import { describe, expect, it } from "vitest";
import { HtmlJsonIslandFilter } from "../src/lib/htmlIslandFilter";
import type { FilterError } from "../src/lib/ndjsonFilter";
import { mockAdIslandTransform } from "../src/lib/mockAdRule";
import { enc, feed, joined, randomCuts } from "./helpers";

const island = (edges: unknown[]) => JSON.stringify({ require: [["X"]], data: { viewer: { news_feed: { edges } } } });
const ad = { node: { id: "a", mock_sponsored: true, text: "é <b>x</b>" } };
const post = { node: { id: "p", text: "😀 مرحبا" } };

function page(json: string, type = 'type="application/json"'): string {
  return `<!DOCTYPE html><html><head><title>t</title><script>var x = "<script>";</script></head><body><p>é😀</p><script ${type} data-sjs>${json}</script><script type="text/javascript">1</script></body></html>`;
}

function run(input: string | Uint8Array, cuts: number[] = []) {
  const errors: FilterError[] = [];
  const f = new HtmlJsonIslandFilter(mockAdIslandTransform, { onError: (e) => errors.push(e) });
  const bytes = typeof input === "string" ? enc.encode(input) : input;
  return { out: joined(feed(f, bytes, cuts)), errors, stats: f.stats };
}

describe("HtmlJsonIslandFilter", () => {
  it("removes ad edges from a JSON island and leaves the rest byte-identical", () => {
    const html = page(island([post, ad]));
    const r = run(html);
    const expectedIsland = JSON.stringify(JSON.parse(island([post]))).replace(/</g, "\\u003c");
    expect(r.out).toBe(page(expectedIsland));
    expect(r.stats).toMatchObject({ islands: 1, changed: 1, failedOpen: 0 });
  });

  it("leaves an island without ads untouched, byte for byte", () => {
    const json = ' { "data" : { "edges" : [ {"node":{"id":1}} ] } } ';
    const html = page(json);
    expect(run(html).out).toBe(html);
  });

  it("ignores scripts that are not JSON islands", () => {
    const html = page(island([ad]), 'type="text/javascript"');
    expect(run(html).out).toBe(html);
  });

  it("accepts single quotes, no quotes and upper case", () => {
    for (const type of ["type='application/json'", "TYPE=application/json", 'type = "application/json"']) {
      const html = page(island([post, ad]), type);
      expect(run(html).stats.changed, type).toBe(1);
    }
    expect(run(`<SCRIPT type="application/json">${island([ad])}</SCRIPT>`).stats.changed).toBe(1);
  });

  it("gives the same output for a cut at every byte offset", () => {
    const html = page(island([post, ad, post]));
    const bytes = enc.encode(html);
    const expected = run(bytes).out;
    for (let cut = 1; cut < bytes.length; cut++) {
      expect(run(bytes, [cut]).out, `cut at ${cut}`).toBe(expected);
    }
    for (let seed = 1; seed <= 20; seed++) {
      expect(run(bytes, randomCuts(bytes.length, 30, seed)).out).toBe(expected);
    }
  });

  it("streams the part before an island without waiting for the end", () => {
    const f = new HtmlJsonIslandFilter(mockAdIslandTransform);
    const head = "<html><body>" + "x".repeat(1000);
    expect(f.push(enc.encode(head)).length).toBeGreaterThan(990);
  });

  it("fails open on a malformed island", () => {
    const html = page('{"data": [1, 2');
    const r = run(html);
    expect(r.out).toBe(html);
    expect(r.errors[0]).toMatchObject({ kind: "parse", index: 0 });
  });

  it("passes an unterminated island through at the end", () => {
    const html = `<p>a</p><script type="application/json">${island([ad])}`;
    expect(run(html).out).toBe(html);
  });

  it("handles several islands", () => {
    const html = `<script type="application/json">${island([ad])}</script><i>x</i><script type="application/json">${island([post])}</script>`;
    const r = run(html);
    expect(r.stats).toMatchObject({ islands: 2, changed: 1 });
    expect(r.out.endsWith(`<script type="application/json">${island([post])}</script>`)).toBe(true);
  });
});
