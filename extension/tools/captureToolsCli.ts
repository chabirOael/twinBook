// Command line for the offline capture tools. tools/capture-tools.sh builds and runs it.
// Every command prints names, counts, lengths and structure only, never a recorded value.
//
//   rescrub <session dir>... [--replace]   layer 1 + layer 2 again with the current rules
//   scan <session dir>...                  keys whose values are long, opaque and recur
//   findings <session dir>... [--rules f]  the numbers of docs/findings/payloads.md
//   keypaths <key> <session dir>...        where a key occurs in the feed edges (exploration)
//   fixtures <session dir>... [--census]   sanitized fixtures into fixtures/ (fixtures/README.md)
import { join } from "node:path";
import { findingsReport, keyPathsReport } from "./findings/report";
import { buildFixtureManifest } from "./fixtures/manifest";
import { buildFixtures, readAllowlist, writeFixtures } from "./fixtures/sanitize";
import { ancestorScan, formatScan, scanSession } from "./opaqueScan";
import { Session } from "./findings/session";
import { rescrubSession } from "./rescrub";

const [cmd, ...rest] = process.argv.slice(2);
const flags = new Set(rest.filter((a) => a.startsWith("--")));
const rulesAt = rest.indexOf("--rules");
const rulesFile = rulesAt >= 0 ? rest[rulesAt + 1]! : join(process.env["TWINBOOK_ROOT"] ?? "..", "rules", "ads-v1.json");
const paths = rest.filter((a, i) => !a.startsWith("--") && !(i > 0 && rest[i - 1] === "--rules"));

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
  case "keypaths": {
    if (paths.length < 2) usage();
    process.stdout.write(keyPathsReport(paths.slice(1), rulesFile, paths[0]!) + "\n");
    break;
  }
  default:
    usage();
}
