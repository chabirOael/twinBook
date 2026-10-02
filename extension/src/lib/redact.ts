// Layer 1 redaction: replaces credential values at the source, before a capture record leaves
// the extension. Names, keys, attributes and structure stay; every value is replaced by a
// placeholder of the same length. Every value replaced here is remembered with a label (for
// example "cookie:c_user" or "field:fb_dtsg") so that layer 2 (taint.ts, and the app's
// finalize pass) can scrub other occurrences of it from the whole session.
//
// Works on JavaScript strings. Bodies are handled as latin1 strings (one char per byte), so the
// byte length of a body never changes: patterns and placeholders are ASCII.
//
// The rules (which headers and which keys) live in data/redaction-rules.json, one comment per
// entry. docs/CAPTURE.md section 5 is the specification; test/redact.test.ts the test vectors.

import rulesJson from "../../data/redaction-rules.json";

export interface RedactionRules {
  readonly minTaintLength: number;
  readonly taintStopList: readonly string[];
  readonly headers: readonly { readonly name: string; readonly kind: string }[];
  readonly keys: readonly { readonly name: string }[];
}

export const RULES: RedactionRules = rulesJson;

/** Layer 1 placeholder for a value of `length` characters: `!R***!`, same length. */
export function l1Placeholder(length: number): string {
  if (length <= 0) return "";
  if (length < 3) return "*".repeat(length);
  return `!R${"*".repeat(length - 3)}!`;
}

// Layer 1 placeholders, and layer 2 placeholders (`!T:<label>!`): a session re-scrubbed again
// keeps them as they are instead of treating them as new secrets.
const PLACEHOLDER = /^(?:!R\**!|\*{1,2}|!T:[A-Za-z0-9_.:-]+!)$/;

export function isPlaceholder(value: string): boolean {
  return PLACEHOLDER.test(value);
}

/** Label text allowed inside a layer 2 placeholder. */
export function sanitizeLabel(label: string): string {
  return label.replace(/[^A-Za-z0-9_.:-]/g, "_").slice(0, 80);
}

/** Layer 2 placeholder naming the label of the scrubbed value: `!T:cookie:c_user!`. */
export function l2Placeholder(label: string): string {
  return `!T:${sanitizeLabel(label)}!`;
}

export interface Secret {
  readonly value: string;
  readonly label: string;
}

/** Values seen by layer 1, each with the label of its first sighting. Memory only. */
export class SecretSet {
  private readonly map = new Map<string, string>();

  add(value: string, label: string): void {
    if (value.length === 0 || isPlaceholder(value) || this.map.has(value)) return;
    this.map.set(value, sanitizeLabel(label));
  }

  get size(): number {
    return this.map.size;
  }

  entries(): Secret[] {
    return [...this.map].map(([value, label]) => ({ value, label }));
  }

  clear(): void {
    this.map.clear();
  }
}

export interface Header {
  name: string;
  value?: string | undefined;
}

type HeaderKind = "cookie" | "set-cookie" | "value" | "url";

const URL_HEADERS = new Set(["referer", "location", "origin", "content-location", "x-frame-options"]);

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function tryDecodeUri(s: string): string | null {
  try {
    return decodeURIComponent(s.replace(/\+/g, " "));
  } catch {
    return null;
  }
}

function tryJsonUnescape(s: string): string | null {
  try {
    const v: unknown = JSON.parse(`"${s}"`);
    return typeof v === "string" ? v : null;
  } catch {
    return null;
  }
}

export interface RedactionCounts {
  cookies: number;
  setCookies: number;
  headers: number;
  fields: number;
  urlParams: number;
  textValues: number;
}

export class Redactor {
  readonly secrets: SecretSet;
  readonly counts: RedactionCounts = { cookies: 0, setCookies: 0, headers: 0, fields: 0, urlParams: 0, textValues: 0 };
  private readonly headerKinds = new Map<string, HeaderKind>();
  private readonly keyNames: Set<string>;
  private readonly jsonKeyRe: RegExp;
  private readonly bareKeyRe: RegExp;
  private readonly formKeyRe: RegExp;
  private readonly inputNameRe: RegExp;

  constructor(rules: RedactionRules = RULES, secrets: SecretSet = new SecretSet()) {
    this.secrets = secrets;
    for (const h of rules.headers) this.headerKinds.set(h.name.toLowerCase(), h.kind as HeaderKind);
    this.keyNames = new Set(rules.keys.map((k) => k.name.toLowerCase()));
    const keys = rules.keys.map((k) => escapeRegExp(k.name)).join("|");
    // "key":"value" at any JSON escaping level: the backslashes before the key's quotes must be
    // repeated before the value's opening quote (group 1). Group 2 is the key.
    this.jsonKeyRe = new RegExp(`(\\\\*)"(${keys})\\1"\\s*:\\s*\\1"`, "gi");
    // JavaScript object literals: key:"value" or key:'value' with an unquoted key.
    this.bareKeyRe = new RegExp(`(?<![\\w$"'\\\\])(${keys})\\s*:\\s*(["'])`, "gi");
    // key=value in query strings, form bodies and cookies embedded in text.
    this.formKeyRe = new RegExp(`(^|[?&;\\s"'])(${keys})=([^&\\s"'#<>;\\\\]*)`, "gi");
    this.inputNameRe = new RegExp(`\\bname\\s*=\\s*["']?(${keys})["'\\s/>]`, "i");
  }

  get total(): number {
    const c = this.counts;
    return c.cookies + c.setCookies + c.headers + c.fields + c.urlParams + c.textValues;
  }

  isSecretKey(name: string): boolean {
    return this.keyNames.has(name.toLowerCase());
  }

  /** Remembers `raw` and its decoded forms (URL and JSON escaping) under `label`. */
  private remember(raw: string, label: string): void {
    const value = raw.trim();
    if (value.length === 0 || isPlaceholder(value)) return;
    this.secrets.add(value, label);
    let current = value;
    for (let i = 0; i < 3 && current.includes("\\"); i++) {
      const next = tryJsonUnescape(current);
      if (next === null || next === current) break;
      this.secrets.add(next, label);
      current = next;
    }
    if (value.includes("%") || value.includes("+")) {
      const decoded = tryDecodeUri(value);
      if (decoded !== null && decoded !== value) this.secrets.add(decoded, label);
    }
  }

  // ---- headers -------------------------------------------------------------------------

  headers(list: readonly Header[] | undefined): Header[] {
    return (list ?? []).map((h) => ({ name: h.name, value: h.value === undefined ? undefined : this.headerValue(h.name, h.value) }));
  }

  headerValue(name: string, value: string): string {
    const lower = name.toLowerCase();
    const kind = this.headerKinds.get(lower) ?? (URL_HEADERS.has(lower) ? "url" : undefined);
    switch (kind) {
      case "cookie":
        return this.cookieHeader(value);
      case "set-cookie":
        return this.setCookie(value);
      case "value":
        this.counts.headers++;
        this.remember(value, `header:${lower}`);
        // "Bearer <token>": remember the token alone as well.
        if (/^\S+\s+\S+$/.test(value.trim())) this.remember(value.trim().split(/\s+/)[1]!, `header:${lower}`);
        return l1Placeholder(value.length);
      case "url":
        return this.url(value);
      default:
        return value;
    }
  }

  /** `a=1; b=2`: names stay, values are replaced. */
  cookieHeader(value: string): string {
    return value.replace(/(^|;)(\s*)([^=;]*)=([^;]*)/g, (_m, sep: string, ws: string, name: string, v: string) => {
      if (v.length === 0) return `${sep}${ws}${name}=`;
      this.counts.cookies++;
      this.remember(unquote(v), `cookie:${name.trim()}`);
      return `${sep}${ws}${name}=${l1Placeholder(v.length)}`;
    });
  }

  /** One or more Set-Cookie values (Gecko joins several with a newline). Attributes stay. */
  setCookie(value: string): string {
    return value
      .split("\n")
      .map((line) => {
        const semi = line.indexOf(";");
        const pair = semi < 0 ? line : line.slice(0, semi);
        const rest = semi < 0 ? "" : line.slice(semi);
        const eq = pair.indexOf("=");
        if (eq < 0) return line;
        const name = pair.slice(0, eq);
        const v = pair.slice(eq + 1);
        if (v.length === 0) return line;
        this.counts.setCookies++;
        this.remember(unquote(v), `cookie:${name.trim()}`);
        return `${name}=${l1Placeholder(v.length)}${rest}`;
      })
      .join("\n");
  }

  // ---- URLs and forms ------------------------------------------------------------------

  /** Replaces the values of secret keys in the query string and fragment of `url`. */
  url(url: string): string {
    const q = url.search(/[?#]/);
    if (q < 0) return url;
    return url.slice(0, q) + url.slice(q).replace(/([?&#;])([^=&#;]*)=([^&#;]*)/g, (m, sep: string, k: string, v: string) => {
      const key = tryDecodeUri(k) ?? k;
      if (v.length === 0 || !this.isSecretKey(key)) return m;
      this.counts.urlParams++;
      this.remember(v, `field:${key}`);
      return `${sep}${k}=${l1Placeholder(v.length)}`;
    });
  }

  /**
   * Parsed form fields in order. Secret keys lose their values; other values are scanned as text.
   * A field without a value is kept as `[name, null]`: Gecko's form parser yields `undefined` for
   * a body part with no `=` (seen on the site's `/ajax/route-definition/` posts), and a missing
   * value must not throw, or the whole request line is lost.
   */
  formFields(fields: readonly (readonly [string, string | null | undefined])[]): [string, string | null][] {
    return fields.map(([name, value]) => {
      if (typeof value !== "string") return [String(name), null];
      if (this.isSecretKey(name)) {
        if (value.length === 0) return [name, value];
        this.counts.fields++;
        this.remember(value, `field:${name}`);
        return [name, l1Placeholder(value.length)];
      }
      return [name, this.text(value)];
    });
  }

  // ---- text bodies ---------------------------------------------------------------------

  /**
   * Replaces secret values inside text: JSON ("key":"value" at any escaping level), JavaScript
   * literals (key:"value"), key=value pairs, and <input name="key" value="..."> tags.
   */
  text(text: string): string {
    let s = this.jsonValues(text);
    s = this.bareValues(s);
    s = s.replace(this.formKeyRe, (m, pre: string, key: string, v: string) => {
      if (v.length === 0 || isPlaceholder(v)) return m;
      this.counts.textValues++;
      this.remember(v, `field:${key}`);
      return `${pre}${key}=${l1Placeholder(v.length)}`;
    });
    s = s.replace(/<input\b[^>]*>/gi, (tag) => {
      const name = this.inputNameRe.exec(tag);
      if (name === null) return tag;
      return tag.replace(/(\bvalue\s*=\s*)(["'])(.*?)\2/i, (m, pre: string, q: string, v: string) => {
        if (v.length === 0 || isPlaceholder(v)) return m;
        this.counts.textValues++;
        this.remember(v, `field:${name[1]!}`);
        return `${pre}${q}${l1Placeholder(v.length)}${q}`;
      });
    });
    return s;
  }

  private jsonValues(text: string): string {
    const re = new RegExp(this.jsonKeyRe.source, "gi");
    let out = "";
    let last = 0;
    for (let m = re.exec(text); m !== null; m = re.exec(text)) {
      const level = m[1]!.length;
      const start = m.index + m[0].length;
      const end = findStringEnd(text, start, '"', level);
      if (end < 0) continue;
      const raw = text.slice(start, end);
      re.lastIndex = end;
      if (raw.length === 0 || isPlaceholder(raw)) continue;
      this.counts.textValues++;
      this.remember(raw, `field:${m[2]!}`);
      out += text.slice(last, start) + l1Placeholder(raw.length);
      last = end;
    }
    return last === 0 ? text : out + text.slice(last);
  }

  private bareValues(text: string): string {
    const re = new RegExp(this.bareKeyRe.source, "gi");
    let out = "";
    let last = 0;
    for (let m = re.exec(text); m !== null; m = re.exec(text)) {
      const start = m.index + m[0].length;
      const end = findStringEnd(text, start, m[2]!, 0);
      if (end < 0) continue;
      const raw = text.slice(start, end);
      re.lastIndex = end;
      if (raw.length === 0 || isPlaceholder(raw)) continue;
      this.counts.textValues++;
      this.remember(raw, `field:${m[1]!}`);
      out += text.slice(last, start) + l1Placeholder(raw.length);
      last = end;
    }
    return last === 0 ? text : out + text.slice(last);
  }
}

/** Longest string value scanned for its closing quote. Longer values are left alone. */
const MAX_VALUE_SCAN = 64 * 1024;

/**
 * Index where the string value that starts at `start` ends: the position of the backslashes
 * that precede its closing quote. `backslashes` is the escaping level, given as the number of
 * backslashes in front of the key's own quotes: 0 for plain JSON, 1 for JSON inside a JSON
 * string, 3 for one level deeper. At that level a closing quote is preceded by a run of
 * backslashes r with r mod 2(b+1) = b; any other run is an escaped quote inside the value.
 * -1 if no end is found within MAX_VALUE_SCAN characters or before a line break.
 */
export function findStringEnd(text: string, start: number, quote: string, backslashes: number): number {
  const limit = Math.min(text.length, start + MAX_VALUE_SCAN);
  const modulus = 2 * (backslashes + 1);
  let run = 0;
  for (let i = start; i < limit; i++) {
    const c = text.charCodeAt(i);
    if (c === 0x5c) {
      run++;
      continue;
    }
    if (c === 0x0a || c === 0x0d) return -1;
    if (text[i] === quote && run % modulus === backslashes) return i - backslashes;
    run = 0;
  }
  return -1;
}

function unquote(v: string): string {
  const t = v.trim();
  return t.length >= 2 && t.startsWith('"') && t.endsWith('"') ? t.slice(1, -1) : t;
}
