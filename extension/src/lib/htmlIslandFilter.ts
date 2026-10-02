// Streaming filter for HTML documents with JSON islands:
//   <script type="application/json" ...>{ ... }</script>
//
// Works on bytes. Everything outside an island is forwarded byte for byte as soon as it is
// known not to start an island. An island is held until its closing tag arrives, parsed,
// handed to the transform and, only if the transform changed it, re-serialized. Fail open:
// an island that is not valid UTF-8 or JSON, or that the transform throws on, is forwarded
// unchanged and reported through onError.

import { bytesToLatin1, concatBytes, EMPTY, indexOfAsciiCaseInsensitive, sampleOf } from "./bytes";
import { errorMessage, type FilterError } from "./ndjsonFilter";

/** Mutates the parsed island in place; returns true if it changed anything. */
export type IslandTransform = (value: unknown, info?: { readonly bytes: number }) => boolean;

export interface HtmlFilterStats {
  bytesIn: number;
  bytesOut: number;
  islands: number;
  changed: number;
  failedOpen: number;
  passThrough: boolean;
}

export interface HtmlFilterOptions {
  onError?: (error: FilterError) => void;
  /** An island larger than this is passed through unparsed. Default 32 MiB. */
  maxIslandBytes?: number;
  /** Count changes the transform would make but forward every island unchanged. */
  observe?: boolean;
}

const OPEN = "<script";
const CLOSE = "</script";
const JSON_TYPE = /\btype\s*=\s*(["']?)application\/json\1(?=[\s>/])/i;

export class HtmlJsonIslandFilter {
  private readonly transform: IslandTransform;
  private readonly onError: ((error: FilterError) => void) | undefined;
  private readonly maxIslandBytes: number;
  readonly observe: boolean;
  private readonly encoder = new TextEncoder();
  private buffer: Uint8Array = EMPTY;
  private ended = false;
  readonly stats: HtmlFilterStats = {
    bytesIn: 0,
    bytesOut: 0,
    islands: 0,
    changed: 0,
    failedOpen: 0,
    passThrough: false,
  };

  constructor(transform: IslandTransform, options: HtmlFilterOptions = {}) {
    this.transform = transform;
    this.onError = options.onError;
    this.maxIslandBytes = options.maxIslandBytes ?? 32 * 1024 * 1024;
    this.observe = options.observe === true;
  }

  push(chunk: Uint8Array): Uint8Array {
    if (this.ended) throw new Error("push after end");
    this.stats.bytesIn += chunk.length;
    if (this.stats.passThrough) return this.count(chunk.slice());
    this.buffer = this.buffer.length === 0 ? chunk.slice() : concatBytes([this.buffer, chunk]);
    try {
      return this.count(this.drain(false));
    } catch (e) {
      return this.count(this.bail(e));
    }
  }

  end(): Uint8Array {
    if (this.ended) return EMPTY;
    this.ended = true;
    try {
      return this.count(this.drain(true));
    } catch (e) {
      return this.count(this.bail(e));
    }
  }

  private bail(e: unknown): Uint8Array {
    this.stats.passThrough = true;
    this.report({ kind: "internal", index: -1, message: errorMessage(e), sample: "" });
    const rest = this.buffer;
    this.buffer = EMPTY;
    return rest;
  }

  private count(bytes: Uint8Array): Uint8Array {
    this.stats.bytesOut += bytes.length;
    return bytes;
  }

  /** Emits what can be decided now and keeps the undecided tail in `buffer`. */
  private drain(final: boolean): Uint8Array {
    const buf = this.buffer;
    const out: Uint8Array[] = [];
    let pos = 0;
    for (;;) {
      const open = indexOfAsciiCaseInsensitive(buf, OPEN, pos);
      if (open < 0) {
        // Keep a tail that could be the start of "<script".
        const keepFrom = final ? buf.length : Math.max(pos, buf.length - (OPEN.length - 1));
        out.push(buf.subarray(pos, keepFrom));
        pos = keepFrom;
        break;
      }
      const tagEnd = buf.indexOf(0x3e /* > */, open);
      if (tagEnd < 0) {
        out.push(buf.subarray(pos, final ? buf.length : open));
        pos = final ? buf.length : open;
        break;
      }
      const tag = bytesToLatin1(buf.subarray(open, tagEnd + 1));
      const afterName = tag.charAt(OPEN.length);
      const isScriptTag = afterName === ">" || afterName === "/" || /\s/.test(afterName);
      if (!isScriptTag || !JSON_TYPE.test(tag)) {
        out.push(buf.subarray(pos, tagEnd + 1));
        pos = tagEnd + 1;
        continue;
      }
      const close = indexOfAsciiCaseInsensitive(buf, CLOSE, tagEnd + 1);
      if (close < 0) {
        if (final || buf.length - tagEnd > this.maxIslandBytes) {
          // Unterminated or oversized island: give up on the rest of the document.
          if (!final) this.stats.passThrough = true;
          out.push(buf.subarray(pos));
          pos = buf.length;
        } else {
          out.push(buf.subarray(pos, open));
          pos = open;
        }
        break;
      }
      out.push(buf.subarray(pos, tagEnd + 1));
      out.push(this.processIsland(buf.subarray(tagEnd + 1, close)));
      pos = close;
    }
    this.buffer = pos >= buf.length ? EMPTY : buf.slice(pos);
    return concatBytes(out);
  }

  private processIsland(content: Uint8Array): Uint8Array {
    const index = this.stats.islands++;
    let value: unknown;
    try {
      const text = new TextDecoder("utf-8", { fatal: true }).decode(content);
      value = JSON.parse(text);
    } catch (e) {
      return this.failOpen(content, { kind: "parse", index, message: errorMessage(e), sample: sampleOf(content) });
    }
    let changed: boolean;
    try {
      changed = this.transform(value, { bytes: content.length });
    } catch (e) {
      return this.failOpen(content, { kind: "rule", index, message: errorMessage(e), sample: sampleOf(content) });
    }
    if (!changed) return content;
    this.stats.changed++;
    if (this.observe) return content;
    // "<" escaped so the island can never close its own script element.
    return this.encoder.encode(JSON.stringify(value).replace(/</g, "\\u003c"));
  }

  private failOpen(content: Uint8Array, error: FilterError): Uint8Array {
    this.stats.failedOpen++;
    this.report(error);
    return content;
  }

  private report(error: FilterError): void {
    try {
      this.onError?.(error);
    } catch {
      // ignore listener failures
    }
  }
}
