// Command line for the HAR importer: node build/har-import.mjs <file.har> --out <dir> [--id <id>]
// Prints counts only, never values. tools/har-import.sh builds and runs it.
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { importHar, type Har } from "./harImport";

const args = process.argv.slice(2);
const opt = (name: string): string | undefined => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const file = args.find((a, i) => !a.startsWith("--") && !(i > 0 && args[i - 1]!.startsWith("--")));
const out = opt("--out");
if (file === undefined || out === undefined) {
  process.stderr.write("usage: har-import <file.har> --out <dir> [--id <session-id>]\n");
  process.exitCode = 2;
} else {
  const stamp = new Date().toISOString().replace(/[-:]/g, "").replace("T", "-").slice(0, 15);
  const id = opt("--id") ?? `har-${stamp}-${basename(file, ".har").replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 30)}`;
  const har = JSON.parse(readFileSync(file, "utf8")) as Har;
  const r = importHar(har, out, id);
  process.stdout.write(
    `imported ${r.entries} entries into ${r.dir}: ${r.bodies} bodies, layer 1 ${JSON.stringify(r.redactions)}, ` +
      `layer 2 ${r.eligibleValues}/${r.rememberedValues} values eligible, replacements ${JSON.stringify(r.replacements)}, verification hits ${r.verifyHits}\n`,
  );
}
