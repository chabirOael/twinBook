// Data-driven ad rules for the site's feed (M2b proposal "rules v1", for M4). The rules are
// data (rules/ads-v1.json); this file evaluates them and turns them into a DocumentRule for
// the NDJSON stream filter (ndjsonFilter.ts), the same interface as the M1 mock rule.
//
// A feed edge is an ad when signals from at least `minFamilies` different families match.
// Signals of one family describe the same fact (for example the sponsored-data object under
// two names), so a single decoy field can never be enough.
//
// Per response, the rule:
// - removes ad edges from an `edges` array named by `units.edgeArrays` (replace decision);
// - drops an incremental document whose `data` is an ad edge (path ends in `edges`, n); if
//   that document was the final one, it is replaced by {"extensions":{"is_final":true}};
// - drops incremental documents whose path runs through a removed edge (deferred parts of
//   the ad, such as its video ad breaks), with the same final-document rule.
// Everything else is kept byte for byte.

import { DROP, KEEP, type Decision, type DocumentRule } from "./ndjsonFilter";

export interface PathTest {
  /** Keys from the edge; "*" matches every element of an array or every value of an object. */
  readonly path?: readonly string[];
  /** Any object at any depth of the edge with this key (instead of a fixed path). */
  readonly anywhereKey?: string;
  readonly equals?: string | number | boolean;
  readonly notNull?: boolean;
  readonly type?: "number" | "string" | "object" | "boolean";
}

export interface SignalSpec {
  readonly id: string;
  readonly family: string;
  readonly why?: string;
  /** The signal matches if any test matches. */
  readonly any: readonly PathTest[];
}

export interface AdRules {
  readonly version: number;
  readonly minFamilies: number;
  /** Paths (from the document root) of arrays of feed edges, e.g. data.viewer.news_feed.edges. */
  readonly edgeArrays: readonly (readonly string[])[];
  /** Labels of incremental documents that carry one edge each (Relay @stream). */
  readonly streamLabels: readonly string[];
  readonly signals: readonly SignalSpec[];
  /** Signals reported for analysis only (suggested content); never used to remove. */
  readonly suggestionSignals?: readonly SignalSpec[];
}

export interface EdgeVerdict {
  readonly isAd: boolean;
  readonly signals: string[];
  readonly families: string[];
  readonly suggestions: string[];
}

type Json = unknown;

function isObject(v: Json): v is Record<string, Json> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function valueMatches(v: Json, t: PathTest): boolean {
  if (v === undefined) return false;
  if (t.notNull === true && v === null) return false;
  if (t.equals !== undefined && v !== t.equals) return false;
  if (t.type !== undefined) {
    const actual = v === null ? "null" : Array.isArray(v) ? "array" : typeof v;
    if (actual !== t.type) return false;
  }
  return true;
}

/** Values at `path` below `root`, with "*" expanded. */
export function valuesAt(root: Json, path: readonly string[]): Json[] {
  let current: Json[] = [root];
  for (const key of path) {
    const next: Json[] = [];
    for (const v of current) {
      if (key === "*") {
        if (Array.isArray(v)) next.push(...v);
        else if (isObject(v)) next.push(...Object.values(v));
      } else if (Array.isArray(v) && /^\d+$/.test(key)) {
        if (Number(key) < v.length) next.push(v[Number(key)]);
      } else if (isObject(v) && key in v) {
        next.push(v[key]);
      }
    }
    current = next;
    if (current.length === 0) break;
  }
  return current;
}

function anywhere(root: Json, key: string, t: PathTest): boolean {
  const stack: Json[] = [root];
  while (stack.length > 0) {
    const v = stack.pop();
    if (Array.isArray(v)) {
      for (const x of v) if (typeof x === "object" && x !== null) stack.push(x);
    } else if (isObject(v)) {
      for (const [k, x] of Object.entries(v)) {
        if (k === key && valueMatches(x, t)) return true;
        if (typeof x === "object" && x !== null) stack.push(x);
      }
    }
  }
  return false;
}

export function testMatches(edge: Json, t: PathTest): boolean {
  if (t.anywhereKey !== undefined) return anywhere(edge, t.anywhereKey, t);
  if (t.path === undefined) return false;
  return valuesAt(edge, t.path).some((v) => valueMatches(v, t));
}

export function matchingSignals(edge: Json, specs: readonly SignalSpec[]): SignalSpec[] {
  return specs.filter((s) => s.any.some((t) => testMatches(edge, t)));
}

export function evaluateEdge(rules: AdRules, edge: Json): EdgeVerdict {
  const hits = matchingSignals(edge, rules.signals);
  const families = [...new Set(hits.map((s) => s.family))].sort();
  return {
    isAd: families.length >= rules.minFamilies,
    signals: hits.map((s) => s.id),
    families,
    suggestions: matchingSignals(edge, rules.suggestionSignals ?? []).map((s) => s.id),
  };
}

const FINAL_MARKER = Object.freeze({ extensions: Object.freeze({ is_final: true }) });

function isFinal(doc: Record<string, Json>): boolean {
  const ext = doc["extensions"];
  return isObject(ext) && ext["is_final"] === true;
}

/** Index of the edge a path runs through: [..., "edges", n, ...] -> n, for the given prefix. */
function edgeIndexOf(path: readonly Json[], arrays: readonly (readonly string[])[]): { array: string; index: number; rest: number } | null {
  for (const a of arrays) {
    // Incremental paths start below "data": the array path without its leading "data".
    const p = a[0] === "data" ? a.slice(1) : a;
    if (path.length <= p.length) continue;
    if (!p.every((k, i) => path[i] === k)) continue;
    const n = path[p.length];
    if (typeof n === "number") return { array: p.join("."), index: n, rest: path.length - p.length - 1 };
  }
  return null;
}

export interface AdRuleReport {
  removed: { index: number; signals: string[]; families: string[] }[];
  droppedDocuments: number;
  finalMarkers: number;
}

/**
 * A DocumentRule for one response. Create a new one per response: it remembers which edges it
 * removed so that their deferred parts are dropped as well. `report` collects what it did.
 */
export function createAdRule(rules: AdRules, report: AdRuleReport = { removed: [], droppedDocuments: 0, finalMarkers: 0 }): DocumentRule & { report: AdRuleReport } {
  const removed = new Set<string>();
  const drop = (doc: Record<string, Json>): Decision => {
    report.droppedDocuments++;
    if (isFinal(doc)) {
      report.finalMarkers++;
      return { action: "replace", value: FINAL_MARKER };
    }
    return DROP;
  };
  const rule = ((doc: Json): Decision => {
    if (!isObject(doc)) return KEEP;
    const path = doc["path"];
    if (Array.isArray(path)) {
      const at = edgeIndexOf(path, rules.edgeArrays);
      if (at === null) return KEEP;
      const key = `${at.array}#${at.index}`;
      if (at.rest === 0) {
        const label = doc["label"];
        if (typeof label === "string" && !rules.streamLabels.includes(label)) return KEEP;
        const verdict = evaluateEdge(rules, doc["data"]);
        if (!verdict.isAd) return KEEP;
        removed.add(key);
        report.removed.push({ index: at.index, signals: verdict.signals, families: verdict.families });
        return drop(doc);
      }
      return removed.has(key) ? drop(doc) : KEEP;
    }
    let changed = false;
    for (const a of rules.edgeArrays) {
      const parent = valuesAt(doc, a.slice(0, -1))[0];
      const last = a[a.length - 1]!;
      if (!isObject(parent) || !Array.isArray(parent[last])) continue;
      const edges = parent[last] as Json[];
      const kept: Json[] = [];
      edges.forEach((edge, index) => {
        const verdict = evaluateEdge(rules, edge);
        if (verdict.isAd) {
          removed.add(`${(a[0] === "data" ? a.slice(1) : a).join(".")}#${index}`);
          report.removed.push({ index, signals: verdict.signals, families: verdict.families });
        } else {
          kept.push(edge);
        }
      });
      if (kept.length !== edges.length) {
        parent[last] = kept;
        changed = true;
      }
    }
    return changed ? { action: "replace", value: doc } : KEEP;
  }) as unknown as DocumentRule & { report: AdRuleReport };
  rule.report = report;
  return rule;
}
