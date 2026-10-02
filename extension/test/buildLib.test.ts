import { describe, expect, it } from "vitest";
// @ts-expect-error plain JavaScript module without types
import { stampVersion } from "../build-lib.mjs";

describe("stampVersion", () => {
  it("adds a stable fourth part derived from the content", () => {
    const a = stampVersion("0.1.0", ["manifest", "bundle"]) as string;
    expect(a).toMatch(/^0\.1\.0\.[1-9]\d{0,8}$/);
    expect(stampVersion("0.1.0", ["manifest", "bundle"])).toBe(a);
    expect(stampVersion("0.1.0", ["manifest", "bundle2"])).not.toBe(a);
    expect(stampVersion("0.1.0", ["manifestbundle"])).not.toBe(stampVersion("0.1.0", ["manifest", "bundle"]));
  });

  it("rejects a base version that is not x.y.z", () => {
    expect(() => stampVersion("0.1", [])).toThrow();
  });
});
