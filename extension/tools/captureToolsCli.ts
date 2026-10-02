// Command line for the offline capture tools. tools/capture-tools.sh builds and runs it.
// Every command prints names, counts, lengths and structure only, never a recorded value.
//
//   rescrub <session dir>... [--replace]   layer 1 + layer 2 again with the current rules
//   scan <session dir>...                  keys whose values are long, opaque and recur
//   findings <session dir>... [--rules f]  the numbers of docs/findings/payloads.md
//   keypaths <key> <session dir>...        where a key occurs in the feed edges (exploration)
//   fixtures <session dir>... [--census]   sanitized fixtures into fixtures/ (fixtures/README.md)
//   leakscan <file>... --raw <session dir>[,<session dir>...]
//                                          raw non-structural values, identifier tokens, e-mails,
//                                          phone numbers in text files (names and counts only)
import { join } from "node:path";
import { findingsReport, keyPathsReport } from "./findings/report";
import { buildFixtureManifest } from "./fixtures/manifest";
import { buildFixtures, readAllowlist, writeFixtures } from "./fixtures/sanitize";
import { rawValues, tokensOf } from "./fixtures/leak";
import { readFileSync } from "node:fs";
import { ancestorScan, formatScan, scanSession } from "./opaqueScan";
import { Session } from "./findings/session";
import { rescrubSession } from "./rescrub";

const [cmd, ...rest] = process.argv.slice(2);
const flags = new Set(rest.filter((a) => a.startsWith("--")));
const rulesAt = rest.indexOf("--rules");
const rulesFile = rulesAt >= 0 ? rest[rulesAt + 1]! : join(process.env["TWINBOOK_ROOT"] ?? "..", "rules", "ads-v1.json");
const paths = rest.filter((a, i) => !a.startsWith("--") && !(i > 0 && (rest[i - 1] === "--rules" || rest[i - 1] === "--raw")));
const rawAt = rest.indexOf("--raw");

/** Every parsed response document of a session: NDJSON and guarded bodies, document islands. */
function allDocuments(dir: string): unknown[] {
  const s = new Session(dir);
  const docs: unknown[] = [];
  for (const r of s.recs.values()) {
    if (r.body === undefined) continue;
    if (s.info(r)?.type === "main_frame") docs.push(...s.islands(r).map((x) => x.json));
    else docs.push(...s.guarded(r).docs, ...s.ndjson(r).docs);
  }
  return docs;
}

function usage(): never {
  process.stderr.write(
    "usage: capture-tools rescrub <session dir>... [--replace]\n       capture-tools scan <session dir>...\n       capture-tools findings <session dir>... [--rules <file>]\n       capture-tools keypaths <key> <session dir>...\n       capture-tools fixtures <session dir>... [--census]\n",
  );
  process.exit(2);
}

switch (cmd) {
  case "rescrub": {
    if (paths.length === 0) usage();
    for (const p of paths) {
      const r = rescrubSession(p, { replace: flags.has("--replace") });
      process.stdout.write(
        `rescrub ${r.source} -> ${r.dir}: ${r.lines} lines, ${r.bodies} bodies, layer 1 ${JSON.stringify(r.layer1)}, ` +
          `layer 2 ${r.eligibleValues}/${r.rememberedValues} values eligible, replacements ${JSON.stringify(r.replacements)}, verification hits ${r.verifyHits}\n`,
      );
    }
    break;
  }
  case "scan": {
    if (paths.length === 0) usage();
    for (const p of paths) {
      process.stdout.write(formatScan(p, scanSession(p)) + "\n");
      const rows = ancestorScan(p, allDocuments);
      process.stdout.write(`  long unredacted strings below credential-like keys (content tokens excluded): ${rows.length}\n`);
      for (const r of rows) process.stdout.write(`  ${String(r.count).padStart(6)}  len ${r.lengths.sort((a, b) => a - b).join(",").padEnd(20)}  ${r.ancestor} > ${r.key}\n`);
    }
    break;
  }
  case "findings": {
    if (paths.length === 0) usage();
    process.stdout.write(findingsReport(paths, rulesFile) + "\n");
    break;
  }
  case "fixtures": {
    if (paths.length === 0) usage();
    const dir = join(process.env["TWINBOOK_ROOT"] ?? "..", "fixtures");
    const sessions = paths.map((p) => new Session(p));
    const set = buildFixtures(sessions, readAllowlist(dir));
    if (flags.has("--census")) {
      process.stdout.write(`enum-like candidates (key=VALUE count), to review for fixtures/enum-allowlist.json:\n`);
      for (const [k, v] of [...set.stats.enumCandidates].sort()) process.stdout.write(`  ${String(v).padStart(5)}  ${k}\n`);
      break;
    }
    const manifest = buildFixtureManifest(sessions, set, rulesFile);
    writeFixtures(dir, set, manifest);
    const st = set.stats;
    process.stdout.write(
      `fixtures: ${set.files.size} files, ${set.entries.reduce((a, e) => a + e.bytes, 0)} bytes; strings ${st.strings} (kept ${st.kept}, replaced ${st.replaced}, URLs ${st.urls}), numbers remapped ${st.numbersRemapped}, keys replaced ${st.keysReplaced}, enum candidates ${st.enumCandidates.size}\n`,
    );
    break;
  }
  case "leakscan": {
    if (paths.length === 0 || rawAt < 0) usage();
    const raw = rawValues(rest[rawAt + 1]!.split(","), join(process.env["TWINBOOK_ROOT"] ?? "..", "fixtures"));
    const long = [...raw.strings].filter((x) => x.length >= 8);
    let total = 0;
    for (const f of paths) {
      const text = readFileSync(f, "utf8");
      const hits = long.filter((x) => text.includes(x)).length;
      const tokens = new Set(text.split(/[^A-Za-z0-9]+/).filter(Boolean));
      const tokenHits = [...tokens].filter((t) => raw.tokens.has(t) || (/^\d{6,}$/.test(t) && raw.numbers.has(t))).length;
      const emails = (text.match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g) ?? []).length;
      const phones = (text.match(/\+\d{1,3}[\s.-]?\(?\d{1,4}\)?(?:[\s.-]\d{2,4}){2,4}/g) ?? []).length;
      total += hits + tokenHits + emails + phones;
      if (hits + tokenHits + emails + phones > 0) process.stdout.write(`  ${f}: raw strings ${hits}, raw tokens ${tokenHits}, e-mails ${emails}, phones ${phones}\n`);
      if (flags.has("--show")) {
        // Matched values are shown only when they cannot be personal (no space next to a
        // capital letter, no run of 5+ digits); otherwise only their shape.
        const show = (x: string): string => (/ [A-Z]|[A-Z][a-z]+ [A-Z]/.test(x) || /\d{5,}/.test(x) ? `<shape ${x.replace(/[a-z]/g, "a").replace(/[A-Z]/g, "A").replace(/\d/g, "9").replace(/(.)\1+/g, "$1+")}>` : x);
        for (const x of long.filter((v) => text.includes(v))) process.stdout.write(`      string: ${show(x)}\n`);
        for (const t of [...tokens].filter((t) => raw.tokens.has(t) || (/^\d{6,}$/.test(t) && raw.numbers.has(t)))) process.stdout.write(`      token: ${show(t)}\n`);
        for (const m of text.match(/[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g) ?? []) process.stdout.write(`      e-mail: ${m.replace(/^[^@]+/, "<local>")}\n`);
      }
      void tokensOf;
    }
    process.stdout.write(`leakscan: ${paths.length} files against ${long.length} raw strings of 8+ characters, ${raw.tokens.size} identifier tokens, ${raw.numbers.size} remapped numbers: ${total} findings\n`);
    break;
  }
  case "keypaths": {
    if (paths.length < 2) usage();
    process.stdout.write(keyPathsReport(paths.slice(1), rulesFile, paths[0]!) + "\n");
    break;
  }
  default:
    usage();
}
