// Leak test for fixtures: no string of four or more characters that occurs as a non-structural
// value in a raw session occurs in the fixtures, and neither do the identifier tokens inside
// such values or the identifier and time numbers the sanitizer remaps. Plus pattern scans for
// e-mail addresses, phone numbers and URLs outside the reserved example domains.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { sessionVocabulary, visitSessionInputs } from "./inputs";
import { isObj, Session, type Json } from "../findings/session";
import { readAllowlist } from "./sanitize";
import { addVocabulary, isStructuralKey, KEPT_FORM_FIELDS, makeClassifier, numberIsRemapped } from "./structural";

export interface RawValues {
  strings: Set<string>;
  tokens: Set<string>;
  numbers: Set<string>;
  keys: Set<string>;
}

/** Identifier-like tokens of a string: runs of 8+ letters and digits with a digit, or 6+ digits. */
export function tokensOf(s: string): string[] {
  return s.split(/[^A-Za-z0-9]+/).filter((t) => (t.length >= 8 && /\d/.test(t) && /[A-Za-z]/.test(t)) || /^\d{6,}$/.test(t));
}

/** Non-structural values of the raw sessions, from every source the sanitizer reads. */
export function rawValues(dirs: string[], fixturesDir: string): RawValues {
  const vocabulary = new Set<string>();
  const sessions = dirs.map((d) => new Session(d));
  sessionVocabulary(sessions, (d) => addVocabulary(vocabulary, d), (w) => vocabulary.add(w));
  const classify = makeClassifier(readAllowlist(fixturesDir), vocabulary);
  const structural = (key: string | undefined, v: string, variable: boolean): boolean => classify(key, v, variable);
  const out: RawValues = { strings: new Set(), tokens: new Set(), numbers: new Set(), keys: new Set() };
  const addString = (s: string): void => {
    if (s.length >= 4) out.strings.add(s);
    for (const t of tokensOf(s)) out.tokens.add(t);
  };
  const visit = (v: Json, key: string | undefined, variable: boolean): void => {
    if (typeof v === "string") {
      if (!structural(key, v, variable)) addString(v);
    } else if (typeof v === "number") {
      if (numberIsRemapped(key, v) && String(v).length >= 6) out.numbers.add(String(v));
    } else if (Array.isArray(v)) {
      for (const x of v) visit(x, key, variable);
    } else if (isObj(v)) {
      for (const [k, x] of Object.entries(v)) {
        if (!isStructuralKey(k)) out.keys.add(k);
        visit(x, k, variable);
      }
    }
  };
  for (const session of sessions) {
    visitSessionInputs(session, {
      doc: (v, variable) => visit(v, undefined, variable),
      field: (k, v) => {
        if (v === null || KEPT_FORM_FIELDS.has(k) || (k === "variables" && /^[[{]/.test(v))) return;
        if (!structural(k, v, false)) addString(v);
      },
    });
  }
  return out;
}

export interface FixtureScan {
  files: number;
  strings: number;
  tokens: number;
  numbers: number;
  keys: number;
  leaks: { file: string; kind: string; length: number }[];
  emails: number;
  phones: number;
  foreignUrls: number;
  siteHostMentions: number;
}

function fixtureFiles(dir: string): string[] {
  const out: string[] = [];
  const walkDir = (d: string): void => {
    for (const f of readdirSync(d)) {
      const p = join(d, f);
      if (statSync(p).isDirectory()) walkDir(p);
      else if (/\.(ndjson|json)$/.test(f) && f !== "manifest.json" && f !== "enum-allowlist.json") out.push(p);
    }
  };
  for (const sub of ["graphql", "preload"]) if (existsSync(join(dir, sub))) walkDir(join(dir, sub));
  return out.sort();
}

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}/g;
const PHONE = /\+\d{1,3}[\s.-]?\(?\d{1,4}\)?(?:[\s.-]\d{2,4}){2,4}|\(\d{3}\)\s?\d{3}-\d{4}|\b\d{3}-\d{3}-\d{4}\b/g;
const URL_RE = /https?:\/\/([^/\s"'\\?#]+)/g;

/** Scans every fixture file against the raw values (or only for patterns when `raw` is undefined). */
export function scanFixtures(dir: string, raw: RawValues | undefined): FixtureScan {
  const res: FixtureScan = { files: 0, strings: 0, tokens: 0, numbers: 0, keys: 0, leaks: [], emails: 0, phones: 0, foreignUrls: 0, siteHostMentions: 0 };
  for (const file of fixtureFiles(dir)) {
    res.files++;
    const text = readFileSync(file, "utf8");
    res.emails += (text.match(EMAIL) ?? []).length;
    res.phones += (text.match(PHONE) ?? []).length;
    for (const m of text.matchAll(URL_RE)) if (!/(^|\.)example\.(com|net|org)$/.test(m[1]!)) res.foreignUrls++;
    res.siteHostMentions += (text.match(/facebook\.com|fbcdn\.net|fbsbx\.com/g) ?? []).length;
    const docs: Json[] = file.endsWith(".ndjson") ? text.split("\n").filter(Boolean).map((l) => JSON.parse(l) as Json) : [JSON.parse(text) as Json];
    const name = file.slice(dir.length + 1);
    const check = (s: string, kind: string): void => {
      res.strings++;
      if (raw === undefined) return;
      // Keys are schema field names (kept) or synthetic; only a raw non-schema key is a leak.
      if (kind === "key" ? raw.keys.has(s) : raw.strings.has(s)) res.leaks.push({ file: name, kind, length: s.length });
      for (const t of tokensOf(s)) {
        res.tokens++;
        if (raw.tokens.has(t)) res.leaks.push({ file: name, kind: `${kind} token`, length: t.length });
      }
    };
    const visit = (v: Json): void => {
      if (typeof v === "string") {
        check(v, "string");
        if (/^[[{]/.test(v)) {
          try {
            visit(JSON.parse(v));
          } catch {
            // a string that only looks like JSON
          }
        }
      } else if (typeof v === "number") {
        res.numbers++;
        if (raw !== undefined && Number.isInteger(v) && raw.numbers.has(String(v))) res.leaks.push({ file: name, kind: "number", length: String(v).length });
      } else if (Array.isArray(v)) {
        for (const x of v) visit(x);
      } else if (isObj(v)) {
        for (const [k, x] of Object.entries(v)) {
          res.keys++;
          check(k, "key");
          visit(x);
        }
      }
    };
    for (const d of docs) visit(d);
  }
  return res;
}
