// Offline re-scrub: re-applies layer 1 redaction with the current rules, then layer 2 (taint)
// with every value layer 1 found this time, to an already pulled session. Used when the rules
// grow after a capture was recorded (M2b: camel-case token names, findings of the opaque-value
// scan). The original session is only read; the result is a new finalized session next to it,
// `<id>-rescrub`, with a `rescrub` section in its session.json. Nothing unredacted is written:
// the files are built in memory, scrubbed and verified first.
//
// A re-scrubbed copy can itself be re-scrubbed in place (`inPlace`, M3a), so rules added after
// the originals were deleted still reach it: the new copy is written into a temporary
// directory and swapped in (sessionIo.replaceFinalizedSession). Placeholders already in the
// copy (`!R***!`, `!T:<label>!`) are left as they are.

import { existsSync, readFileSync, rmSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { bytesToLatin1, latin1ToBytes } from "../src/lib/bytes";
import { pickDetails } from "../src/lib/record";
import { Redactor, RULES, sanitizeLabel, type Header } from "../src/lib/redact";
import { DEFAULT_TAINT_OPTIONS, TaintScrubber } from "../src/lib/taint";
import { loadSession, parseLines, replaceFinalizedSession, sha256, writeFinalizedSession, type SessionLine } from "./sessionIo";

export const RESCRUB_SUFFIX = "-rescrub";

export interface RescrubResult {
  source: string;
  dir: string;
  lines: number;
  bodies: number;
  layer1: Redactor["counts"];
  rememberedValues: number;
  eligibleValues: number;
  replacements: Record<string, number>;
  verifyHits: number;
}

const utf8 = new TextEncoder();

function utf8Latin1(s: string): string {
  return bytesToLatin1(utf8.encode(s));
}

/** Layer 1 over one record line: URLs in details, headers, cookies, request bodies, then the whole line as text. */
export function redactLine(line: SessionLine, r: Redactor): string {
  const out: SessionLine = { ...line };
  if (out.d !== undefined && typeof out.d === "object" && out.d !== null) out.d = pickDetails(out.d, r);
  if (Array.isArray(out["headers"])) out["headers"] = r.headers(out["headers"] as Header[]);
  if (Array.isArray(out["cookies"])) {
    out["cookies"] = (out["cookies"] as { name: string; value: string }[]).map((c) => {
      const redacted = r.cookieHeader(`${c.name}=${c.value}`);
      return { ...c, value: redacted.slice(redacted.indexOf("=") + 1) };
    });
  }
  const body = out["body"] as { kind?: string; fields?: [string, string | null][]; text?: string } | undefined;
  if (body !== undefined && body !== null && typeof body === "object") {
    const b = { ...body };
    if (b.kind === "formData" && Array.isArray(b.fields)) b.fields = r.formFields(b.fields);
    if (b.kind === "raw" && typeof b.text === "string") b.text = r.text(b.text);
    out["body"] = b;
  }
  // Catch-all: keyed values anywhere else in the line, at any escaping level.
  return r.text(JSON.stringify(out));
}

export function rescrubSession(srcDir: string, options: { replace?: boolean; inPlace?: boolean } = {}): RescrubResult {
  const src = loadSession(srcDir);
  const id = basename(srcDir);
  const inPlace = options.inPlace === true;
  if (inPlace && !id.endsWith(RESCRUB_SUFFIX)) throw new Error(`${id} is not a re-scrubbed copy; --in-place rewrites only -rescrub copies (originals are never changed)`);
  if (!inPlace && id.endsWith(RESCRUB_SUFFIX)) throw new Error(`${id} is already a re-scrubbed copy; re-scrub the original, or pass --in-place to rewrite the copy`);
  const outId = inPlace ? id : `${id}${RESCRUB_SUFFIX}`;
  const outDir = inPlace ? srcDir : join(dirname(srcDir), outId);
  const prior = (src.session["rescrub"] ?? {}) as { source?: string; passes?: number };
  if (!inPlace && existsSync(outDir)) {
    const prior = JSON.parse(readFileSync(join(outDir, "session.json"), "utf8")) as { rescrub?: { source?: string } };
    if (options.replace !== true) throw new Error(`${outDir} exists; pass --replace to rebuild it`);
    if (prior.rescrub?.source !== id) throw new Error(`${outDir} is not a re-scrub of ${id}; not touching it`);
    rmSync(outDir, { recursive: true });
  }

  const r = new Redactor();
  const lines = parseLines(src.eventsText).map((l) => redactLine(l, r));
  const files = new Map<string, string>();
  files.set("events.ndjson", utf8Latin1(lines.join("\n") + "\n"));
  for (const [name, data] of src.bodies) files.set(name, r.text(data));

  const secrets = r.secrets.entries();
  const scrubber = new TaintScrubber(secrets);
  const replacements: Record<string, number> = {};
  for (const [name, data] of files) {
    const s = scrubber.scrub(data);
    for (const [k, v] of Object.entries(s.counts)) replacements[k] = (replacements[k] ?? 0) + v;
    files.set(name, s.text);
  }
  let verifyHits = 0;
  for (const data of files.values()) verifyHits += scrubber.countHits(data);
  if (verifyHits !== 0) throw new Error(`verification found ${verifyHits} remaining occurrences; nothing written`);

  const session = {
    ...src.session,
    id: outId,
    rescrub: {
      source: inPlace ? (prior.source ?? id) : id,
      // Number of re-scrubs this copy has been through (in place counts too).
      passes: inPlace ? (prior.passes ?? 1) + 1 : 1,
      sourceChecksums: sha256(readFileSync(join(srcDir, "checksums.sha256"))),
      rulesVersion: (RULES as unknown as { version?: number }).version ?? null,
      rulesSha256: sha256(JSON.stringify(RULES)),
      keys: RULES.keys.length,
      at: Date.now(),
      layer1: { ...r.counts },
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
    },
  };
  files.set("session.json", scrubber.scrub(utf8Latin1(JSON.stringify(session, null, 2) + "\n")).text);
  secrets.length = 0;
  r.secrets.clear();
  if (inPlace) replaceFinalizedSession(outDir, files);
  else writeFinalizedSession(outDir, files);
  return {
    source: id,
    dir: outDir,
    lines: lines.length,
    bodies: src.bodies.size,
    layer1: { ...r.counts },
    rememberedValues: session.rescrub.taint.rememberedValues,
    eligibleValues: scrubber.eligibleValues,
    replacements,
    verifyHits,
  };
}

/** Decodes a latin1 file string as UTF-8 text. */
export function latin1ToText(s: string): string {
  return new TextDecoder().decode(latin1ToBytes(s));
}
