// Opaque-value scan: lists every JSON key, form field, URL parameter and header whose values
// are long, opaque and recur across requests. It prints key names, value lengths, counts,
// shapes and where the key occurs. It never prints a value.
//
// "Opaque": at least MIN_LENGTH characters, no whitespace, not a URL or path, not a redaction
// placeholder, made of token characters. "Recurs": the same value occurs in at least two
// different records (requests or response bodies).

import { findStringEnd } from "../src/lib/redact";
import { latin1ToText } from "./rescrub";
import { loadSession, parseLines, type SessionLine } from "./sessionIo";

export const MIN_LENGTH = 16;
const TOKEN_CHARS = /^[A-Za-z0-9+/=_\-:.%~|]+$/;

export type Where = "url" | "header" | "form" | "form-json" | "body-json";

export function isOpaque(v: string): boolean {
  if (v.length < MIN_LENGTH || !TOKEN_CHARS.test(v)) return false;
  if (v.includes("!T:") || /^!R\**!$/.test(v) || /\*{4,}/.test(v)) return false;
  if (v.startsWith("/") || v.includes("://") || /^[a-z0-9-]+(\.[a-z0-9-]+)*\.(com|net|org)$/i.test(v)) return false;
  return true;
}

export function shapeOf(v: string): string {
  if (/^\d+$/.test(v)) return "digits";
  if (/^[0-9a-f]+$/.test(v)) return "hex";
  if (/^[0-9A-F]+$/.test(v)) return "HEX";
  if (/^[A-Za-z0-9_-]+$/.test(v)) return "base64url";
  if (/^[A-Za-z0-9+/]+=*$/.test(v)) return "base64";
  if (/^[A-Za-z0-9+/=_-]+(:[A-Za-z0-9+/=_-]+)+$/.test(v)) return "colon-separated";
  return "mixed";
}

interface KeyStats {
  key: string;
  where: Set<Where>;
  occurrences: number;
  values: Map<string, Set<string>>; // value -> record ids
  lengths: number[];
  shapes: Map<string, number>;
}

/** Keyed JSON string values in text, at any escaping level: [key, raw value]. */
export function* jsonPairs(text: string): Generator<[string, string]> {
  const re = /(\\*)"([A-Za-z0-9_$.@-]{1,80})\1"\s*:\s*\1"/g;
  for (let m = re.exec(text); m !== null; m = re.exec(text)) {
    const level = m[1]!.length;
    const start = m.index + m[0].length;
    const end = findStringEnd(text, start, '"', level);
    if (end < 0) continue;
    re.lastIndex = end;
    yield [m[2]!, unescapeLevels(text.slice(start, end), level)];
  }
}

function unescapeLevels(raw: string, level: number): string {
  let s = raw;
  for (let i = 0, n = level === 0 ? 1 : Math.round(Math.log2(level + 1)) + 1; i < n && s.includes("\\"); i++) {
    try {
      s = JSON.parse(`"${s}"`) as string;
    } catch {
      break;
    }
  }
  return s;
}

export interface ScanRow {
  key: string;
  where: Where[];
  occurrences: number;
  distinctValues: number;
  recurringValues: number;
  maxRecords: number;
  minLength: number;
  maxLength: number;
  shapes: Record<string, number>;
}

/** Key names that look credential-like; listed with every opaque value, recurring or not. */
export const SUSPICIOUS_NAME = /token|secret|auth|sess|key|sig|nonce|pass|cred|dtsg|lsd|csrf|xsrf|cookie|ticket|hash|uuid|device|machine|client_?id|cid$|^sid$|crypt|cat$|cert|private|bearer/i;

export function scanSession(dir: string): { rows: ScanRow[]; suspicious: ScanRow[]; records: number } {
  const s = loadSession(dir);
  const lines = parseLines(s.eventsText);
  const keys = new Map<string, KeyStats>();
  const add = (key: string, where: Where, value: string, record: string): void => {
    if (!isOpaque(value)) return;
    const id = `${where === "header" ? "h" : "k"}:${key}`;
    let k = keys.get(id);
    if (k === undefined) {
      k = { key: where === "header" ? `header ${key.toLowerCase()}` : key, where: new Set(), occurrences: 0, values: new Map(), lengths: [], shapes: new Map() };
      keys.set(id, k);
    }
    k.where.add(where);
    k.occurrences++;
    const recs = k.values.get(value) ?? new Set<string>();
    recs.add(record);
    k.values.set(value, recs);
    k.lengths.push(value.length);
    const sh = shapeOf(value);
    k.shapes.set(sh, (k.shapes.get(sh) ?? 0) + 1);
  };
  const records = new Set<string>();
  for (const l of lines as SessionLine[]) {
    if (l.rid === "") continue;
    records.add(l.rid);
    if (l.ev === "request" && typeof l.d?.["url"] === "string") {
      try {
        const u = new URL(l.d["url"] as string);
        for (const [k, v] of u.searchParams) add(k, "url", v, l.rid);
      } catch {
        // not a URL
      }
    }
    if ((l.ev === "sendHeaders" || l.ev === "headers") && Array.isArray(l["headers"])) {
      for (const h of l["headers"] as { name: string; value?: string }[]) {
        if (typeof h.value !== "string" || /^(cookie|set-cookie)$/i.test(h.name)) continue;
        add(h.name, "header", h.value, l.rid);
      }
    }
    const body = l["body"] as { kind?: string; fields?: [string, string | null][]; text?: string } | undefined;
    if (l.ev === "request" && body?.kind === "formData") {
      for (const [k, v] of body.fields ?? []) {
        if (typeof v !== "string") continue;
        add(k, "form", v, l.rid);
        if (/^[[{]/.test(v)) for (const [jk, jv] of jsonPairs(v)) add(jk, "form-json", jv, l.rid);
      }
    }
    if (l.ev === "request" && body?.kind === "raw" && typeof body.text === "string") for (const [jk, jv] of jsonPairs(body.text)) add(jk, "form-json", jv, l.rid);
  }
  for (const l of lines) {
    if (l.ev !== "body" || typeof l["file"] !== "string") continue;
    const data = s.bodies.get(l["file"] as string);
    if (data === undefined) continue;
    for (const [k, v] of jsonPairs(latin1ToText(data))) add(k, "body-json", v, `body:${l.rid}`);
  }
  const rows: ScanRow[] = [];
  const suspicious: ScanRow[] = [];
  for (const k of keys.values()) {
    const recurring = [...k.values.values()].filter((r) => r.size >= 2);
    const bare = k.key.replace(/^header /, "");
    if (SUSPICIOUS_NAME.test(bare)) suspicious.push(row(k, recurring));
    if (recurring.length === 0) continue;
    rows.push(row(k, recurring));
  }
  const order = (a: ScanRow, b: ScanRow): number => b.maxRecords - a.maxRecords || b.occurrences - a.occurrences || a.key.localeCompare(b.key);
  rows.sort(order);
  suspicious.sort(order);
  return { rows, suspicious, records: records.size };
}

function row(k: KeyStats, recurring: Set<string>[]): ScanRow {
  return {
    key: k.key,
    where: [...k.where].sort(),
    occurrences: k.occurrences,
    distinctValues: k.values.size,
    recurringValues: recurring.length,
    maxRecords: recurring.length === 0 ? 1 : Math.max(...recurring.map((r) => r.size)),
    minLength: Math.min(...k.lengths),
    maxLength: Math.max(...k.lengths),
    shapes: Object.fromEntries(k.shapes),
  };
}

export function formatScan(dir: string, r: { rows: ScanRow[]; suspicious: ScanRow[]; records: number }): string {
  const out = [`opaque-value scan ${dir}: ${r.records} records, ${r.rows.length} keys with recurring opaque values (min length ${MIN_LENGTH})`];
  const head = "  maxRec  occur  distinct  recurring  len        where                 shapes                      key";
  out.push(head);
  const line = (x: ScanRow): void => {
    const len = x.minLength === x.maxLength ? `${x.minLength}` : `${x.minLength}-${x.maxLength}`;
    const shapes = Object.entries(x.shapes)
      .map(([k, v]) => `${k}:${v}`)
      .join(",");
    out.push(
      `  ${String(x.maxRecords).padStart(6)}  ${String(x.occurrences).padStart(5)}  ${String(x.distinctValues).padStart(8)}  ${String(x.recurringValues).padStart(9)}  ${len.padEnd(9)}  ${x.where.join(",").padEnd(20)}  ${shapes.padEnd(26)}  ${x.key}`,
    );
  };
  r.rows.forEach(line);
  out.push(`  credential-like key names with opaque values, recurring or not: ${r.suspicious.length}`);
  out.push(head);
  r.suspicious.forEach(line);
  return out.join("\n");
}

/** Parent keys that make every long string below them suspect. */
export const CREDENTIAL_ANCESTOR = /token|secret|auth|crypt|nonce|pass(word)?$|^pass|cred|dtsg|lsd|csrf|xsrf|cookie|ticket|signature|private|bearer|_cat$|session_?key/i;
/** Content tokens of the site's schema (pagination, rendering, tracking), reviewed in M2b: not credentials. */
export const CONTENT_TOKEN_KEYS = /tracking|^(story_token|legacy_token|expansion_token|intent_token|page_token|mediaset_token|sectionToken|rawSectionToken|collectionToken|notif_filter_token|uri_token|reference_token|client_vpv_token|privacy_mutation_token|selected_filter_tokens|client_token)$|^comet_comment_author_name_and_badges_renderer$|^author$|^author_group_membership$|Cookie|Authenticity|Password|Credentials?Dialog|\.react$|^LSD|^InitialCookieConsent$|authorization_hub/;

export interface AncestorRow {
  ancestor: string;
  key: string;
  count: number;
  lengths: number[];
}

/**
 * Long unredacted strings (16+ characters, no spaces, not URLs, not placeholders) anywhere
 * below a credential-like key, in every parsed response document of the session. Catches
 * secrets whose own key is generic ("data", "encrypted") but whose parent names them.
 */
export function ancestorScan(dir: string, parse: (dir: string) => unknown[]): AncestorRow[] {
  const rows = new Map<string, AncestorRow>();
  const visit = (x: unknown, anc: string[]): void => {
    if (Array.isArray(x)) for (const v of x) visit(v, anc);
    else if (typeof x === "object" && x !== null) for (const [k, v] of Object.entries(x)) visit(v, [...anc, k]);
    else if (typeof x === "string" && x.length >= MIN_LENGTH && !/\s/.test(x) && !x.startsWith("http") && !x.includes("!T:") && !/^!R\**!$/.test(x)) {
      const flagged = anc.filter((a) => CREDENTIAL_ANCESTOR.test(a));
      if (flagged.length === 0 || anc.some((a) => CONTENT_TOKEN_KEYS.test(a))) return;
      const safe = (k: string): string => (/^[A-Za-z_$][A-Za-z0-9_$.]{0,79}$/.test(k) && !/\d{5,}/.test(k) ? k : "<key>");
      const id = `${safe(flagged[flagged.length - 1]!)} > ${safe(anc[anc.length - 1] ?? "")}`;
      const row = rows.get(id) ?? { ancestor: safe(flagged[flagged.length - 1]!), key: safe(anc[anc.length - 1] ?? ""), count: 0, lengths: [] };
      row.count++;
      if (!row.lengths.includes(x.length)) row.lengths.push(x.length);
      rows.set(id, row);
    }
  };
  for (const doc of parse(dir)) visit(doc, []);
  return [...rows.values()].sort((a, b) => b.count - a.count);
}
