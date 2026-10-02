// Layer 2 redaction (taint): scrubs every occurrence of every remembered secret value from
// stored capture data, including data recorded before the value was first recognized.
//
// The same algorithm is implemented in Kotlin for the app's finalize pass
// (capture/src/main/kotlin/.../TaintScrubber.kt). Both run the shared vectors in
// test/vectors/taint.json. docs/CAPTURE.md section 5 is the specification:
//
// 1. A value is eligible if it has at least minTaintLength characters, is not in the stop
//    list (case-insensitive), is not one repeated character, and is not a layer 1 placeholder.
//    Shorter values are only redacted where layer 1 finds them under their key.
// 2. Each eligible value is searched in several encodings: as is, URL-encoded
//    (encodeURIComponent), form-encoded (space as +), JSON-escaped, JSON-escaped with "/" as
//    "\/", JSON-escaped twice, and HTML-escaped. Variants shorter than minTaintLength in UTF-8
//    bytes are skipped. If two values share a variant, the first value's label wins.
// 3. Data is scanned as bytes from left to right. At each position the longest variant that
//    matches is replaced by `!T:<label>!` and the scan continues after it.
// 4. A match that is a whole JSON number (the value is a number literal, the nearest
//    non-blank byte before it is `:`, `,` or `[`, and after it `,`, `]` or `}`) is replaced by
//    the placeholder as a JSON string, so the document stays valid JSON. The quotes are escaped
//    for the depth of JSON-in-a-string the match sits at (see quoteAt).

import { isPlaceholder, l2Placeholder, RULES, sanitizeLabel, type Secret } from "./redact";
import { bytesToLatin1 } from "./bytes";

export interface TaintOptions {
  minTaintLength: number;
  stopList: readonly string[];
}

export const DEFAULT_TAINT_OPTIONS: TaintOptions = { minTaintLength: RULES.minTaintLength, stopList: RULES.taintStopList };

export function isEligible(value: string, options: TaintOptions = DEFAULT_TAINT_OPTIONS): boolean {
  if (value.length < options.minTaintLength) return false;
  const lower = value.toLowerCase();
  if (options.stopList.some((s) => s.toLowerCase() === lower)) return false;
  if (/^([\s\S])\1*$/u.test(value)) return false;
  return !isPlaceholder(value);
}

export function jsonEscape(value: string): string {
  return JSON.stringify(value).slice(1, -1);
}

export function htmlEscape(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

/** The encodings a value is searched in, distinct, in a fixed order. */
export function variants(value: string): string[] {
  const json = jsonEscape(value);
  // encodeURIComponent throws on a lone surrogate; such a value has no URL variants.
  let uri: string[] = [];
  try {
    uri = [encodeURIComponent(value), encodeURIComponent(value).replace(/%20/g, "+")];
  } catch {
    uri = [];
  }
  const list = [
    value,
    ...uri,
    json,
    json.replace(/\//g, "\\/"),
    jsonEscape(json),
    htmlEscape(value),
  ];
  return [...new Set(list)];
}

const utf8 = new TextEncoder();

interface Pattern {
  /** The variant as a latin1 string of its UTF-8 bytes. */
  bytes: string;
  label: string;
  /** True if the variant is a JSON number literal (a numeric secret such as a user id). */
  numeric: boolean;
}

const NUMBER_LITERAL = /^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?$/;

function isBlank(c: string | undefined): boolean {
  return c === " " || c === "\t" || c === "\n" || c === "\r";
}

/**
 * The quote to put around the placeholder that replaces the match at [start, end), or undefined
 * if the match is not a whole JSON number.
 *
 * Depth: JSON inside a JSON string writes its quotes as `\"`, inside that as `\\\"`, and so on.
 * The nearest quote before the match belongs to the match's own level (a key, or the end of the
 * previous string). With b backslashes before that quote, the level is the number of trailing
 * zero bits of b + 1 (an escaped backslash before a closing quote adds pairs). A quote followed
 * by `[` or `{` opens a string that holds JSON: one level deeper. A quote followed by anything
 * else but `:`, `,`, `]` or `}` opens a plain string, so the match is text inside it.
 */
export function quoteAt(data: string, start: number, end: number): string | undefined {
  let j = start - 1;
  while (j >= 0 && isBlank(data[j])) j--;
  if (j < 0 || (data[j] !== ":" && data[j] !== "," && data[j] !== "[")) return undefined;
  let k = end;
  while (k < data.length && isBlank(data[k])) k++;
  if (k >= data.length || (data[k] !== "," && data[k] !== "]" && data[k] !== "}")) return undefined;
  const q = data.lastIndexOf('"', j);
  let depth = 0;
  if (q >= 0) {
    let b = 0;
    while (q - b - 1 >= 0 && data[q - b - 1] === "\\") b++;
    for (let n = b + 1; n % 2 === 0; n /= 2) depth++;
    let a = q + 1;
    while (a < data.length && isBlank(data[a])) a++;
    if (data[a] === "[" || data[a] === "{") depth++;
    // Any other quote that is not followed by `:`, `,`, `]` or `}` opens a string the match
    // lies in (a list such as "a,<id>,b"): not a number.
    else if (data[a] !== ":" && data[a] !== "," && data[a] !== "]" && data[a] !== "}") return undefined;
  }
  return "\\".repeat(2 ** Math.min(depth, 6) - 1) + '"';
}

export interface ScrubResult {
  /** Scrubbed data as a latin1 string (one char per byte). */
  text: string;
  /** Replacements per label. */
  counts: Record<string, number>;
}

/** Compiled set of secrets; scrub() can be called on any number of files. */
export class TaintScrubber {
  private readonly byFirst = new Map<number, Pattern[]>();
  readonly eligibleValues: number;
  readonly patternCount: number;

  constructor(secrets: readonly Secret[], options: TaintOptions = DEFAULT_TAINT_OPTIONS) {
    const seen = new Set<string>();
    let eligible = 0;
    for (const s of secrets) {
      if (!isEligible(s.value, options)) continue;
      eligible++;
      for (const v of variants(s.value)) {
        const bytes = bytesToLatin1(utf8.encode(v));
        if (bytes.length < options.minTaintLength || seen.has(bytes)) continue;
        seen.add(bytes);
        const first = bytes.charCodeAt(0);
        const list = this.byFirst.get(first) ?? [];
        list.push({ bytes, label: sanitizeLabel(s.label), numeric: NUMBER_LITERAL.test(bytes) });
        this.byFirst.set(first, list);
      }
    }
    for (const list of this.byFirst.values()) list.sort((a, b) => b.bytes.length - a.bytes.length);
    this.eligibleValues = eligible;
    this.patternCount = seen.size;
  }

  /** Scrubs `data`, a latin1 string (one char per byte). */
  scrub(data: string): ScrubResult {
    const counts: Record<string, number> = {};
    if (this.byFirst.size === 0) return { text: data, counts };
    let out = "";
    let last = 0;
    let i = 0;
    while (i < data.length) {
      const candidates = this.byFirst.get(data.charCodeAt(i));
      const hit = candidates?.find((p) => data.startsWith(p.bytes, i));
      if (hit === undefined) {
        i++;
        continue;
      }
      const quote = hit.numeric ? quoteAt(data, i, i + hit.bytes.length) : undefined;
      const placeholder = l2Placeholder(hit.label);
      out += data.slice(last, i) + (quote === undefined ? placeholder : quote + placeholder + quote);
      counts[hit.label] = (counts[hit.label] ?? 0) + 1;
      i += hit.bytes.length;
      last = i;
    }
    return { text: last === 0 ? data : out + data.slice(last), counts };
  }

  /** Number of variant occurrences left in `data` (0 after a complete scrub). */
  countHits(data: string): number {
    let hits = 0;
    for (let i = 0; i < data.length; i++) {
      const candidates = this.byFirst.get(data.charCodeAt(i));
      if (candidates?.some((p) => data.startsWith(p.bytes, i))) hits++;
    }
    return hits;
  }
}
