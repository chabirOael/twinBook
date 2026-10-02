// Bundles the extension into dist/ and writes dist/manifest.json with a per-build version.
// dist/ is what web-ext lints and what Gradle packages into the APK.
//
// TWIN_BRIDGE_MARKER (environment, optional) is compiled into the bundle and reported over the
// bridge. tools/extension-update-test.sh uses it to build a changed extension.
import { build } from "esbuild";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { stampVersion } from "./build-lib.mjs";

const outdir = "dist";
const marker = process.env.TWIN_BRIDGE_MARKER || "default";

await rm(outdir, { recursive: true, force: true });
await mkdir(outdir, { recursive: true });
const entries = { background: "src/background.ts", anchor: "src/anchor.ts" };
for (const [name, entry] of Object.entries(entries)) {
  await build({
    entryPoints: [entry],
    outfile: `${outdir}/${name}.js`,
    bundle: true,
    format: "iife",
    target: "firefox128",
    logLevel: "warning",
    legalComments: "none",
    define: { __TWIN_BRIDGE_MARKER__: JSON.stringify(marker) },
  });
}

const manifestText = await readFile("manifest.json", "utf8");
const manifest = JSON.parse(manifestText);
const bundles = await Promise.all(Object.keys(entries).map((n) => readFile(`${outdir}/${n}.js`)));
manifest.version = stampVersion(manifest.version, [manifestText, ...bundles]);
await writeFile(`${outdir}/manifest.json`, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`twin-bridge ${manifest.version} (marker ${marker}) built into ${outdir}/`);
