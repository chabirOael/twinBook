// Bundles the extension into dist/ and copies manifest.json next to the bundle.
// dist/ is what web-ext lints and what Gradle packages into the APK.
import { build } from "esbuild";
import { copyFile, mkdir, rm } from "node:fs/promises";

const outdir = "dist";

await rm(outdir, { recursive: true, force: true });
await mkdir(outdir, { recursive: true });
await build({
  entryPoints: ["src/background.ts"],
  outfile: `${outdir}/background.js`,
  bundle: true,
  format: "iife",
  target: "firefox128",
  logLevel: "warning",
  legalComments: "none",
});
await copyFile("manifest.json", `${outdir}/manifest.json`);
console.log(`twin-bridge built into ${outdir}/`);
