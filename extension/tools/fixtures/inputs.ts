// Every value a recorded session holds, walked the same way by the sanitizer (to learn which
// values are real and which occur as plain data) and by the leak test (to collect them).

import { prefetchedResults } from "../findings/feed";
import { routePreloads } from "../findings/sections";
import type { Json, Session } from "../findings/session";
import { KEPT_FORM_FIELDS } from "./structural";

export interface InputVisitor {
  /** A parsed JSON document; `variable` is true for GraphQL request variables. */
  doc(v: Json, variable: boolean): void;
  /** A form field or URL parameter of a request. */
  field(name: string, value: string | null): void;

}

export function visitSessionInputs(s: Session, visit: InputVisitor): void {
  for (const r of s.recs.values()) {
    const info = s.info(r);
    if (info === undefined) continue;
    try {
      // URL parameters are fields of the request, classified like form fields.
      for (const [k, v] of new URL(info.url).searchParams) visit.field(k, v);
    } catch {
      // not a URL
    }
    for (const [k, v] of s.fields(r)) {
      visit.field(k, v);
      if (k === "variables" && v !== null) {
        try {
          visit.doc(JSON.parse(v) as Json, true);
        } catch {
          // not JSON
        }
      }
    }
    if (r.body === undefined || r.body["truncated"] === true) continue;
    if (info.type === "main_frame") for (const isl of s.islands(r)) visit.doc(isl.json, false);
    else if (info.path === "/api/graphql/") for (const d of s.ndjson(r).docs) visit.doc(d, false);
    else for (const d of [...s.guarded(r).docs, ...s.ndjson(r).docs]) visit.doc(d, false);
  }
  for (const r of s.select((i) => i.own && i.type === "main_frame")) for (const p of prefetchedResults(s, r)) visit.doc(p.result, false);
  for (const p of routePreloads([s])) visit.doc(p.result, false);
}

/** The schema vocabulary of sessions: keys and type names of GraphQL responses and preloaded results. */
export function sessionVocabulary(sessions: Session[], add: (doc: Json) => void, addWord?: (word: string) => void): void {
  for (const s of sessions) {
    // The request protocol of the site's own hosts: field names, header names, the GraphQL
    // path, and the values of the form fields kept verbatim (client constants).
    if (addWord !== undefined) {
      addWord("/api/graphql/");
      for (const r of s.recs.values()) {
        if (s.info(r)?.own !== true) continue;
        for (const [k, v] of s.fields(r)) {
          addWord(k);
          if (v !== null && KEPT_FORM_FIELDS.has(k)) addWord(v);
        }
        for (const h of (r.sendHeaders?.["headers"] as { name: string }[] | undefined) ?? []) addWord(h.name);
      }
    }
    for (const r of s.graphql()) if (r.body !== undefined && r.body["truncated"] !== true) for (const d of s.ndjson(r).docs) add(d);
    for (const r of s.select((i) => i.own && i.type === "main_frame")) for (const p of prefetchedResults(s, r)) add(p.result);
    for (const p of routePreloads([s])) add(p.result);
  }
}
