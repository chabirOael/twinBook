// Helpers for build.mjs, kept separate so Vitest can test them.
import { createHash } from "node:crypto";

/**
 * Version of a build: the manifest's three-part version plus a fourth part derived from the
 * content of the build. Gecko reinstalls a built-in extension whenever the installed version
 * string differs from the packaged one (GeckoViewWebExtension.ensureBuiltIn compares with !=),
 * so every changed build must carry a different version, and an unchanged build the same one
 * (Gradle up-to-date checks). The fourth part is 1..268435456: at most 9 digits, no leading zero.
 */
export function stampVersion(baseVersion, contents) {
  if (!/^\d+\.\d+\.\d+$/.test(baseVersion)) {
    throw new Error(`manifest version must be x.y.z, got ${baseVersion}`);
  }
  const hash = createHash("sha256");
  for (const c of contents) {
    hash.update(c);
    hash.update("\0");
  }
  const n = Number.parseInt(hash.digest("hex").slice(0, 7), 16) + 1;
  return `${baseVersion}.${n}`;
}
