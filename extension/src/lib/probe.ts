// Observe-only probe for the real site. For each JSON document (an NDJSON line, a guarded
// single document, or a JSON island of an HTML document) it reports the size, the top-level
// keys, and which candidate key names occur anywhere inside, with counts. It never changes a
// document. The candidate names live in data/probe-keys.json.

import probeKeys from "../../data/probe-keys.json";
import { KEEP, type DocumentRule } from "./ndjsonFilter";

export const CANDIDATE_KEYS: readonly string[] = probeKeys.candidates;

export interface ProbeReport {
  index: number;
  bytes: number;
  /** Top-level keys of an object (at most 40), or ["[array:N]"]. */
  topKeys: string[];
  /** Candidate key name -> occurrences anywhere in the document. Only names that occur. */
  candidates: Record<string, number>;
}

/** Most documents reported per response; later ones are only counted. */
export const MAX_REPORTS = 400;
const MAX_TOP_KEYS = 40;

export function probeDocument(doc: unknown, index: number, bytes: number, candidates: readonly string[] = CANDIDATE_KEYS): ProbeReport {
  const wanted = new Set(candidates);
  const counts: Record<string, number> = {};
  const stack: unknown[] = [doc];
  while (stack.length > 0) {
    const v = stack.pop();
    if (Array.isArray(v)) {
      for (const item of v) if (typeof item === "object" && item !== null) stack.push(item);
    } else if (typeof v === "object" && v !== null) {
      for (const [k, child] of Object.entries(v)) {
        if (wanted.has(k)) counts[k] = (counts[k] ?? 0) + 1;
        if (typeof child === "object" && child !== null) stack.push(child);
      }
    }
  }
  const topKeys = Array.isArray(doc)
    ? [`[array:${doc.length}]`]
    : typeof doc === "object" && doc !== null
      ? Object.keys(doc).slice(0, MAX_TOP_KEYS)
      : [`[${typeof doc}]`];
  return { index, bytes, topKeys, candidates: counts };
}

/** Collects reports for one response. Its rule and transform always keep. */
export class Probe {
  readonly reports: ProbeReport[] = [];
  documents = 0;
  readonly candidateTotals: Record<string, number> = {};

  private add(report: ProbeReport): void {
    this.documents++;
    for (const [k, n] of Object.entries(report.candidates)) this.candidateTotals[k] = (this.candidateTotals[k] ?? 0) + n;
    if (this.reports.length < MAX_REPORTS) this.reports.push(report);
  }

  readonly rule: DocumentRule = (doc, index, info) => {
    this.add(probeDocument(doc, index, info?.bytes ?? -1));
    return KEEP;
  };

  readonly islandTransform = (value: unknown, info?: { readonly bytes: number }): boolean => {
    this.add(probeDocument(value, this.documents, info?.bytes ?? -1));
    return false;
  };
}
