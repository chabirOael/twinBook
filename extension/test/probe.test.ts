import { describe, expect, it } from "vitest";
import { CANDIDATE_KEYS, Probe, probeDocument } from "../src/lib/probe";
import { NdjsonStreamFilter } from "../src/lib/ndjsonFilter";
import { HtmlJsonIslandFilter } from "../src/lib/htmlIslandFilter";
import { enc, feed, joined } from "./helpers";

describe("probe", () => {
  it("starts with the candidate names from data/probe-keys.json", () => {
    expect(CANDIDATE_KEYS).toEqual(["sponsored_data", "ad_id", "is_sponsored", "client_token"]);
  });

  it("reports size, top-level keys and candidate counts at any depth", () => {
    const doc = { data: { feed: { edges: [{ node: { sponsored_data: { ad_id: "1" } } }, { node: { is_sponsored: false } }, { node: { ad_id: "2" } }] } }, extensions: {} };
    expect(probeDocument(doc, 3, 120)).toEqual({ index: 3, bytes: 120, topKeys: ["data", "extensions"], candidates: { sponsored_data: 1, ad_id: 2, is_sponsored: 1 } });
    expect(probeDocument([1, 2], 0, 5).topKeys).toEqual(["[array:2]"]);
  });

  it("never changes a stream or a document", () => {
    const input = 'for (;;);{"a":{"ad_id":1}}\n{"b":2}\nnot json\n';
    const probe = new Probe();
    const f = new NdjsonStreamFilter(probe.rule, { observe: true });
    expect(joined(feed(f, enc.encode(input), [5, 20]))).toBe(input);
    expect(probe.documents).toBe(2);
    expect(probe.candidateTotals).toEqual({ ad_id: 1 });
    expect(probe.reports[0]!.bytes).toBe('for (;;);{"a":{"ad_id":1}}'.length);

    const html = '<p>x</p><script type="application/json">{"is_sponsored":true}</script><script>var a=1</script>';
    const p2 = new Probe();
    const h = new HtmlJsonIslandFilter(p2.islandTransform, { observe: true });
    expect(joined(feed(h, enc.encode(html), [10, 50]))).toBe(html);
    expect(p2.candidateTotals).toEqual({ is_sponsored: 1 });
  });
});
