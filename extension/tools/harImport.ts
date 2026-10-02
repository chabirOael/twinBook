// HAR importer: converts a HAR file exported from desktop Firefox into the capture format
// (docs/CAPTURE.md), applying both redaction layers with the same code the extension uses
// (src/lib/redact.ts for layer 1, src/lib/taint.ts for layer 2). Everything is built in
// memory; only the finalized, redacted session is written to disk.

import { createHash } from "node:crypto";
import { mkdirSync, renameSync, writeFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { bytesToLatin1, latin1ToBytes } from "../src/lib/bytes";
import { ownProfileOf } from "../src/lib/profiles";
import { BODY_CAP_BYTES, FORMAT_VERSION, hasNoBodyToTap, isTextualContentType, recordRequestBody } from "../src/lib/record";
import { Redactor, sanitizeLabel } from "../src/lib/redact";
import { DEFAULT_TAINT_OPTIONS, TaintScrubber } from "../src/lib/taint";

interface HarHeader {
  name: string;
  value: string;
}
interface HarCookie {
  name: string;
  value: string;
  [attr: string]: unknown;
}
export interface HarEntry {
  startedDateTime?: string;
  time?: number;
  request: {
    method: string;
    url: string;
    httpVersion?: string;
    headers?: HarHeader[];
    cookies?: HarCookie[];
    postData?: { mimeType?: string; text?: string; params?: { name: string; value?: string }[] };
  };
  response: {
    status: number;
    statusText?: string;
    httpVersion?: string;
    headers?: HarHeader[];
    cookies?: HarCookie[];
    content?: { size?: number; mimeType?: string; text?: string; encoding?: string; compression?: number };
    redirectURL?: string;
  };
  cache?: unknown;
  timings?: Record<string, number>;
  serverIPAddress?: string;
  connection?: string;
  pageref?: string;
  _resourceType?: string;
}
export interface Har {
  log: { creator?: { name?: string; version?: string }; browser?: { name?: string; version?: string }; entries: HarEntry[] };
}

export interface ImportResult {
  id: string;
  dir: string;
  entries: number;
  bodies: number;
  rememberedValues: number;
  eligibleValues: number;
  replacements: Record<string, number>;
  verifyHits: number;
  redactions: Redactor["counts"];
}

const enc = new TextEncoder();

function sha256(data: Uint8Array | string): string {
  return createHash("sha256").update(data).digest("hex");
}

/** Firefox HAR has no resource type; guess one from the request and the response type. */
function guessType(e: HarEntry): string {
  if (e._resourceType !== undefined) return e._resourceType;
  const mime = e.response.content?.mimeType ?? "";
  const accept = e.request.headers?.find((h) => h.name.toLowerCase() === "accept")?.value ?? "";
  if (/text\/html/.test(mime) && /text\/html/.test(accept)) return "main_frame";
  if (/javascript|ecmascript/.test(mime) && !e.request.headers?.some((h) => h.name.toLowerCase() === "x-requested-with")) {
    return e.request.method === "GET" && !/json/.test(accept) ? "script" : "xmlhttprequest";
  }
  if (/^image\//.test(mime)) return "image";
  if (/^(video|audio)\//.test(mime)) return "media";
  if (/css/.test(mime)) return "stylesheet";
  if (/font/.test(mime)) return "font";
  return "xmlhttprequest";
}

/** Redacts HAR cookie objects: names and attributes stay, values are replaced (and remembered). */
function cookies(list: HarCookie[] | undefined, redactor: Redactor): HarCookie[] {
  return (list ?? []).map((c) => {
    // Through the Cookie header path so the value is counted and remembered like any cookie.
    const redacted = redactor.cookieHeader(`${c.name}=${c.value}`);
    return { ...c, value: redacted.slice(redacted.indexOf("=") + 1) };
  });
}

/**
 * Converts [har] and writes the finalized session to `outDir/id/`. Throws if that directory
 * exists. Nothing unredacted is written: the files are built in memory, scrubbed, verified,
 * then written.
 */
export function importHar(har: Har, outDir: string, id: string): ImportResult {
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(id)) throw new Error("bad session id");
  const dir = join(outDir, id);
  if (existsSync(dir)) throw new Error(`${dir} exists`);
  const redactor = new Redactor();
  const lines: string[] = [];
  const bodies = new Map<string, string>(); // file -> latin1 data (layer 1 applied)
  const push = (ev: string, rid: string, data: Record<string, unknown>) => lines.push(JSON.stringify({ v: FORMAT_VERSION, ev, rid, profile: "har", ...data }));
  push("start", "", { source: "har", creator: har.log.creator ?? null, browser: har.log.browser ?? null, entries: har.log.entries.length });

  har.log.entries.forEach((e, i) => {
    const rid = `har${i + 1}`;
    const own = ownProfileOf(e.request.url) !== undefined;
    const t = e.startedDateTime === undefined ? null : Date.parse(e.startedDateTime);
    const type = guessType(e);
    const d = {
      url: redactor.url(e.request.url),
      method: e.request.method,
      type,
      httpVersion: e.request.httpVersion ?? null,
      serverIPAddress: e.serverIPAddress ?? null,
      connection: e.connection ?? null,
      pageref: e.pageref ?? null,
      time: e.time ?? null,
    };
    const post = e.request.postData;
    let body: unknown = { kind: "none" };
    if (post !== undefined && own) {
      if (post.params !== undefined && post.params.length > 0) {
        body = { kind: "formData", fields: redactor.formFields(post.params.map((p) => [p.name, p.value ?? ""] as [string, string])) };
      } else if (post.text !== undefined) {
        body = /x-www-form-urlencoded/.test(post.mimeType ?? "")
          ? { kind: "formData", fields: redactor.formFields([...new URLSearchParams(post.text).entries()]) }
          : recordRequestBody({ raw: [{ bytes: enc.encode(post.text).buffer as ArrayBuffer }] }, redactor);
      }
    }
    push("request", rid, { own, t, d, body });
    push("sendHeaders", rid, { own, t, d, headers: redactor.headers(e.request.headers), cookies: cookies(e.request.cookies, redactor) });
    const r = e.response;
    const rd = { ...d, statusCode: r.status, statusLine: `${r.httpVersion ?? ""} ${r.status} ${r.statusText ?? ""}`.trim(), redirectUrl: r.redirectURL ? redactor.url(r.redirectURL) : undefined };
    push("headers", rid, { own, t, d: rd, headers: redactor.headers(r.headers), cookies: cookies(r.cookies, redactor) });
    push("completed", rid, { own, t, d: { ...rd, cache: e.cache ?? null, timings: e.timings ?? null } });

    const content = r.content;
    if (own && content?.text !== undefined && !hasNoBodyToTap(r.status) && (type === "main_frame" || type === "sub_frame" || type === "xmlhttprequest") && isTextualContentType(content.mimeType)) {
      const raw = content.encoding === "base64" ? latin1ToBytes(atob(content.text)) : enc.encode(content.text);
      const recorded = raw.subarray(0, BODY_CAP_BYTES);
      const file = `bodies/${rid}-${bodies.size + 1}.res`;
      bodies.set(file, redactor.text(bytesToLatin1(recorded)));
      push("body", rid, {
        own,
        type,
        contentType: content.mimeType ?? null,
        contentEncoding: r.headers?.find((h) => h.name.toLowerCase() === "content-encoding")?.value ?? null,
        status: "complete",
        file,
        size: raw.length,
        recorded: recorded.length,
        truncated: raw.length > recorded.length,
        cap: BODY_CAP_BYTES,
        sha256: sha256(recorded),
        harContentSize: content.size ?? null,
      });
    }
  });
  push("end", "", { redactions: { ...redactor.counts } });

  // Layer 2 over everything, in memory, then a verification scan.
  const secrets = redactor.secrets.entries();
  const scrubber = new TaintScrubber(secrets);
  const replacements: Record<string, number> = {};
  const add = (counts: Record<string, number>) => {
    for (const [k, v] of Object.entries(counts)) replacements[k] = (replacements[k] ?? 0) + v;
  };
  const events = scrubber.scrub(bytesToLatin1(enc.encode(lines.join("\n") + "\n")));
  add(events.counts);
  const files = new Map<string, string>([["events.ndjson", events.text]]);
  for (const [name, data] of bodies) {
    const s = scrubber.scrub(data);
    add(s.counts);
    files.set(name, s.text);
  }
  let verifyHits = 0;
  for (const data of files.values()) verifyHits += scrubber.countHits(data);
  if (verifyHits !== 0) throw new Error(`verification found ${verifyHits} remaining secret occurrences; nothing written`);

  const dataBytes = [...files.values()].reduce((n, f) => n + f.length, 0);
  const session = {
    format: FORMAT_VERSION,
    id,
    source: "har",
    profiles: ["har"],
    complete: true,
    importedAt: Date.now(),
    harCreator: har.log.creator ?? null,
    entries: har.log.entries.length,
    taint: {
      minTaintLength: DEFAULT_TAINT_OPTIONS.minTaintLength,
      rememberedValues: secrets.length,
      eligibleValues: scrubber.eligibleValues,
      patterns: scrubber.patternCount,
      replacements: Object.values(replacements).reduce((a, b) => a + b, 0),
      byLabel: replacements,
      labels: [...new Set(secrets.map((s) => sanitizeLabel(s.label)))].sort(),
      verifyHits,
    },
    files: files.size,
    dataBytes,
  };
  files.set("session.json", scrubber.scrub(bytesToLatin1(enc.encode(JSON.stringify(session, null, 2) + "\n"))).text);
  secrets.length = 0;
  redactor.secrets.clear();

  // Write to a temporary directory, then rename: a half-written session never appears.
  const tmp = join(outDir, `.partial-${id}`);
  mkdirSync(join(tmp, "bodies"), { recursive: true });
  const sums: string[] = [];
  for (const [name, data] of files) {
    const bytes = latin1ToBytes(data);
    writeFileSync(join(tmp, name), bytes);
    sums.push(`${sha256(bytes)}  ${name}`);
  }
  const checksums = sums.sort().join("\n") + "\n";
  writeFileSync(join(tmp, "checksums.sha256"), checksums);
  writeFileSync(join(tmp, "FINALIZED"), `twinbook-capture ${FORMAT_VERSION}\nchecksums ${sha256(checksums)}\n`);
  renameSync(tmp, dir);
  return {
    id,
    dir,
    entries: har.log.entries.length,
    bodies: bodies.size,
    rememberedValues: session.taint.rememberedValues,
    eligibleValues: scrubber.eligibleValues,
    replacements,
    verifyHits,
    redactions: { ...redactor.counts },
  };
}
