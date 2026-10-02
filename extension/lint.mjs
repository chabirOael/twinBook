// Lint policy for dist/: web-ext lint errors always fail. Warnings fail unless they match an
// entry in lint-allowlist.json (code, file and message substring), each with a reason.
// Notices are printed and never fail.
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";

const run = spawnSync("npx", ["--no-install", "web-ext", "lint", "--source-dir", "dist", "--self-hosted", "--output", "json"], {
  encoding: "utf8",
});
let report;
try {
  report = JSON.parse(run.stdout);
} catch {
  console.error(run.stdout, run.stderr);
  console.error("lint: could not parse web-ext output");
  process.exit(1);
}
const allow = JSON.parse(readFileSync("lint-allowlist.json", "utf8")).warnings;
const matches = (w, a) => w.code === a.code && (a.file === undefined || w.file === a.file) && (a.messageIncludes === undefined || `${w.message} ${w.description ?? ""}`.includes(a.messageIncludes));

const show = (m) => `${m.code} ${m.file ?? ""}${m.line ? `:${m.line}` : ""} ${m.message}`;
const allowed = [];
const failing = [...report.errors.map((e) => ({ ...e, level: "error" }))];
for (const w of report.warnings) {
  const entry = allow.find((a) => matches(w, a));
  if (entry) allowed.push({ w, entry });
  else failing.push({ ...w, level: "warning" });
}
for (const n of report.notices) console.log(`notice   ${show(n)}`);
for (const { w, entry } of allowed) console.log(`allowed  ${show(w)}\n         reason: ${entry.reason}`);
for (const f of failing) console.log(`FAIL     ${f.level} ${show(f)}`);
const unused = allow.filter((a) => !report.warnings.some((w) => matches(w, a)));
for (const a of unused) console.log(`stale allowlist entry (no matching warning): ${a.code} ${a.messageIncludes ?? ""}`);
console.log(`lint: errors ${report.errors.length}, warnings ${report.warnings.length} (allowed ${allowed.length}), notices ${report.notices.length}`);
process.exit(failing.length > 0 ? 1 : 0);
