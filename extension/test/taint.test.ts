import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { bytesToLatin1, latin1ToBytes } from "../src/lib/bytes";
import { DEFAULT_TAINT_OPTIONS, isEligible, TaintScrubber, variants } from "../src/lib/taint";

interface Vector {
  name: string;
  secrets: { value: string; label: string }[];
  input: string;
  output: string;
  counts: Record<string, number>;
  validJson?: boolean;
}

/** Parses `text` as JSON, and every string inside it that starts like JSON, recursively. */
function parseDeep(text: string): void {
  const visit = (v: unknown): void => {
    if (typeof v === "string") {
      if (/^[[{]/.test(v)) parseDeep(v);
    } else if (Array.isArray(v)) v.forEach(visit);
    else if (typeof v === "object" && v !== null) Object.values(v).forEach(visit);
  };
  visit(JSON.parse(text));
}

const vectors = JSON.parse(readFileSync(new URL("./vectors/taint.json", import.meta.url), "utf8")) as { minTaintLength: number; stopList: string[]; cases: Vector[] };

const utf8 = new TextEncoder();
const fromUtf8 = new TextDecoder();

describe("taint vectors (shared with the Kotlin finalize pass)", () => {
  it("uses the rules file's options", () => {
    expect(vectors.minTaintLength).toBe(DEFAULT_TAINT_OPTIONS.minTaintLength);
    expect(vectors.stopList).toEqual(DEFAULT_TAINT_OPTIONS.stopList);
  });

  for (const v of vectors.cases) {
    it(v.name, () => {
      const scrubber = new TaintScrubber(v.secrets);
      const result = scrubber.scrub(bytesToLatin1(utf8.encode(v.input)));
      expect(fromUtf8.decode(latin1ToBytes(result.text))).toBe(v.output);
      expect(result.counts).toEqual(v.counts);
      expect(scrubber.countHits(result.text)).toBe(0);
      if (v.validJson === true) {
        expect(() => parseDeep(v.input)).not.toThrow();
        expect(() => parseDeep(v.output)).not.toThrow();
      }
    });
  }
});

describe("taint rules", () => {
  it("eligibility", () => {
    expect(isEligible("12345678")).toBe(true);
    expect(isEligible("1234567")).toBe(false);
    expect(isEligible("deleted")).toBe(false);
    expect(isEligible("xxxxxxxxxxxx")).toBe(false);
    expect(isEligible("!R******!")).toBe(false);
  });

  it("variants include URL, form, JSON and HTML forms", () => {
    expect(variants("a b/c\"d")).toEqual(["a b/c\"d", "a%20b%2Fc%22d", "a+b%2Fc%22d", 'a b/c\\"d', 'a b\\/c\\"d', 'a b/c\\\\\\"d', "a b/c&quot;d"]);
  });

  it("scrubs a token that appeared in an HTML body before it was recognized", () => {
    const token = "MOCKTOKEN_abcdef_123456";
    const html = `<html><script>window.__boot = {"cfg":"${token}"};</script></html>`;
    const scrubber = new TaintScrubber([{ value: token, label: "field:fb_dtsg" }]);
    const out = scrubber.scrub(bytesToLatin1(utf8.encode(html))).text;
    expect(out).not.toContain(token);
    expect(out).toContain("!T:field:fb_dtsg!");
  });
});
