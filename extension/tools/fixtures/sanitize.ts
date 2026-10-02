// Sanitizer: turns re-scrubbed sessions into structure-only fixtures (fixtures/README.md).
//
// Kept: keys, structure, array lengths, booleans, nulls, counts and other non-identifier
// numbers, and strings the reviewed allowlist calls structural (structural.ts and
// fixtures/enum-allowlist.json). Replaced, deterministically within one fixture set (the same
// input value always gives the same output):
// - URLs -> https://<kind>.example.com/<n>[.<ext>], keeping only the kind of resource;
// - identifiers (no whitespace, ASCII) -> same length and character class per position;
// - text -> synthetic text of the same length in UTF-16 units (ranges stay valid);
// - identifier and time numbers -> remapped (same digit count; times shifted by one offset).
// Synthetic values come from a counter in first-seen order, never from a hash of the value, so
// a guessed value cannot be confirmed by recomputing its replacement.

import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { prefetchedResults, preloaderName } from "../findings/feed";
import { routePreloads } from "../findings/sections";
import { isObj, type Json, type Obj, type Rec, type Session } from "../findings/session";
import { sessionVocabulary, visitSessionInputs } from "./inputs";
import { addVocabulary, isStructuralKey, KEPT_FORM_FIELDS, makeClassifier, numberIsRemapped, TIME_KEY, type EnumAllowlist } from "./structural";

export const SANITIZER_VERSION = 1;

/** Queries whose responses become fixtures: everything a native screen of v1 needs. */
export const FIXTURE_QUERIES = [
  "CometNewsFeedPaginationQuery",
  "CometSinglePostDialogContentQuery",
  "CommentsListComponentsPaginationQuery",
  "CometNotificationsDropdownQuery",
  "CometNotificationsListPaginationQuery",
  "FBUnifiedVideoRootWithEntrypointQuery",
  "FBUnifiedVideoContainerQuery",
  "FBUnifiedVideoSeenStateMutation",
  "ProfileCometTimelineFeedRefetchQuery",
  "ProfileCometTilesFeedPaginationQuery",
  "CometHovercardQueryRendererQuery",
  "StoriesTrayRectangularQuery",
  "CometSearchKeywordDataSourceQuery",
  "CometHomeRightSideEgoRefetchQuery",
];

/** Preloaded results (page document, route definitions) that become fixtures. */
export const FIXTURE_PRELOADS = ["CometModernHomeFeedQuery", "ProfileCometHeaderQuery", "ProfileCometTimelineFeedQuery", "ProfileCometTopAppSectionQuery", "SearchCometResultsInitialResultsQuery"];

export type { EnumAllowlist };

const UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const LOWER = "abcdefghijklmnopqrstuvwxyz";
const DIGITS = "0123456789";

/** Small deterministic generator (xorshift32) seeded by the replacement's index. */
function rng(seed: number): () => number {
  let x = (seed * 2654435761) >>> 0 || 1;
  return () => {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    return x;
  };
}

function urlKind(u: URL): { host: string; ext: string } {
  const path = u.pathname.toLowerCase();
  const ext = /\.(jpe?g|png|webp|gif|mp4|m4a|mpd|m3u8|js|css|vtt|srt)$/.exec(path)?.[1] ?? "";
  const h = u.hostname;
  if (/fbcdn\.net$|fbsbx\.com$/.test(h)) return { host: ext === "mp4" || ext === "m4a" || ext === "mpd" ? "video" : ext === "vtt" || ext === "srt" ? "captions" : ext === "js" || ext === "css" ? "static" : "media", ext };
  if (/^l\.facebook\.com$|^lm\.facebook\.com$/.test(h)) return { host: "link", ext: "" };
  if (/facebook\.com$/.test(h)) return { host: "www", ext: "" };
  return { host: "external", ext: "" };
}

export interface SanitizeStats {
  strings: number;
  kept: number;
  replaced: number;
  urls: number;
  numbersRemapped: number;
  keysReplaced: number;
  enumCandidates: Map<string, number>;
}

export class Sanitizer {
  private readonly strings = new Map<string, string>();
  private readonly numbers = new Map<string, number>();
  private readonly keys = new Map<string, string>();
  private readonly classify: (key: string | undefined, value: string, variable?: boolean) => boolean;
  private next = 1;
  readonly stats: SanitizeStats = { strings: 0, kept: 0, replaced: 0, urls: 0, numbersRemapped: 0, keysReplaced: 0, enumCandidates: new Map() };

  /** `timeOffsetSeconds` is subtracted from every time value (seconds; times in ms scaled). */
  constructor(
    allow: EnumAllowlist,
    private readonly timeOffsetSeconds: number,
    vocabulary: ReadonlySet<string> = new Set(),
  ) {
    this.classify = makeClassifier(allow, vocabulary);
  }

  /**
   * Structural per the shared classifier, and never a value that occurs as a non-structural
   * value anywhere in the input (`poisoned`), so that no such value survives in any position.
   */
  structural(key: string | undefined, value: string, variable = false): boolean {
    if (/^[A-Z][A-Z0-9_]{1,40}$/.test(value) && key !== "__typename" && !(key ?? "").startsWith("__is")) {
      const pair = `${key ?? ""}=${value}`;
      this.stats.enumCandidates.set(pair, (this.stats.enumCandidates.get(pair) ?? 0) + 1);
    }
    return this.classify(key, value, variable) && !this.poisoned.has(value);
  }

  /** Every input string, so that no synthetic value can equal a real one. */
  private readonly avoid = new Set<string>();
  private readonly used = new Set<string>();
  /** Input strings of 4+ characters that occur at least once as a non-structural value. */
  readonly poisoned = new Set<string>();

  /** Registers the input values before any replacement is made (see buildFixtures). */
  learn(v: Json, key?: string, variable = false): void {
    if (typeof v === "string") {
      this.avoid.add(v);
      if (v.length >= 4 && !this.classify(key, v, variable)) this.poisoned.add(v);
    } else if (Array.isArray(v)) for (const x of v) this.learn(x, key, variable);
    else if (isObj(v)) for (const [k, x] of Object.entries(v)) {
      this.avoid.add(k);
      this.learn(x, k, variable);
    }
  }

  /** A form field value as the sanitizer will see it (see fields()). */
  learnField(k: string, v: string | null): void {
    this.avoid.add(k);
    if (v === null) return;
    this.avoid.add(v);
    if (KEPT_FORM_FIELDS.has(k)) return;
    if (k === "variables" && /^[[{]/.test(v)) return;
    if (v.length >= 4 && !this.classify(k, v)) this.poisoned.add(v);
  }

  string(key: string | undefined, value: string, variable = false): string {
    this.stats.strings++;
    if (this.structural(key, value, variable)) {
      this.stats.kept++;
      return value;
    }
    this.stats.replaced++;
    let out = this.strings.get(value);
    if (out === undefined) {
      // Short values have few possible replacements: draw again until the replacement is
      // neither a real input value nor another value's replacement.
      // Values under 4 characters are below the leak test's threshold and have too few
      // possible replacements, so only longer ones are checked.
      out = this.synthetic(value);
      if (value.length >= 4) {
        for (let i = 0; (this.avoid.has(out) || this.used.has(out)) && i < 1000; i++) out = this.synthetic(value);
        if (this.avoid.has(out) || this.used.has(out)) throw new Error(`no unused replacement for a value of length ${value.length}`);
      }
      this.used.add(out);
      this.strings.set(value, out);
    }
    return out;
  }

  private synthetic(value: string): string {
    const n = this.next++;
    const r = rng(n);
    if (/^https?:\/\//i.test(value)) {
      this.stats.urls++;
      try {
        const { host, ext } = urlKind(new URL(value));
        return `https://${host}.example.com/${n}${ext === "" ? "" : `.${ext}`}`;
      } catch {
        return `https://www.example.com/${n}`;
      }
    }
    if (value.startsWith("/") && !/\s/.test(value)) return `/x/${n}`;
    if (/^\d+$/.test(value)) {
      let s = "";
      for (let i = 0; i < value.length; i++) s += DIGITS[i === 0 && value[0] !== "0" ? 1 + (r() % 9) : r() % 10];
      return s;
    }
    if (/^[\x21-\x7e]+$/.test(value)) {
      // Identifier: same length, same class at every position, punctuation kept.
      let s = "";
      for (const c of value) s += c >= "A" && c <= "Z" ? UPPER[r() % 26] : c >= "a" && c <= "z" ? LOWER[r() % 26] : c >= "0" && c <= "9" ? DIGITS[r() % 10] : c;
      return s === value ? `${s.slice(0, -1)}${s.endsWith("x") ? "y" : "x"}` : s;
    }
    // Text: same length in UTF-16 units, line breaks kept, words of synthetic length.
    let s = "";
    let word = 0;
    for (let i = 0; i < value.length; i++) {
      const c = value[i]!;
      if (c === "\n") {
        s += "\n";
        word = 0;
      } else if (word >= 3 + (r() % 7)) {
        s += " ";
        word = 0;
      } else {
        s += LOWER[r() % 26];
        word++;
      }
    }
    return s;
  }

  number(key: string | undefined, value: number): number {
    if (!numberIsRemapped(key, value)) return value;
    this.stats.numbersRemapped++;
    if (TIME_KEY.test(key ?? "")) return value >= 1_000_000_000_000 ? value - this.timeOffsetSeconds * 1000 : value - this.timeOffsetSeconds;
    const raw = String(value);
    let out = this.numbers.get(raw);
    if (out === undefined) {
      const r = rng(this.next++);
      let s = String(1 + (r() % 9));
      for (let i = 1; i < Math.min(raw.length, 15); i++) s += DIGITS[r() % 10];
      out = Number(s);
      this.numbers.set(raw, out);
    }
    return out;
  }

  key(k: string): string {
    if (isStructuralKey(k)) return k;
    this.stats.keysReplaced++;
    let out = this.keys.get(k);
    if (out === undefined) {
      out = `k${this.next++}`;
      this.keys.set(k, out);
    }
    return out;
  }

  /** Sanitizes any JSON value; `variable` selects the request-variable rules for strings. */
  value(v: Json, key?: string, variable = false): Json {
    if (typeof v === "string") return this.string(key, v, variable);
    if (typeof v === "number") return this.number(key, v);
    if (Array.isArray(v)) return v.map((x) => this.value(x, key, variable));
    if (isObj(v)) {
      const out: Obj = {};
      for (const [k, x] of Object.entries(v)) out[this.key(k)] = this.value(x, k, variable);
      return out;
    }
    return v;
  }

  /** Form fields of a request: kept constants, sanitized JSON for variables, the rest replaced. */
  fields(fields: [string, string | null][]): [string, Json][] {
    return fields.map(([k, v]) => {
      if (v === null) return [k, null];
      if (KEPT_FORM_FIELDS.has(k)) return [k, v];
      if (k === "variables") {
        try {
          return [k, JSON.stringify(this.value(JSON.parse(v), undefined, true))];
        } catch {
          return [k, this.string(k, v)];
        }
      }
      return [k, this.string(k, v)];
    });
  }
}

export interface FixtureEntry {
  file: string;
  kind: "graphql-request" | "graphql-response" | "preload";
  query: string;
  docId?: string;
  session: string;
  source: string;
  documents?: number;
  bytes: number;
}

export interface FixtureSet {
  files: Map<string, string>;
  entries: FixtureEntry[];
  stats: SanitizeStats;
}

function lines(docs: Json[]): string {
  return docs.map((d) => JSON.stringify(d)).join("\n") + "\n";
}

/** Builds the fixture files (in memory) from re-scrubbed sessions. */
export function buildFixtures(sessions: Session[], allow: EnumAllowlist): FixtureSet {
  const starts = sessions.map((s) => Math.floor(Number(s.session["startedAt"] ?? 0) / 1000)).filter((x) => x > 0);
  // Times are shifted so that the first capture starts on 2001-01-01T00:00:00Z.
  const offset = (starts.length > 0 ? Math.min(...starts) : 0) - 978307200;
  const vocabulary = new Set<string>();
  sessionVocabulary(sessions, (d) => addVocabulary(vocabulary, d), (w) => vocabulary.add(w));
  const z = new Sanitizer(allow, offset, vocabulary);
  for (const s of sessions) {
    visitSessionInputs(s, {
      doc: (v, variable) => z.learn(v, undefined, variable),
      field: (k, v) => z.learnField(k, v),
    });
  }
  const files = new Map<string, string>();
  const entries: FixtureEntry[] = [];
  const short = (s: Session): string => s.sourceId.slice(9, 15);
  for (const s of sessions) {
    const graphql = s.graphql().filter((r) => r.request !== undefined && r.body !== undefined && r.body["truncated"] !== true);
    graphql.sort((a, b) => Number(a.request!.t) - Number(b.request!.t));
    for (const r of graphql) {
      const query = String(s.field(r, "fb_api_req_friendly_name"));
      if (!FIXTURE_QUERIES.includes(query)) continue;
      const base = `graphql/${query}/${short(s)}-${r.rid}`;
      const req = {
        query,
        docId: s.field(r, "doc_id"),
        method: "POST",
        path: "/api/graphql/",
        headerNames: ((r.sendHeaders?.["headers"] as { name: string }[] | undefined) ?? []).map((h) => h.name),
        fields: z.fields(s.fields(r)),
      };
      const reqText = JSON.stringify(req, null, 2) + "\n";
      files.set(`${base}.request.json`, reqText);
      entries.push({ file: `${base}.request.json`, kind: "graphql-request", query, docId: String(req.docId), session: s.sourceId, source: `rid ${r.rid}`, bytes: reqText.length });
      const { docs, failed } = s.ndjson(r);
      if (failed > 0) throw new Error(`${s.id} rid ${r.rid}: ${failed} unparsed lines`);
      const text = lines(docs.map((d) => z.value(d)));
      files.set(`${base}.response.ndjson`, text);
      entries.push({ file: `${base}.response.ndjson`, kind: "graphql-response", query, docId: String(req.docId), session: s.sourceId, source: `rid ${r.rid}`, documents: docs.length, bytes: new TextEncoder().encode(text).length });
    }
    for (const r of s.select((i) => i.own && i.type === "main_frame")) {
      const results = prefetchedResults(s, r).filter((p) => FIXTURE_PRELOADS.includes(preloaderName(p.preloader)));
      const byQuery = new Map<string, Obj[]>();
      for (const p of results) {
        const q = preloaderName(p.preloader);
        byQuery.set(q, [...(byQuery.get(q) ?? []), p.result]);
      }
      for (const [q, list] of byQuery) addPreload(files, entries, z, `preload/${q}/${short(s)}-document.ndjson`, q, s, "page document", list);
    }
    const route = routePreloads([s]);
    const byQuery = new Map<string, Obj[]>();
    for (const p of route) if (FIXTURE_PRELOADS.includes(p.name)) byQuery.set(p.name, [...(byQuery.get(p.name) ?? []), p.result]);
    for (const [q, list] of byQuery) addPreload(files, entries, z, `preload/${q}/${short(s)}-route-definition.ndjson`, q, s, "/ajax/route-definition/", list);
  }
  return { files, entries, stats: z.stats };
}

function addPreload(files: Map<string, string>, entries: FixtureEntry[], z: Sanitizer, file: string, query: string, s: Session, source: string, list: Obj[]): void {
  const text = lines(list.map((d) => z.value(d)));
  files.set(file, text);
  entries.push({ file, kind: "preload", query, session: s.sourceId, source, documents: list.length, bytes: new TextEncoder().encode(text).length });
}

/** Writes a fixture set into `dir` (replacing the generated files, keeping README.md and the allowlist). */
export function writeFixtures(dir: string, set: FixtureSet, manifest: Obj): void {
  for (const sub of ["graphql", "preload"]) rmSync(join(dir, sub), { recursive: true, force: true });
  for (const [name, text] of set.files) {
    mkdirSync(dirname(join(dir, name)), { recursive: true });
    writeFileSync(join(dir, name), text);
  }
  writeFileSync(join(dir, "manifest.json"), JSON.stringify(manifest, null, 2) + "\n");
}

export function readAllowlist(dir: string): EnumAllowlist {
  const f = join(dir, "enum-allowlist.json");
  return existsSync(f) ? (JSON.parse(readFileSync(f, "utf8")) as EnumAllowlist) : { about: "", values: [] };
}

export type { Rec };
