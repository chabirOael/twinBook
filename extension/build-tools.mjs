// Bundles the Node tools (tools/*.ts) into build/ for tools/har-import.sh.
import { build } from "esbuild";

await build({
  entryPoints: { "har-import": "tools/harImportCli.ts" },
  outdir: "build",
  outExtension: { ".js": ".mjs" },
  bundle: true,
  platform: "node",
  format: "esm",
  target: "node22",
  logLevel: "warning",
});
console.log("built build/har-import.mjs");
