// A pulled, re-scrubbed capture session prepared for analysis: records grouped by request,
// response bodies as text, and parsers for the site's response formats. Read only.

import { basename } from "node:path";
import { latin1ToText } from "../rescrub";
import { loadSession, parseLines, type SessionLine } from "../sessionIo";

export type Json = unknown;
export type Obj = Record<string, Json>;

export function isObj(v: Json): v is Obj {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

export interface Header {
  name: string;
  value?: string;
}

/** Every event of one request, by event name (first occurrence). */
export interface Rec {
  rid: string;
  request?: SessionLine;
  sendHeaders?: SessionLine;
  headers?: SessionLine;
  body?: SessionLine;
  observe?: SessionLine;
  completed?: SessionLine;
  error?: SessionLine;
  redirect?: SessionLine;
  security?: SessionLine;
}

export interface RequestInfo {
  url: string;
  host: string;
  path: string;
  type: string;
  method: string;
  own: boolean;
  tabId: number | undefined;
}

/**
 * Layer 2 placeholders replaced numbers too: the viewer's id appears in the site's JSON as a
 * bare number, so `!T:cookie:c_user!` stands unquoted where a number was, which is not JSON.
 * For analysis such a placeholder outside a string becomes the number 0.
 */
export const BARE_PLACEHOLDER = /(?<=[:[,]\s*)!T:[A-Za-z0-9_.:-]+!(?=\s*[,\]}])/g;

export function repairBare(text: string): { text: string; repaired: number } {
  let repaired = 0;
  const out = text.replace(BARE_PLACEHOLDER, () => {
    repaired++;
    return "0";
  });
  return { text: out, repaired };
}

export class Session {
  readonly id: string;
  readonly lines: SessionLine[];
  readonly recs = new Map<string, Rec>();
  readonly session: Obj;
  private readonly bodies: Map<string, string>;
  /** Bare placeholders repaired per parsed body and parse kind, so a body parsed twice counts once. */
  private readonly repaired = new Map<string, number>();

  /** Distinct bare placeholders repaired while parsing (see repairBare). */
  get repairedPlaceholders(): number {
    let n = 0;
    for (const v of this.repaired.values()) n += v;
    return n;
  }

  constructor(readonly dir: string) {
    const s = loadSession(dir);
    this.id = basename(dir);
    this.session = s.session as Obj;
    this.bodies = s.bodies;
    this.lines = parseLines(s.eventsText);
    for (const l of this.lines) {
      if (l.rid === "") continue;
      let r = this.recs.get(l.rid);
      if (r === undefined) {
        r = { rid: l.rid };
        this.recs.set(l.rid, r);
      }
      const key = l.ev as keyof Rec;
      if (key !== "rid" && r[key] === undefined) (r as unknown as Record<string, SessionLine>)[key] = l;
    }
  }

  /** The source session id (without the -rescrub suffix). */
  get sourceId(): string {
    return this.id.replace(/-rescrub$/, "");
  }

  info(r: Rec): RequestInfo | undefined {
    const l = r.request ?? r.sendHeaders ?? r.headers ?? r.completed ?? r.error;
    const d = l?.d;
    if (l === undefined || d === undefined) return undefined;
    const url = String(d["url"] ?? "");
    let host = "?";
    let path = "?";
    try {
      const u = new URL(url);
      host = u.hostname;
      path = u.pathname;
    } catch {
      // keep "?"
    }
    return { url, host, path, type: String(d["type"] ?? "?"), method: String(d["method"] ?? "?"), own: l.own === true, tabId: typeof d["tabId"] === "number" ? (d["tabId"] as number) : undefined };
  }

  /** The recorded response body as UTF-8 text. */
  bodyText(r: Rec): string | undefined {
    const file = r.body?.["file"];
    if (typeof file !== "string") return undefined;
    const data = this.bodies.get(file);
    return data === undefined ? undefined : latin1ToText(data);
  }

  bodyMeta(r: Rec): Obj | undefined {
    return r.body as Obj | undefined;
  }

  /** Form fields of the request body, in order. */
  fields(r: Rec): [string, string | null][] {
    const b = r.request?.["body"] as { kind?: string; fields?: [string, string | null][] } | undefined;
    return b?.kind === "formData" ? (b.fields ?? []) : [];
  }

  field(r: Rec, name: string): string | null | undefined {
    return this.fields(r).find(([k]) => k === name)?.[1];
  }

  /** Every parse of a body sees the same placeholders: keep the largest count per body. */
  private noteRepaired(r: Rec, n: number): void {
    if (n > (this.repaired.get(r.rid) ?? 0)) this.repaired.set(r.rid, n);
  }

  /** NDJSON documents of a body; lines that fail to parse are counted. */
  ndjson(r: Rec): { docs: Json[]; lines: string[]; failed: number } {
    const text = this.bodyText(r);
    if (text === undefined) return { docs: [], lines: [], failed: 0 };
    const docs: Json[] = [];
    const lines: string[] = [];
    let failed = 0;
    let repaired = 0;
    for (const line of text.split("\n")) {
      if (line.trim() === "") continue;
      const fixed = repairBare(line.replace(/^\s*for ?\(;;\);/, ""));
      repaired += fixed.repaired;
      try {
        docs.push(JSON.parse(fixed.text));
        lines.push(line);
      } catch {
        failed++;
      }
    }
    this.noteRepaired(r, repaired);
    return { docs, lines, failed };
  }

  /** Documents of a body in which every document is behind its own `for (;;);` guard. */
  guarded(r: Rec): { docs: Json[]; failed: number; guards: number } {
    const text = this.bodyText(r);
    if (text === undefined) return { docs: [], failed: 0, guards: 0 };
    const parts = text.split(/for ?\(;;\);/);
    const docs: Json[] = [];
    let failed = 0;
    let repaired = 0;
    for (const p of parts) {
      if (p.trim() === "") continue;
      const fixed = repairBare(p);
      repaired += fixed.repaired;
      try {
        docs.push(JSON.parse(fixed.text));
      } catch {
        failed++;
      }
    }
    this.noteRepaired(r, repaired);
    return { docs, failed, guards: parts.length - 1 };
  }

  /** `<script type="application/json">` islands of an HTML body. */
  islands(r: Rec): { attrs: string; size: number; json: Json | undefined }[] {
    const text = this.bodyText(r);
    if (text === undefined) return [];
    const out: { attrs: string; size: number; json: Json | undefined }[] = [];
    const re = /<script type="application\/json"([^>]*)>([\s\S]*?)<\/script>/g;
    let repaired = 0;
    for (let m = re.exec(text); m !== null; m = re.exec(text)) {
      const fixed = repairBare(m[2]!);
      repaired += fixed.repaired;
      let json: Json | undefined;
      try {
        json = JSON.parse(fixed.text);
      } catch {
        json = undefined;
      }
      out.push({ attrs: m[1]!, size: new TextEncoder().encode(m[2]!).length, json });
    }
    this.noteRepaired(r, repaired);
    return out;
  }

  /** Requests whose info matches. */
  select(pred: (i: RequestInfo, r: Rec) => boolean): Rec[] {
    const out: Rec[] = [];
    for (const r of this.recs.values()) {
      const i = this.info(r);
      if (i !== undefined && pred(i, r)) out.push(r);
    }
    return out;
  }

  graphql(): Rec[] {
    return this.select((i) => i.own && i.path === "/api/graphql/");
  }
}

export function header(list: Json, name: string): string | undefined {
  if (!Array.isArray(list)) return undefined;
  const lower = name.toLowerCase();
  return (list as Header[]).find((h) => h.name.toLowerCase() === lower)?.value;
}

/** Walks every object and array below `root`, depth first, with its key path. */
export function walk(root: Json, visit: (v: Json, path: (string | number)[], key: string | number | undefined) => void): void {
  const stack: [Json, (string | number)[]][] = [[root, []]];
  while (stack.length > 0) {
    const [v, p] = stack.pop()!;
    visit(v, p, p[p.length - 1]);
    if (Array.isArray(v)) for (let i = v.length - 1; i >= 0; i--) stack.push([v[i], [...p, i]]);
    else if (isObj(v)) for (const k of Object.keys(v).reverse()) stack.push([v[k], [...p, k]]);
  }
}

/** Path with array indices replaced by [] (a pattern shared by all elements). */
export function pattern(path: readonly (string | number)[]): string {
  return path.map((k) => (typeof k === "number" ? "[]" : `.${safeKey(k)}`)).join("").replace(/^\./, "");
}

/**
 * A key as it may be printed. Schema field names are identifiers; anything else (route URLs,
 * ids or hashes used as map keys) is shown as <key>, since it can carry personal data.
 */
export function safeKey(k: string): string {
  return /^[A-Za-z_$][A-Za-z0-9_$]{0,79}$/.test(k) && !/\d{5,}/.test(k) ? k : "<key>";
}

export function count<K>(map: Map<K, number>, key: K, n = 1): void {
  map.set(key, (map.get(key) ?? 0) + n);
}

export function median(xs: number[]): number {
  if (xs.length === 0) return 0;
  const s = [...xs].sort((a, b) => a - b);
  return s[Math.floor(s.length / 2)]!;
}

export function getPath(root: Json, path: readonly (string | number)[]): Json {
  let v: Json = root;
  for (const k of path) {
    if (Array.isArray(v) && typeof k === "number") v = v[k];
    else if (isObj(v) && typeof k === "string") v = v[k];
    else return undefined;
  }
  return v;
}

/** Strings that may be printed: type names, enum-like constants, renderer and strategy names. */
export function isPrintableStructural(key: string | number | undefined, v: string): boolean {
  if (key === "__typename" || (typeof key === "string" && key.startsWith("__is"))) return /^[A-Za-z0-9_]{1,80}$/.test(v);
  return /^[A-Z][A-Z0-9_]{1,60}$/.test(v);
}
