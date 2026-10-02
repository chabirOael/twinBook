import { describe, expect, it } from "vitest";
import { FINAL_REPLACEMENT, isMockAdEdge, mockAdIslandTransform, mockAdRule, removeMockAdEdges } from "../src/lib/mockAdRule";

describe("mock ad rule", () => {
  it("recognizes the mock marker only as node.mock_sponsored === true", () => {
    expect(isMockAdEdge({ node: { mock_sponsored: true } })).toBe(true);
    expect(isMockAdEdge({ node: { mock_sponsored: "true" } })).toBe(false);
    expect(isMockAdEdge({ mock_sponsored: true })).toBe(false);
    expect(isMockAdEdge(null)).toBe(false);
  });

  it("removes ad edges from nested edges arrays", () => {
    const v = { a: { edges: [{ node: { id: 1 } }, { node: { id: 2, mock_sponsored: true } }] }, b: [{ c: { edges: [{ node: { mock_sponsored: true } }] } }] };
    expect(removeMockAdEdges(v)).toBe(2);
    expect(v).toEqual({ a: { edges: [{ node: { id: 1 } }] }, b: [{ c: { edges: [] } }] });
  });

  it("keeps documents without ads", () => {
    expect(mockAdRule({ data: { viewer: { news_feed: { edges: [{ node: { id: 1 } }] } } } }, 0)).toEqual({ action: "keep" });
    expect(mockAdRule([1, 2], 0)).toEqual({ action: "keep" });
    expect(mockAdRule("x", 0)).toEqual({ action: "keep" });
  });

  it("drops a non-final incremental ad document and replaces a final one", () => {
    const ad = { node: { id: 5, mock_sponsored: true } };
    expect(mockAdRule({ data: ad, path: ["viewer", "news_feed", "edges", 4], extensions: { is_final: false } }, 3)).toEqual({ action: "drop" });
    expect(mockAdRule({ data: ad, path: ["viewer", "news_feed", "edges", 4], extensions: { is_final: true } }, 3)).toEqual({
      action: "replace",
      value: FINAL_REPLACEMENT,
    });
  });

  it("island transform reports whether anything changed", () => {
    expect(mockAdIslandTransform({ edges: [{ node: { mock_sponsored: true } }] })).toBe(true);
    expect(mockAdIslandTransform({ edges: [{ node: {} }] })).toBe(false);
  });
});
