import { describe, expect, it } from "vitest";
import { DROP, KEEP, NdjsonStreamFilter, type DocumentRule, type FilterError } from "../src/lib/ndjsonFilter";
import { mockAdRule } from "../src/lib/mockAdRule";
import { dec, enc, feed, joined, randomCuts } from "./helpers";

const edge = (id: string, ad = false, text = `post ${id}`) => ({ node: { id, text, ...(ad ? { mock_sponsored: true } : {}) }, cursor: `c${id}` });

function stream(opts: { guard?: boolean; adFirst?: boolean; adMiddle?: boolean; adLast?: boolean; text?: string } = {}): { input: string; lines: string[] } {
  const first = JSON.stringify({ data: { viewer: { news_feed: { edges: [edge("1", opts.adFirst), edge("2", false, opts.text), edge("3")] } } }, extensions: { is_final: false } });
  const later = [4, 5, 6].map((i) => JSON.stringify({ data: edge(String(i), i === 5 && opts.adMiddle, opts.text), path: ["viewer", "news_feed", "edges", i - 1], extensions: { is_final: false } }));
  const last = JSON.stringify({ data: edge("7", opts.adLast), path: ["viewer", "news_feed", "edges", 6], extensions: { is_final: true } });
  const lines = [first, ...later, last];
  return { input: (opts.guard ? "for (;;);" : "") + lines.join("\n") + "\n", lines };
}

function filterAll(input: string | Uint8Array, cuts: number[] = [], rule: DocumentRule = mockAdRule) {
  const errors: FilterError[] = [];
  const f = new NdjsonStreamFilter(rule, { onError: (e) => errors.push(e) });
  const bytes = typeof input === "string" ? enc.encode(input) : input;
  const outputs = feed(f, bytes, cuts);
  return { out: joined(outputs), outputs, errors, stats: f.stats };
}

describe("NdjsonStreamFilter", () => {
  it("passes a stream without ads through byte for byte", () => {
    const { input } = stream({ guard: true });
    const r = filterAll(input);
    expect(r.out).toBe(input);
    expect(r.stats).toMatchObject({ documents: 5, kept: 5, dropped: 0, replaced: 0, guard: true });
  });

  it("removes an ad edge from the first document and keeps the guard", () => {
    const { input, lines } = stream({ guard: true, adFirst: true });
    const out = filterAll(input).out.split("\n");
    expect(out[0]!.startsWith("for (;;);")).toBe(true);
    const first = JSON.parse(out[0]!.slice("for (;;);".length));
    expect(first.data.viewer.news_feed.edges.map((e: { node: { id: string } }) => e.node.id)).toEqual(["2", "3"]);
    expect(out.slice(1, 5)).toEqual(lines.slice(1));
  });

  it("drops a later ad document and leaves the others byte-identical", () => {
    const { input, lines } = stream({ adMiddle: true });
    const r = filterAll(input);
    expect(r.out).toBe([lines[0], lines[1], lines[3], lines[4]].join("\n") + "\n");
    expect(r.stats.dropped).toBe(1);
  });

  it("replaces a dropped final document with a minimal final document", () => {
    const { input, lines } = stream({ adLast: true });
    const out = filterAll(input).out.split("\n");
    expect(out.slice(0, 4)).toEqual(lines.slice(0, 4));
    expect(JSON.parse(out[4]!)).toEqual({ extensions: { is_final: true } });
    expect(out[5]).toBe("");
  });

  it("handles ads at first, middle and last position together", () => {
    const { input, lines } = stream({ guard: true, adFirst: true, adMiddle: true, adLast: true });
    const r = filterAll(input);
    const out = r.out.split("\n");
    expect(out).toHaveLength(5);
    expect(out[1]).toBe(lines[1]);
    expect(out[2]).toBe(lines[3]);
    expect(JSON.parse(out[3]!)).toEqual({ extensions: { is_final: true } });
    expect(r.stats).toMatchObject({ documents: 5, kept: 2, dropped: 1, replaced: 2 });
  });

  it("gives the same output for a cut at every byte offset", () => {
    const { input } = stream({ guard: true, adFirst: true, adMiddle: true, adLast: true, text: "é 😀 مرحبا" });
    const bytes = enc.encode(input);
    const expected = filterAll(bytes).out;
    for (let cut = 1; cut < bytes.length; cut++) {
      expect(filterAll(bytes, [cut]).out, `cut at ${cut}`).toBe(expected);
    }
  });

  it("gives the same output for many random cuts, including inside multi-byte characters", () => {
    const { input } = stream({ guard: true, adMiddle: true, text: "😀😀😀 ééé مرحبا 中文" });
    const bytes = enc.encode(input);
    const expected = filterAll(bytes).out;
    for (let seed = 1; seed <= 50; seed++) {
      expect(filterAll(bytes, randomCuts(bytes.length, 40, seed)).out).toBe(expected);
    }
    // One byte per chunk.
    expect(filterAll(bytes, Array.from({ length: bytes.length - 1 }, (_, i) => i + 1)).out).toBe(expected);
  });

  it("cuts inside a 4-byte character keep the character intact", () => {
    const line = JSON.stringify({ data: edge("9", false, "😀") }) + "\n";
    const bytes = enc.encode(line);
    const emoji = bytes.indexOf(0xf0);
    for (const cut of [emoji + 1, emoji + 2, emoji + 3]) {
      expect(filterAll(bytes, [cut]).out).toBe(line);
    }
  });

  it("emits a complete line as soon as it is complete (prompt output)", () => {
    const { lines } = stream({});
    const f = new NdjsonStreamFilter(mockAdRule);
    expect(dec.decode(f.push(enc.encode(lines[0]!.slice(0, 10))))).toBe("");
    expect(dec.decode(f.push(enc.encode(lines[0]!.slice(10) + "\n" + lines[1]!.slice(0, 5))))).toBe(lines[0] + "\n");
    expect(dec.decode(f.push(enc.encode(lines[1]!.slice(5) + "\n")))).toBe(lines[1] + "\n");
    expect(dec.decode(f.end())).toBe("");
  });

  it("does not hold the whole response: output keeps pace with input", () => {
    const line = JSON.stringify({ data: edge("x") }) + "\n";
    const f = new NdjsonStreamFilter(mockAdRule);
    for (let i = 0; i < 1000; i++) {
      expect(f.push(enc.encode(line)).length).toBe(line.length);
    }
  });

  it("flushes a last line without trailing newline at end", () => {
    const { lines } = stream({ adLast: true });
    const input = lines.join("\n");
    const out = filterAll(input).out.split("\n");
    expect(JSON.parse(out[4]!)).toEqual({ extensions: { is_final: true } });
    expect(out).toHaveLength(5);
  });

  it("preserves CRLF line endings, also on replaced lines", () => {
    const { lines } = stream({ adFirst: true });
    const input = lines.join("\r\n") + "\r\n";
    const out = filterAll(input).out;
    expect(out.split("\r\n")).toHaveLength(6);
    expect(out.endsWith(lines[4] + "\r\n")).toBe(true);
  });

  it("detects the guard without a space and after whitespace, only before the first document", () => {
    const { lines } = stream({ adFirst: true });
    for (const guard of ["for(;;);", "  for (;;);", "for (;;); "]) {
      const r = filterAll(guard + lines.join("\n"));
      expect(r.out.startsWith(guard)).toBe(true);
      expect(r.stats.guard).toBe(true);
      expect(r.errors).toEqual([]);
    }
    // A guard on a later line is not a guard: that line fails open.
    const clean = stream({}).lines;
    const r = filterAll(clean[0] + "\nfor (;;);" + clean[1]);
    expect(r.out).toBe(clean[0] + "\nfor (;;);" + clean[1]);
    expect(r.errors.map((e) => e.kind)).toEqual(["parse"]);
  });

  it("keeps the guard when the first document is dropped", () => {
    const rule: DocumentRule = (_d, i) => (i === 0 ? DROP : KEEP);
    const r = filterAll('for (;;);{"a":1}\n{"b":2}\n', [], rule);
    expect(r.out).toBe('for (;;);{"b":2}\n');
  });

  it("keeps a byte order mark on a replaced first document", () => {
    const { lines } = stream({ adFirst: true });
    const r = filterAll("\uFEFF" + lines.join("\n"));
    expect(r.out.charCodeAt(0)).toBe(0xfeff);
    expect(r.errors).toEqual([]);
  });

  it("fails open on a malformed line and still filters the rest", () => {
    const { lines } = stream({ adFirst: true, adMiddle: true, adLast: true });
    const malformed = '{"data": {"broken": ';
    const input = [lines[0], lines[1], malformed, lines[2], lines[3], lines[4]].join("\n") + "\n";
    const r = filterAll(input);
    const out = r.out.split("\n");
    expect(out[2]).toBe(malformed);
    expect(out[1]).toBe(lines[1]);
    expect(out).not.toContain(lines[2]); // the middle ad, after the malformed line, is still dropped
    expect(JSON.parse(out[4]!)).toEqual({ extensions: { is_final: true } });
    expect(r.errors).toHaveLength(1);
    expect(r.errors[0]).toMatchObject({ kind: "parse", index: 2 });
    expect(r.errors[0]!.sample).toBe(malformed);
    expect(r.stats.failedOpen).toBe(1);
  });

  it("fails open on invalid UTF-8", () => {
    const bad = new Uint8Array([0x7b, 0x22, 0x61, 0x22, 0x3a, 0x22, 0xff, 0x22, 0x7d, 0x0a]);
    const r = filterAll(bad);
    expect(r.out).toBe(dec.decode(bad));
    expect(r.errors[0]!.kind).toBe("utf8");
  });

  it("fails open when the rule throws", () => {
    const r = filterAll('{"a":1}\n{"b":2}\n', [], (_d, i) => {
      if (i === 1) throw new Error("boom");
      return KEEP;
    });
    expect(r.out).toBe('{"a":1}\n{"b":2}\n');
    expect(r.errors[0]).toMatchObject({ kind: "rule", index: 1, message: "boom" });
  });

  it("forwards blank lines unchanged and does not count them as documents", () => {
    const r = filterAll('{"a":1}\n\n  \n{"b":2}\n');
    expect(r.out).toBe('{"a":1}\n\n  \n{"b":2}\n');
    expect(r.stats.documents).toBe(2);
  });

  it("does not re-serialize kept lines (odd but valid JSON formatting survives)", () => {
    const odd = '{ "data" :{"x":1.50,  "y":"\\u00e9"} }\n';
    expect(filterAll(odd).out).toBe(odd);
  });

  it("an error listener that throws does not break the stream", () => {
    const f = new NdjsonStreamFilter(mockAdRule, {
      onError: () => {
        throw new Error("listener");
      },
    });
    expect(joined(feed(f, enc.encode('x\n{"a":1}\n'), []))).toBe('x\n{"a":1}\n');
  });

  it("counts bytes in and out", () => {
    const { input } = stream({ adMiddle: true });
    const r = filterAll(input, [17, 100]);
    expect(r.stats.bytesIn).toBe(enc.encode(input).length);
    expect(r.stats.bytesOut).toBe(enc.encode(r.out).length);
  });

  it("filters a 5 MB stream", () => {
    const parts: string[] = [JSON.stringify({ data: { viewer: { news_feed: { edges: [edge("0", true), edge("1")] } } } })];
    const pad = "x".repeat(900);
    let i = 2;
    let size = parts[0]!.length;
    while (size < 5 * 1024 * 1024) {
      const line = JSON.stringify({ data: edge(String(i), i % 50 === 0, pad), path: ["viewer", "news_feed", "edges", i], extensions: { is_final: false } });
      parts.push(line);
      size += line.length + 1;
      i++;
    }
    const bytes = enc.encode(parts.join("\n") + "\n");
    const cuts = Array.from({ length: Math.floor(bytes.length / 16384) }, (_, k) => (k + 1) * 16384);
    const t0 = performance.now();
    const r = filterAll(bytes, cuts);
    const ms = performance.now() - t0;
    const expectedDrops = parts.slice(1).filter((_, k) => (k + 2) % 50 === 0).length;
    expect(r.stats.dropped).toBe(expectedDrops);
    expect(r.stats.replaced).toBe(1);
    expect(ms).toBeLessThan(5000);
  });
});

describe("NdjsonStreamFilter internal failure (M1 line-loss defect)", () => {
  // A decision whose `action` getter throws escapes every handler inside processLine: it is
  // read in the switch, outside the try blocks. That models any unexpected internal error.
  const throwingDecision = { get action(): never { throw new Error("unexpected internal failure"); } } as unknown as ReturnType<DocumentRule>;

  for (const failAt of [0, 2, 4]) {
    it(`loses no byte when processing line ${failAt} throws, at every chunk boundary`, () => {
      const { input } = stream({ guard: true, adFirst: true });
      const bytes = enc.encode(input);
      const rule: DocumentRule = (doc, index) => (index === failAt ? throwingDecision : KEEP);
      for (let cut = 1; cut < bytes.length; cut++) {
        const errors: FilterError[] = [];
        const f = new NdjsonStreamFilter(rule, { onError: (e) => errors.push(e) });
        const out = joined(feed(f, bytes, [cut]));
        expect(out, `cut at ${cut}`).toBe(input);
        expect(f.stats.passThrough).toBe(true);
        expect(errors.map((e) => e.kind)).toEqual(["internal"]);
      }
    });
  }

  it("loses no byte when the throwing line spans several chunks", () => {
    const { input } = stream({ guard: true });
    const bytes = enc.encode(input);
    const rule: DocumentRule = (_doc, index) => (index === 1 ? throwingDecision : KEEP);
    const f = new NdjsonStreamFilter(rule);
    expect(joined(feed(f, bytes, randomCuts(bytes.length, 40, 7)))).toBe(input);
  });
});
