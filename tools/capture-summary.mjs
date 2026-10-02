#!/usr/bin/env node
// Usage: node tools/capture-summary.mjs [--short] <captures/<session-id>>
//
// Summarizes a pulled capture session (docs/CAPTURE.md): records by host and type, content
// types, encodings, protocols, cache and service-worker hints, the observe-only filter, the
// section 2 leads of the M2a prompt, and a redaction report (cookie names with attributes,
// layer 1 counts, layer 2 summary, and a scan for keyed values left unredacted).
// Reads only the redacted session; prints names and counts, never values.
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const args = process.argv.slice(2);
const short = args[0] === "--short";
const dir = short ? args[1] : args[0];
if (!dir || !existsSync(join(dir, "events.ndjson"))) {
  console.error("usage: node tools/capture-summary.mjs [--short] <session dir>");
  process.exit(2);
}

const session = existsSync(join(dir, "session.json")) ? JSON.parse(readFileSync(join(dir, "session.json"), "utf8")) : {};
const lines = readFileSync(join(dir, "events.ndjson"), "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
const by = (ev) => lines.filter((l) => l.ev === ev);
const host = (u) => { try { return new URL(u).hostname; } catch { return "?"; } };
const count = (map, key, n = 1) => map.set(key, (map.get(key) ?? 0) + n);
const table = (title, map, limit = 40) => {
  console.log(`\n${title}`);
  if (map.size === 0) console.log("  (none)");
  for (const [k, v] of [...map].sort((a, b) => b[1] - a[1]).slice(0, limit)) console.log(`  ${String(v).padStart(6)}  ${k}`);
  if (map.size > limit) console.log(`  ... ${map.size - limit} more`);
};
const header = (headers, name) => headers?.find((h) => h.name.toLowerCase() === name)?.value;

const requests = by("request");
const bodies = by("body");
const observes = by("observe");
const reqByRid = new Map(requests.map((r) => [r.rid, r]));
const ownRequests = requests.filter((r) => r.own);

console.log(`session ${session.id ?? "?"}  format ${session.format ?? "?"}  profiles ${JSON.stringify(session.profiles ?? [])}  complete ${session.complete}`);
console.log(`records ${lines.length}  requests ${requests.length} (own hosts ${ownRequests.length}, third party ${requests.length - ownRequests.length})  bodies ${bodies.length}  data bytes ${session.dataBytes ?? "?"}`);
const taint = session.taint ?? {};
console.log(`layer 2: ${taint.rememberedValues ?? "?"} values remembered, ${taint.eligibleValues ?? "?"} eligible, ${taint.replacements ?? "?"} replacements, verification hits ${taint.verifyHits ?? "?"}`);
if (short) process.exit(0);

const hostType = new Map();
for (const r of requests) count(hostType, `${r.own ? "own " : "3rd "} ${host(r.d.url).padEnd(34)} ${r.d.type}`);
table("Requests by host and type", hostType, 80);

const types = new Map();
const encodings = new Map();
const statuses = new Map();
for (const h of by("headers")) {
  const r = reqByRid.get(h.rid);
  const ct = (header(h.headers, "content-type") ?? "(none)").split(";")[0];
  count(types, `${h.own ? "own" : "3rd"} ${ct}`);
  count(encodings, `${h.own ? "own" : "3rd"} ${header(h.headers, "content-encoding") ?? "(identity)"}`);
  count(statuses, `${h.d.statusCode} ${r?.d.type ?? ""}`);
}
table("Content types", types);
table("Content encodings", encodings);
table("Status codes", statuses);

const protocols = new Map();
for (const h of by("headers")) count(protocols, `${h.own ? "own" : "3rd"} ${(h.d.statusLine ?? "").split(" ")[0] || "(no status line)"}`);
for (const s of by("security")) count(protocols, `TLS ${s.security?.protocolVersion ?? "?"} ${s.security?.cipherSuite ?? ""}`);
table("Protocols (status line) and TLS", protocols);

const cache = new Map();
for (const c of by("completed")) {
  count(cache, `fromCache=${c.d.fromCache} ${c.own ? "own" : "3rd"}`);
}
for (const r of requests) {
  if (r.d.originUrl && /sw|serviceworker|service_worker/i.test(r.d.originUrl)) count(cache, "originUrl looks like a service worker");
  if (r.d.tabId === -1) count(cache, `tabId=-1 (no tab: service worker or background) ${r.own ? "own" : "3rd"}`);
}
table("Cache and service-worker hints", cache);

const kinds = new Map();
const candidates = new Map();
let changed = 0;
let documents = 0;
for (const o of observes) {
  count(kinds, `${o.kind} mode=${o.mode} ${host(reqByRid.get(o.rid)?.d.url ?? "")}`);
  if (o.stats.bytesIn !== o.stats.bytesOut) changed++;
  documents += o.probe?.documents ?? 0;
  for (const [k, v] of Object.entries(o.probe?.candidateTotals ?? {})) count(candidates, k, v);
}
table("Observing filter attached (kind, mode, host)", kinds);
console.log(`  responses observed ${observes.length}, documents probed ${documents}, responses where bytes out != bytes in: ${changed}`);
table("Probe candidate keys found", candidates);

// Leads from section 2 of the M2a prompt, checked against recorded URLs and bodies.
const bodyTexts = bodies.filter((b) => existsSync(join(dir, b.file))).map((b) => ({ b, text: readFileSync(join(dir, b.file), "latin1") }));
const urls = requests.map((r) => r.d.url);
const anyBody = (re) => bodyTexts.filter(({ text }) => re.test(text)).length;
const anyUrl = (re) => urls.filter((u) => re.test(u)).length;
const leads = new Map([
  ["StartFBWebBloks marker in a body", anyBody(/StartFBWebBloks/)],
  ["bloks_payload in a body", anyBody(/bloks_payload/)],
  ["requests to /async/wbloks/", anyUrl(/\/async\/wbloks\//)],
  ["/unified/login_via/app/ in a URL", anyUrl(/\/unified\/login_via\/app\//)],
  ["/unified/login_via/app/ in a body", anyBody(/\/unified\/login_via\/app\//)],
  ["__comet_req in a body or URL", anyBody(/__comet_req/) + anyUrl(/__comet_req/)],
  ["requests to /api/graphql/", anyUrl(/\/api\/graphql\//)],
  ["/api/graphql/ named in a body", anyBody(/\/api\/graphql\//)],
  ["requests to /ajax/bz", anyUrl(/\/ajax\/bz/)],
  ["requests to /ajax/qm/", anyUrl(/\/ajax\/qm\//)],
  ["requests to fbsbx.com", urls.filter((u) => host(u).endsWith("fbsbx.com")).length],
  ["Google hosts contacted", urls.filter((u) => /(^|\.)(google|googleapis|gstatic|doubleclick|googlesyndication|googletagmanager)\.[a-z.]+$/.test(host(u))).length],
  ["Google hosts contacted from an fbsbx.com frame", requests.filter((r) => /google|doubleclick/.test(host(r.d.url)) && /fbsbx\.com/.test(`${r.d.documentUrl} ${JSON.stringify(r.d.frameAncestors ?? [])}`)).length],
  ["custom-scheme navigation in a body (fb:// or intent:)", anyBody(/["'](fb|intent):\/\//)],
]);
table("Section 2 leads (count of matching requests or bodies)", leads);

// Token and session-shaped field names, by where they appear (names only).
const fieldNames = ["fb_dtsg", "lsd", "jazoest", "__user", "__s", "__hsi", "__dyn", "__csr", "__rev", "__req", "__a", "__spin_r"];
const fieldSeen = new Map();
for (const r of requests) {
  for (const n of fieldNames) {
    if (new RegExp(`[?&]${n}=`).test(r.d.url)) count(fieldSeen, `${n} in URL`);
    if (r.body?.fields?.some(([k]) => k === n)) count(fieldSeen, `${n} in form body`);
  }
}
for (const { text } of bodyTexts) {
  for (const n of ["fb_dtsg", "lsd", "jazoest", "DTSGInitialData", "LSD", "__spin_r", "__hsi"]) if (text.includes(`"${n}"`) || text.includes(`name="${n}"`)) count(fieldSeen, `${n} named in a response body`);
}
table("Token and session-shaped field names", fieldSeen);

// Redaction report.
const setCookies = new Map();
const cookieNames = new Map();
for (const h of [...by("headers"), ...by("redirect")]) {
  for (const sc of (header(h.headers, "set-cookie") ?? "").split("\n").filter(Boolean)) {
    const [pair, ...attrs] = sc.split(";");
    const [name, value] = [pair.slice(0, pair.indexOf("=")), pair.slice(pair.indexOf("=") + 1)];
    const placeholder = /^(!R\**!|\*{1,2})$/.test(value) ? `value redacted (${value.length} chars)` : value === "" ? "empty value" : "VALUE NOT REDACTED";
    count(setCookies, `${host(reqByRid.get(h.rid)?.d.url ?? h.d.url)} ${name}: ${placeholder};${attrs.join(";")}`);
  }
}
for (const s of by("sendHeaders")) {
  for (const part of (header(s.headers, "cookie") ?? "").split(";").filter((p) => p.includes("="))) {
    const [name, value] = [part.slice(0, part.indexOf("=")).trim(), part.slice(part.indexOf("=") + 1)];
    count(cookieNames, `${name} ${/^(!R\**!|\*{1,2})$/.test(value) ? "redacted" : "NOT REDACTED"}`);
  }
}
table("Set-Cookie (name: value state; attributes)", setCookies, 80);
table("Cookie request header names", cookieNames);
const end = by("end")[0];
console.log(`\nLayer 1 redactions (extension): ${JSON.stringify(end?.redactions ?? session.extensionReport?.redactions ?? {})}`);
console.log(`Layer 2: ${JSON.stringify({ byLabel: taint.byLabel, labels: taint.labels?.length, verifyHits: taint.verifyHits })}`);

// Residual scan: keyed values that layer 1 should have replaced.
const residual = new Map();
const keyed = [
  [/"(token|fb_dtsg|lsd|jazoest|access_token|async_get_token)"\s*:\s*"(?!!R|\*)[^"]+"/g, "JSON keyed token value"],
  [/name="(fb_dtsg|lsd|jazoest|pass|email)"[^>]*value="(?!!R|\*)[^"]+"/g, "input tag value"],
  [/[?&;](fb_dtsg|fb_dtsg_ag|lsd|jazoest|access_token)=(?!!R|\*)[^&"'\s#<>;]+/g, "key=value"],
];
const scanned = [readFileSync(join(dir, "events.ndjson"), "latin1"), ...bodyTexts.map((x) => x.text)];
for (const text of scanned) for (const [re, label] of keyed) count(residual, label, (text.match(re) ?? []).length);
table("Residual scan for keyed values left unredacted (all must be 0)", residual);
const files = readdirSync(join(dir, "bodies")).length;
console.log(`\nbody files ${files}`);
