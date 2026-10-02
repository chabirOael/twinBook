// Home feed edges from the recordings: the feed pagination query's NDJSON responses and the
// first page that the desktop document carries as prefetched Relay results. Classification
// with rules v1, story shapes, and the field locators of the M2b gate.

import { evaluateEdge, valuesAt, type AdRules, type EdgeVerdict } from "../../src/lib/adRules";
import { getPath, isObj, safeKey, walk, type Json, type Obj, type Rec, type Session } from "./session";

export const FEED_QUERY = "CometNewsFeedPaginationQuery";
export const FEED_PRELOAD = "CometModernHomeFeedQuery";

export interface FeedEdge {
  session: string;
  source: "graphql" | "document";
  rid: string;
  /** Line of the response (graphql) or order in the document. */
  line: number;
  /** Index in the feed connection (path index, or position in the first edges array). */
  index: number;
  edge: Obj;
}

/** RelayPrefetchedStreamCache "next" payloads of a document: [preloader id, result]. */
export function prefetchedResults(s: Session, r: Rec): { preloader: string; complete: boolean; result: Obj }[] {
  const out: { preloader: string; complete: boolean; result: Obj }[] = [];
  for (const island of s.islands(r)) {
    walk(island.json, (v) => {
      if (!Array.isArray(v) || v[0] !== "RelayPrefetchedStreamCache" || v[1] !== "next") return;
      const args = v[3];
      if (!Array.isArray(args) || typeof args[0] !== "string" || !isObj(args[1])) return;
      const bbox = args[1]["__bbox"];
      if (!isObj(bbox) || !isObj(bbox["result"])) return;
      out.push({ preloader: args[0], complete: bbox["complete"] === true, result: bbox["result"] });
    });
  }
  return out;
}

export function preloaderName(id: string): string {
  return id.replace(/^adp_/, "").replace(/RelayPreloader.*$/, "");
}

export function feedEdges(sessions: Session[]): FeedEdge[] {
  const out: FeedEdge[] = [];
  for (const s of sessions) {
    for (const r of s.select((i) => i.own && i.type === "main_frame")) {
      let n = 0;
      for (const p of prefetchedResults(s, r)) {
        if (preloaderName(p.preloader) !== FEED_PRELOAD) continue;
        const path = p.result["path"];
        const data = p.result["data"];
        if (Array.isArray(path) && path.length === 4 && path[2] === "edges" && isObj(data)) {
          out.push({ session: s.sourceId, source: "document", rid: r.rid, line: n++, index: path[3] as number, edge: data });
        } else if (!Array.isArray(path) || path.length === 0) {
          const edges = getPath(data, ["viewer", "news_feed", "edges"]);
          if (Array.isArray(edges)) edges.forEach((e, i) => isObj(e) && out.push({ session: s.sourceId, source: "document", rid: r.rid, line: n++, index: i, edge: e }));
        }
      }
    }
    for (const r of s.graphql()) {
      if (s.field(r, "fb_api_req_friendly_name") !== FEED_QUERY) continue;
      s.ndjson(r).docs.forEach((doc, line) => {
        if (!isObj(doc)) return;
        const path = doc["path"];
        if (Array.isArray(path)) {
          if (path.length === 4 && path[2] === "edges" && isObj(doc["data"])) out.push({ session: s.sourceId, source: "graphql", rid: r.rid, line, index: path[3] as number, edge: doc["data"] });
          return;
        }
        const edges = getPath(doc, ["data", "viewer", "news_feed", "edges"]);
        if (Array.isArray(edges)) edges.forEach((e, i) => isObj(e) && out.push({ session: s.sourceId, source: "graphql", rid: r.rid, line, index: i, edge: e }));
      });
    }
  }
  return out;
}

export type EdgeClass = "sponsored" | "suggested" | "organic";

export function classify(rules: AdRules, edge: Obj): { cls: EdgeClass; verdict: EdgeVerdict } {
  const verdict = evaluateEdge(rules, edge);
  return { cls: verdict.isAd ? "sponsored" : verdict.suggestions.length > 0 ? "suggested" : "organic", verdict };
}

function typenameAt(root: Json, path: string[]): string {
  const vs = valuesAt(root, path).filter((v) => typeof v === "string");
  return vs.length === 0 ? "-" : [...new Set(vs as string[])].sort().join("+");
}

/** Shape of a story: the type names that decide how it renders. */
export function shapeOf(edge: Obj): string {
  const n = edge["node"];
  const parts = [
    typenameAt(n, ["__typename"]),
    `content=${typenameAt(n, ["comet_sections", "content", "__typename"])}`,
    `title=${typenameAt(n, ["comet_sections", "context_layout", "story", "comet_sections", "title", "__typename"])}`,
    `message=${typenameAt(n, ["comet_sections", "content", "story", "comet_sections", "message", "__typename"])}`,
    `attachment=${typenameAt(n, ["attachments", "*", "styles", "__typename"])}`,
    `attached_story=${isObj(getPath(n, ["attached_story"])) ? "yes" : "no"}`,
  ];
  return parts.join(" ");
}

/** Where each field the gate asks for is read from; the first path that has a value wins. */
export const LOCATORS: Record<string, { paths: string[][]; optional: boolean; why: string }> = {
  "author name": {
    paths: [["node", "actors", "0", "name"]],
    optional: false,
    why: "first actor",
  },
  "author picture": {
    paths: [["node", "comet_sections", "context_layout", "story", "comet_sections", "actor_photo", "story", "actors", "0", "profile_picture", "uri"]],
    optional: false,
    why: "actor photo section",
  },
  time: {
    paths: [
      ["node", "comet_sections", "timestamp", "story", "creation_time"],
      ["node", "comet_sections", "context_layout", "story", "comet_sections", "metadata", "*", "story", "creation_time"],
      ["node", "creation_time"],
    ],
    optional: false,
    why: "timestamp section, else header metadata",
  },
  text: {
    paths: [
      ["node", "comet_sections", "content", "story", "comet_sections", "message", "story", "message", "text"],
      ["node", "comet_sections", "content", "story", "message", "text"],
    ],
    optional: true,
    why: "message section; absent on stories without text",
  },
  "primary attachment": {
    paths: [["node", "attachments", "0", "styles", "attachment"]],
    optional: true,
    why: "first attachment's style payload; absent on text-only stories",
  },
  "reaction count": {
    paths: [["node", "comet_sections", "feedback", "story", "story_ufi_container", "story", "feedback_context", "feedback_target_with_context", "comet_ufi_summary_and_actions_renderer", "feedback", "adaptive_ufi_action_renderers", "*", "feedback", "reaction_count", "count"]],
    optional: false,
    why: "UFI action bar renderers (the reaction button's feedback)",
  },
  "comment count": {
    paths: [
      ["node", "comet_sections", "feedback", "story", "story_ufi_container", "story", "feedback_context", "feedback_target_with_context", "comment_rendering_instance", "comments", "total_count"],
    ],
    optional: false,
    why: "comment rendering instance",
  },
};

export function locate(edge: Obj, field: string): { found: boolean; pathIndex: number } {
  const loc = LOCATORS[field]!;
  for (let i = 0; i < loc.paths.length; i++) {
    const vs = valuesAt(edge, loc.paths[i]!).filter((v) => v !== null && v !== undefined && v !== "");
    if (vs.length > 0) return { found: true, pathIndex: i };
  }
  return { found: false, pathIndex: -1 };
}

/** Key path patterns (array indices as []) under which `key` occurs in the edges, with counts. */
export function keyPaths(edges: Obj[], key: string): Map<string, number> {
  const out = new Map<string, number>();
  for (const e of edges) {
    const seen = new Set<string>();
    walk(e, (v, path, k) => {
      if (k !== key) return;
      const p = path.map((x) => (typeof x === "number" ? "[]" : safeKey(x))).join(".");
      if (seen.has(p)) return;
      seen.add(p);
      out.set(p, (out.get(p) ?? 0) + 1);
    });
  }
  return out;
}
