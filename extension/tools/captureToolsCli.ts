// Command line for the offline capture tools. tools/capture-tools.sh builds and runs it.
// Every command prints names, counts, lengths and structure only, never a recorded value.
//
//   rescrub <session dir>... [--replace]   layer 1 + layer 2 again with the current rules
//   scan <session dir>...                  keys whose values are long, opaque and recur
import { formatScan, scanSession } from "./opaqueScan";
import { rescrubSession } from "./rescrub";

const [cmd, ...rest] = process.argv.slice(2);
const flags = new Set(rest.filter((a) => a.startsWith("--")));
const paths = rest.filter((a) => !a.startsWith("--"));

function usage(): never {
  process.stderr.write("usage: capture-tools rescrub <session dir>... [--replace]\n       capture-tools scan <session dir>...\n");
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
    for (const p of paths) process.stdout.write(formatScan(p, scanSession(p)) + "\n");
    break;
  }
  default:
    usage();
}
