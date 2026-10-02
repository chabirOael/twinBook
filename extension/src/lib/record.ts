// Pure helpers for capture records: which bodies are recorded, how webRequest details become
// a record line, and the body recorder with its size cap and chunk log. docs/CAPTURE.md
// describes the resulting format.

import { concatBytes } from "./bytes";
import type { Redactor } from "./redact";

/** Version of the capture format written by this code (session.json "format"). */
export const FORMAT_VERSION = 1;

/** Response bodies are recorded up to this many bytes; the rest is counted, not stored. */
export const BODY_CAP_BYTES = 8 * 1024 * 1024;

/** Non-form request bodies are recorded up to this many bytes. */
export const REQUEST_BODY_CAP_BYTES = 256 * 1024;

/** At most this many chunk entries are logged per response. */
export const MAX_CHUNK_LOG = 20_000;

/** Size of one body piece sent to the app (before base64). */
export const BODY_PIECE_BYTES = 192 * 1024;

const TEXTUAL = /^\s*(text\/[\w.+-]+|application\/(json|x-javascript|javascript|ecmascript|x-ecmascript|xml|xhtml\+xml|x-www-form-urlencoded|graphql|x-ndjson|ndjson|[\w.-]+\+json|[\w.-]+\+xml))\s*(;|$)/i;

export function isTextualContentType(contentType: string | undefined): boolean {
  return contentType !== undefined && TEXTUAL.test(contentType);
}

/**
 * Response bodies recorded for a profile's own hosts: main documents and frames, and XHR or
 * fetch responses, with a textual content type. Scripts, styles, images, media, fonts, beacons
 * and everything else are metadata only.
 */
export function shouldRecordResponseBody(type: string, contentType: string | undefined): boolean {
  return (type === "main_frame" || type === "sub_frame" || type === "xmlhttprequest") && isTextualContentType(contentType);
}

export function headerValue(headers: readonly { name: string; value?: string | undefined }[] | undefined, name: string): string | undefined {
  const lower = name.toLowerCase();
  return headers?.find((h) => h.name.toLowerCase() === lower)?.value;
}

const SKIPPED = new Set(["requestHeaders", "responseHeaders", "requestBody"]);
const URL_FIELDS = new Set(["url", "originUrl", "documentUrl", "redirectUrl"]);

/**
 * Every field of a webRequest details object except headers and bodies (recorded separately),
 * with URLs redacted. Keeping every field means anything Gecko reports about caches, service
 * workers, proxies or classification is in the record without being named here.
 */
export function pickDetails(details: object, redactor: Redactor): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(details)) {
    if (SKIPPED.has(k) || v === undefined || typeof v === "function") continue;
    if (URL_FIELDS.has(k) && typeof v === "string") out[k] = redactor.url(v);
    else if (k === "frameAncestors" && Array.isArray(v)) out[k] = v.map((f: { url?: string }) => ({ ...f, url: typeof f.url === "string" ? redactor.url(f.url) : f.url }));
    else out[k] = v;
  }
  return out;
}

export interface RecordedRequestBody {
  /** formData: parsed fields in order; raw: text of a non-form body; binary: size only. */
  kind: "formData" | "raw" | "binary" | "error" | "none";
  fields?: [string, string][];
  text?: string;
  bytes?: number;
  truncated?: boolean;
  error?: string;
}

interface RequestBodyLike {
  formData?: Record<string, string[]> | undefined;
  raw?: { bytes?: ArrayBuffer | undefined; file?: string | undefined }[] | undefined;
  error?: string | undefined;
}

/** A request body as recorded, layer 1 applied. */
export function recordRequestBody(body: RequestBodyLike | undefined | null, redactor: Redactor): RecordedRequestBody {
  if (body === undefined || body === null) return { kind: "none" };
  if (body.error !== undefined) return { kind: "error", error: body.error };
  if (body.formData !== undefined) {
    const fields: [string, string][] = [];
    for (const [name, values] of Object.entries(body.formData)) for (const v of values) fields.push([name, v]);
    return { kind: "formData", fields: redactor.formFields(fields) };
  }
  if (body.raw !== undefined) {
    const parts = body.raw.flatMap((p) => (p.bytes === undefined ? [] : [new Uint8Array(p.bytes)]));
    const all = concatBytes(parts);
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(all.subarray(0, REQUEST_BODY_CAP_BYTES));
    } catch {
      return { kind: "binary", bytes: all.length };
    }
    return { kind: "raw", text: redactor.text(text), bytes: all.length, truncated: all.length > REQUEST_BODY_CAP_BYTES };
  }
  return { kind: "none" };
}

/** Copies a response body as it streams: capped data and a log of chunk sizes and times. */
export class BodyRecorder {
  private readonly parts: Uint8Array[] = [];
  readonly cap: number;
  readonly startedAt: number;
  totalBytes = 0;
  recordedBytes = 0;
  truncated = false;
  /** [offset, length, ms since the response started] per chunk. */
  readonly chunks: [number, number, number][] = [];
  chunkCount = 0;

  constructor(startedAt: number, cap = BODY_CAP_BYTES) {
    this.startedAt = startedAt;
    this.cap = cap;
  }

  push(bytes: Uint8Array, now: number): void {
    this.chunkCount++;
    if (this.chunks.length < MAX_CHUNK_LOG) this.chunks.push([this.totalBytes, bytes.length, Math.round(now - this.startedAt)]);
    this.totalBytes += bytes.length;
    const room = this.cap - this.recordedBytes;
    if (room <= 0) {
      if (bytes.length > 0) this.truncated = true;
      return;
    }
    const take = Math.min(room, bytes.length);
    this.parts.push(take === bytes.length ? bytes : bytes.slice(0, take));
    this.recordedBytes += take;
    if (take < bytes.length) this.truncated = true;
  }

  data(): Uint8Array {
    return concatBytes(this.parts);
  }
}

/** File name of a request's response body inside the session directory. */
export function bodyFileName(requestId: string): string {
  return `bodies/${requestId.replace(/[^A-Za-z0-9_-]/g, "_")}.res`;
}
