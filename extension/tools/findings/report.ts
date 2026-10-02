// The numbers of docs/findings/payloads.md, regenerated from pulled sessions with one command:
//   tools/capture-tools.sh findings captures/<id>-rescrub ...
// Prints names, counts, sizes, type names and enum-like constants only, never a recorded value.

import { readFileSync } from "node:fs";
import { createAdRule, type AdRules } from "../../src/lib/adRules";
import { NdjsonStreamFilter } from "../../src/lib/ndjsonFilter";
import { classify, feedEdges, keyPaths, locate, LOCATORS, prefetchedResults, shapeOf, type EdgeClass, type FeedEdge } from "./feed";
import { getPath, safeKey, Session, type Obj } from "./session";
import * as sections from "./sections";

export function loadRules(file: string): AdRules {
  return JSON.parse(readFileSync(file, "utf8")) as AdRules;
}

export class Out {
  readonly lines: string[] = [];
  h(title: string): void {
    this.lines.push("", `## ${title}`);
  }
  p(...parts: (string | number)[]): void {
    this.lines.push(parts.join(" "));
  }
  table(rows: (string | number)[][], indent = "  "): void {
    if (rows.length === 0) {
      this.lines.push(`${indent}(none)`);
      return;
    }
    const widths = rows[0]!.map((_, i) => Math.max(...rows.map((r) => String(r[i] ?? "").length)));
    for (const r of rows) this.lines.push(indent + r.map((c, i) => (typeof c === "number" ? String(c).padStart(widths[i]!) : String(c).padEnd(widths[i]!))).join("  ").trimEnd());
  }
  map(m: Map<string, number>, limit = 60, indent = "  "): void {
    const rows = [...m].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, limit);
    this.table(rows.map(([k, v]) => [v, k]), indent);
    if (m.size > limit) this.lines.push(`${indent}... ${m.size - limit} more`);
  }
  toString(): string {
    return this.lines.join("\n");
  }
}

export interface FeedAnalysis {
  edges: FeedEdge[];
  classes: Map<FeedEdge, { cls: EdgeClass; signals: string[]; families: string[]; suggestions: string[] }>;
}

export function analyseFeed(sessions: Session[], rules: AdRules): FeedAnalysis {
  const edges = feedEdges(sessions);
  const classes = new Map<FeedEdge, { cls: EdgeClass; signals: string[]; families: string[]; suggestions: string[] }>();
  for (const e of edges) {
    const { cls, verdict } = classify(rules, e.edge);
    classes.set(e, { cls, signals: verdict.signals, families: verdict.families, suggestions: verdict.suggestions });
  }
  return { edges, classes };
}

export function feedSections(out: Out, sessions: Session[], rules: AdRules): FeedAnalysis {
  const fa = analyseFeed(sessions, rules);
  const { edges, classes } = fa;
  const byClass = (c: EdgeClass): FeedEdge[] => edges.filter((e) => classes.get(e)!.cls === c);

  out.h("5. Ads and suggestions");
  const src = new Map<string, number>();
  for (const e of edges) src.set(`${e.session} ${e.source}`, (src.get(`${e.session} ${e.source}`) ?? 0) + 1);
  out.p(`feed edges: ${edges.length}`);
  out.map(src);
  out.p(`classes (rules v${rules.version}, at least ${rules.minFamilies} signal families for an ad):`, ["sponsored", "suggested", "organic"].map((c) => `${c} ${byClass(c as EdgeClass).length}`).join(", "));
  out.p("signal occurrence by class (edges matching / edges in class):");
  const classesList: EdgeClass[] = ["sponsored", "suggested", "organic"];
  const rows: (string | number)[][] = [["signal", "family", ...classesList]];
  for (const s of [...rules.signals, ...(rules.suggestionSignals ?? [])]) {
    rows.push([
      s.id,
      s.family,
      ...classesList.map((c) => {
        const list = byClass(c);
        const hit = list.filter((e) => {
          const v = classes.get(e)!;
          return v.signals.includes(s.id) || v.suggestions.includes(s.id);
        }).length;
        return `${hit}/${list.length}`;
      }),
    ]);
  }
  out.table(rows);
  // Individual tests (each alternative of each signal), to show decoys and redundancy.
  out.p("individual tests by class:");
  const testRows: (string | number)[][] = [["signal", "test", ...classesList]];
  for (const s of rules.signals) {
    s.any.forEach((t) => {
      const label = t.anywhereKey !== undefined ? `anywhere ${t.anywhereKey}${t.equals !== undefined ? `=${String(t.equals)}` : ""}${t.notNull === true ? " not null" : ""}` : `${(t.path ?? []).join(".")}${t.equals !== undefined ? `=${String(t.equals)}` : ""}${t.type !== undefined ? ` (${t.type})` : ""}`;
      testRows.push([
        s.id,
        label,
        ...classesList.map((c) => {
          const list = byClass(c);
          return `${list.filter((e) => matchesOne(e.edge, s.any.indexOf(t), s)).length}/${list.length}`;
        }),
      ]);
    });
  }
  out.table(testRows);
  // Decoy check: ad-shaped keys present on non-sponsored edges, by value state.
  out.p("ad-shaped keys on non-sponsored edges (key: null / false / empty / other value):");
  const decoy = new Map<string, number[]>();
  for (const e of edges) {
    if (classes.get(e)!.cls === "sponsored") continue;
    walkKeys(e.edge, (k, v) => {
      if (!/spons|^ad_|_ad_|_ads?$|^ads?_|^is_ad|advert|th_dat|client_token|promot/i.test(k) || safeKey(k) !== k) return;
      const c = decoy.get(k) ?? [0, 0, 0, 0];
      if (v === null) c[0]!++;
      else if (v === false) c[1]!++;
      else if ((Array.isArray(v) && v.length === 0) || (typeof v === "object" && v !== null && Object.keys(v).length === 0)) c[2]!++;
      else c[3]!++;
      decoy.set(k, c);
    });
  }
  out.table([...decoy].sort().map(([k, c]) => [k, ...c]));

  out.p("rules v1 over every recorded feed response (enforce mode, NDJSON filter):");
  for (const s of sessions) {
    for (const r of s.graphql()) {
      if (s.field(r, "fb_api_req_friendly_name") !== "CometNewsFeedPaginationQuery") continue;
      const text = s.bodyText(r);
      if (text === undefined) continue;
      const rule = createAdRule(rules);
      const filter = new NdjsonStreamFilter(rule);
      const enc = new TextEncoder();
      filter.push(enc.encode(text));
      filter.end();
      out.p(`  ${s.sourceId} rid ${r.rid}: documents ${filter.stats.documents}, kept ${filter.stats.kept}, dropped ${filter.stats.dropped}, replaced ${filter.stats.replaced}, failed open ${filter.stats.failedOpen}; ads removed ${rule.report.removed.length} by [${rule.report.removed.map((x) => x.families.join("+")).join("; ")}], final markers ${rule.report.finalMarkers}`);
    }
  }
  out.p("document preload (first page):", edges.filter((e) => e.source === "document").map((e) => classes.get(e)!.cls).join(", "));

  out.h("Feed pagination chain (equality of cursors only; no value is printed)");
  for (const s of sessions) {
    const pages: { id: string; cursor: string | undefined; end: string | undefined; edgeCursors: string[]; count: unknown }[] = [];
    for (const r of s.select((i) => i.own && i.type === "main_frame")) {
      for (const p of prefetchedResults(s, r)) {
        const end = getPath(p.result, ["data", "page_info", "end_cursor"]);
        if (p.preloader.includes("CometModernHomeFeedQuery") && typeof end === "string") pages.push({ id: "document", cursor: undefined, end, edgeCursors: [], count: undefined });
      }
    }
    const reqs = s.graphql().filter((r) => s.field(r, "fb_api_req_friendly_name") === "CometNewsFeedPaginationQuery" && r.request !== undefined);
    reqs.sort((a, b) => Number(a.request!.t) - Number(b.request!.t));
    for (const r of reqs) {
      let vars: Obj = {};
      try {
        vars = JSON.parse(s.field(r, "variables") ?? "{}") as Obj;
      } catch {
        vars = {};
      }
      let end: string | undefined;
      const edgeCursors: string[] = [];
      for (const d of s.ndjson(r).docs) {
        const pe = getPath(d, ["data", "page_info", "end_cursor"]);
        if (typeof pe === "string") end = pe;
        const c = getPath(d, ["data", "cursor"]);
        if (typeof c === "string") edgeCursors.push(c);
        const edges = getPath(d, ["data", "viewer", "news_feed", "edges"]);
        if (Array.isArray(edges)) for (const e of edges) if (typeof getPath(e, ["cursor"]) === "string") edgeCursors.push(getPath(e, ["cursor"]) as string);
      }
      pages.push({ id: `rid ${r.rid}`, cursor: typeof vars["cursor"] === "string" ? (vars["cursor"] as string) : undefined, end, edgeCursors, count: vars["count"] });
    }
    pages.forEach((p, i) => {
      const prev = i > 0 ? pages[i - 1] : undefined;
      const rel = prev === undefined ? "first recorded page" : p.cursor !== undefined && p.cursor === prev.end ? "cursor = previous end_cursor" : p.cursor !== undefined && prev.edgeCursors.includes(p.cursor) ? "cursor = an edge cursor of the previous page" : "cursor not from the previous recorded page";
      const last = p.edgeCursors.length > 0 ? String(p.end === p.edgeCursors[p.edgeCursors.length - 1]) : "-";
      out.p(`  ${s.sourceId} ${p.id}: count ${String(p.count ?? "-")}, ${rel}; end_cursor = last edge cursor: ${last}; edges ${p.edgeCursors.length}`);
    });
  }

  out.h("4. Feed story anatomy");
  const shapes = new Map<string, number>();
  for (const e of edges) shapes.set(shapeOf(e.edge), (shapes.get(shapeOf(e.edge)) ?? 0) + 1);
  out.p(`distinct story shapes: ${shapes.size} over ${edges.length} edges`);
  out.map(shapes, 80);
  out.p("node type names:");
  out.map(countBy(edges, (e) => String(e.edge["node"] && (e.edge["node"] as Obj)["__typename"])));
  out.p("renderer components (__module_component_*) by number of edges using them:");
  const mods = new Map<string, number>();
  const tns = new Map<string, number>();
  for (const e of edges) {
    const m = new Set<string>();
    const t = new Set<string>();
    walkKeys(e.edge, (k, v) => {
      if (k.startsWith("__module_component_")) m.add(safeKey(k).slice("__module_component_".length));
      if (k === "__typename" && typeof v === "string") t.add(v);
    });
    for (const x of m) mods.set(x, (mods.get(x) ?? 0) + 1);
    for (const x of t) tns.set(x, (tns.get(x) ?? 0) + 1);
  }
  out.map(mods, 80);
  out.p(`type names (__typename values) by number of edges using them: ${tns.size} distinct`);
  out.map(tns, 140);

  out.h("Gate: fields located per story");
  const stories = edges.filter((e) => (e.edge["node"] as Obj | undefined)?.["__typename"] === "Story");
  out.p(`stories: ${stories.length} of ${edges.length} edges (other units: ${edges.length - stories.length})`);
  const gateRows: (string | number)[][] = [["field", "located", "share", "by path", "sponsored", "suggested", "organic", "optional", "shapes where it holds for all"]];
  for (const [field, loc] of Object.entries(LOCATORS)) {
    const res = stories.map((e) => locate(e.edge, field));
    const found = res.filter((x) => x.found).length;
    const byPath = loc.paths.map((_, i) => res.filter((x) => x.pathIndex === i).length).join("/");
    const perClass = (["sponsored", "suggested", "organic"] as EdgeClass[]).map((c) => {
      const idx = stories.map((e, i) => (classes.get(e)!.cls === c ? i : -1)).filter((i) => i >= 0);
      return `${idx.filter((i) => res[i]!.found).length}/${idx.length}`;
    });
    const shapeOk = new Map<string, boolean>();
    stories.forEach((e, i) => {
      const sh = shapeOf(e.edge);
      shapeOk.set(sh, (shapeOk.get(sh) ?? true) && res[i]!.found);
    });
    const allShapes = [...shapeOk.values()].filter(Boolean).length;
    gateRows.push([field, `${found}/${stories.length}`, `${Math.round((100 * found) / Math.max(1, stories.length))}%`, byPath, ...perClass, loc.optional ? "yes" : "no", `${allShapes}/${shapeOk.size}`]);
  }
  out.table(gateRows);
  return fa;
}

function matchesOne(edge: Obj, index: number, s: AdRules["signals"][number]): boolean {
  const single = { ...s, any: [s.any[index]!] };
  return classifyOne(edge, single);
}

function classifyOne(edge: Obj, s: AdRules["signals"][number]): boolean {
  return classify({ version: 0, minFamilies: 1, edgeArrays: [], streamLabels: [], signals: [s] }, edge).verdict.signals.length > 0;
}

function walkKeys(root: unknown, visit: (k: string, v: unknown) => void): void {
  const stack: unknown[] = [root];
  while (stack.length > 0) {
    const v = stack.pop();
    if (Array.isArray(v)) stack.push(...v);
    else if (typeof v === "object" && v !== null) {
      for (const [k, x] of Object.entries(v)) {
        visit(k, x);
        if (typeof x === "object" && x !== null) stack.push(x);
      }
    }
  }
}

export function countBy<T>(xs: T[], key: (x: T) => string): Map<string, number> {
  const m = new Map<string, number>();
  for (const x of xs) m.set(key(x), (m.get(key(x)) ?? 0) + 1);
  return m;
}

export function findingsReport(dirs: string[], rulesFile: string): string {
  const sessions = dirs.map((d) => new Session(d));
  const rules = loadRules(rulesFile);
  const out = new Out();
  out.p(`# M2b findings numbers (tools/capture-tools.sh findings), rules ${rulesFile} v${rules.version}`);
  out.p(`sessions: ${sessions.map((s) => s.id).join(", ")}`);
  sections.overview(out, sessions);
  sections.transport(out, sessions);
  sections.requestAnatomy(out, sessions);
  sections.queryCatalogue(out, sessions);
  feedSections(out, sessions, rules);
  sections.video(out, sessions);
  sections.otherScreens(out, sessions);
  sections.navigation(out, sessions);
  sections.tokensAndCookies(out, sessions);
  sections.pageDocuments(out, sessions);
  sections.mobile(out, sessions);
  sections.telemetry(out, sessions);
  sections.numbers(out, sessions);
  out.p("");
  out.p(`bare layer 2 placeholders repaired while parsing: ${sessions.map((s) => `${s.sourceId} ${s.repairedPlaceholders}`).join(", ")}`);
  return out.toString();
}

/** Exploration aid: key path patterns of a key over every feed edge. */
export function keyPathsReport(dirs: string[], rulesFile: string, key: string): string {
  const sessions = dirs.map((d) => new Session(d));
  const fa = analyseFeed(sessions, loadRules(rulesFile));
  const out = new Out();
  out.p(`key ${key} over ${fa.edges.length} feed edges`);
  out.map(keyPaths(fa.edges.map((e) => e.edge), key), 200);
  return out.toString();
}
