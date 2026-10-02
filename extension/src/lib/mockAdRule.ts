// M1 mock ad rule. The real rule engine arrives in M4.
//
// Mock marker: an edge whose `node` has `"mock_sponsored": true` is an ad.
// - Any `edges` array, at any depth of a document, loses its ad edges.
// - An incremental document (one with a `path` array) whose `data` is an ad edge is dropped.
//   If it was the final document (`extensions.is_final === true`), it is replaced by a minimal
//   document that still carries `is_final: true`, so the client still sees the end.

import { DROP, KEEP, type Decision, type DocumentRule } from "./ndjsonFilter";

type Json = Record<string, unknown>;

function isObject(value: unknown): value is Json {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** True if `edge` is `{ node: { mock_sponsored: true, ... }, ... }`. */
export function isMockAdEdge(edge: unknown): boolean {
  return isObject(edge) && isObject(edge["node"]) && edge["node"]["mock_sponsored"] === true;
}

/** Removes ad edges from every `edges` array inside `value`, in place. Returns how many. */
export function removeMockAdEdges(value: unknown): number {
  let removed = 0;
  const stack: unknown[] = [value];
  while (stack.length > 0) {
    const current = stack.pop();
    if (Array.isArray(current)) {
      for (const item of current) stack.push(item);
    } else if (isObject(current)) {
      for (const [key, child] of Object.entries(current)) {
        if (key === "edges" && Array.isArray(child)) {
          const kept = child.filter((edge) => !isMockAdEdge(edge));
          if (kept.length !== child.length) {
            removed += child.length - kept.length;
            current[key] = kept;
          }
          for (const edge of kept) stack.push(edge);
        } else {
          stack.push(child);
        }
      }
    }
  }
  return removed;
}

/** The minimal document that replaces a dropped final document. */
export const FINAL_REPLACEMENT = Object.freeze({ extensions: Object.freeze({ is_final: true }) });

export const mockAdRule: DocumentRule = (doc): Decision => {
  if (!isObject(doc)) return KEEP;
  if (Array.isArray(doc["path"])) {
    if (isMockAdEdge(doc["data"])) {
      const extensions = doc["extensions"];
      const isFinal = isObject(extensions) && extensions["is_final"] === true;
      return isFinal ? { action: "replace", value: FINAL_REPLACEMENT } : DROP;
    }
    return removeMockAdEdges(doc["data"]) > 0 ? { action: "replace", value: doc } : KEEP;
  }
  return removeMockAdEdges(doc) > 0 ? { action: "replace", value: doc } : KEEP;
};

/** Island transform for the document filter: true if the value was changed. */
export function mockAdIslandTransform(value: unknown): boolean {
  return removeMockAdEdges(value) > 0;
}
