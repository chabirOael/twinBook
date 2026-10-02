// Streaming filter for newline-delimited JSON responses.
//
// Input is a sequence of byte chunks, output is a sequence of byte chunks. The filter splits
// on the newline byte (0x0A), which never occurs inside a multi-byte UTF-8 sequence, so chunk
// boundaries may fall anywhere: inside a line, inside a character, inside the guard prefix.
// Each complete line is decided as soon as it is complete and its output is returned by the
// same push() call; only the unfinished tail of the stream is held back.
//
// Guarantees:
// - A line the rule keeps is forwarded byte for byte, never re-serialized.
// - Blank lines are forwarded unchanged and are not documents.
// - An optional `for (;;);` guard before the first document is preserved in the output, also
//   when the first document is replaced or dropped.
// - Fail open: a line that is not valid UTF-8 or not valid JSON, or for which the rule throws,
//   is forwarded unchanged and reported through onError. The rest of the stream is still
//   filtered. An unexpected internal error switches the filter to pass-through for the rest
//   of the stream, still without losing or reordering bytes.

import { concatBytes, EMPTY, sampleOf } from "./bytes";

export type Decision =
  | { readonly action: "keep" }
  | { readonly action: "drop" }
  | { readonly action: "replace"; readonly value: unknown };

export const KEEP: Decision = Object.freeze({ action: "keep" });
export const DROP: Decision = Object.freeze({ action: "drop" });

/**
 * Decides what happens to one parsed document. `index` counts non-blank lines from 0, so the
 * first document of the response has index 0. The rule may mutate `doc` and return it in a
 * replace decision.
 */
export type DocumentRule = (doc: unknown, index: number) => Decision;

export interface FilterError {
  readonly kind: "utf8" | "parse" | "rule" | "internal";
  /** 0-based index of the non-blank line, or -1 for internal errors. */
  readonly index: number;
  readonly message: string;
  /** Start of the offending line, for diagnostics. */
  readonly sample: string;
}

export interface NdjsonFilterStats {
  bytesIn: number;
  bytesOut: number;
  /** Non-blank lines seen. */
  documents: number;
  kept: number;
  dropped: number;
  replaced: number;
  /** Lines passed through unchanged because of an error. */
  failedOpen: number;
  guard: boolean;
  passThrough: boolean;
}

export interface NdjsonFilterOptions {
  onError?: (error: FilterError) => void;
}

const NEWLINE = 0x0a;
const CR = 0x0d;
// The guard may follow a byte order mark and whitespace. Only checked on the first document.
const GUARD_PREFIX = /^\uFEFF?\s*for ?\(;;\);\s*/;
const BOM_PREFIX = /^\uFEFF/;

export class NdjsonStreamFilter {
  private readonly rule: DocumentRule;
  private readonly onError: ((error: FilterError) => void) | undefined;
  private readonly decoder = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });
  private readonly encoder = new TextEncoder();
  private pending: Uint8Array[] = [];
  private index = 0;
  private ended = false;
  readonly stats: NdjsonFilterStats = {
    bytesIn: 0,
    bytesOut: 0,
    documents: 0,
    kept: 0,
    dropped: 0,
    replaced: 0,
    failedOpen: 0,
    guard: false,
    passThrough: false,
  };

  constructor(rule: DocumentRule, options: NdjsonFilterOptions = {}) {
    this.rule = rule;
    this.onError = options.onError;
  }

  /** Feeds one chunk and returns the bytes that can be emitted now (possibly empty). */
  push(chunk: Uint8Array): Uint8Array {
    if (this.ended) throw new Error("push after end");
    this.stats.bytesIn += chunk.length;
    if (this.stats.passThrough) return this.count(chunk.slice());
    const out: Uint8Array[] = [];
    let start = 0;
    try {
      for (;;) {
        const nl = chunk.indexOf(NEWLINE, start);
        if (nl < 0) break;
        const piece = chunk.subarray(start, nl + 1);
        const line = this.pending.length === 0 ? piece : concatBytes([...this.pending, piece]);
        // The line's bytes leave `pending` and `start` only once it has been processed, so a
        // throw below still finds them there and the catch emits them unchanged.
        const processed = this.processLine(line);
        this.pending = [];
        start = nl + 1;
        out.push(processed);
      }
      if (start < chunk.length) this.pending.push(chunk.slice(start));
      return this.count(concatBytes(out));
    } catch (e) {
      // Not expected: processLine handles its own errors. Emit everything unprocessed as is.
      this.enterPassThrough(e);
      const rest = start < chunk.length ? [chunk.subarray(start)] : [];
      const raw = concatBytes([...out, ...this.pending, ...rest]);
      this.pending = [];
      return this.count(raw);
    }
  }

  /** Flushes the last line, which may have no trailing newline. */
  end(): Uint8Array {
    if (this.ended) return EMPTY;
    this.ended = true;
    if (this.pending.length === 0) return EMPTY;
    const line = concatBytes(this.pending);
    this.pending = [];
    if (this.stats.passThrough) return this.count(line);
    try {
      return this.count(this.processLine(line));
    } catch (e) {
      this.enterPassThrough(e);
      return this.count(line);
    }
  }

  private count(bytes: Uint8Array): Uint8Array {
    this.stats.bytesOut += bytes.length;
    return bytes;
  }

  private enterPassThrough(e: unknown): void {
    this.stats.passThrough = true;
    this.report({ kind: "internal", index: -1, message: errorMessage(e), sample: "" });
  }

  /** `line` includes its terminator, if any. Returns the bytes to emit for it. */
  private processLine(line: Uint8Array): Uint8Array {
    let bodyEnd = line.length;
    if (bodyEnd > 0 && line[bodyEnd - 1] === NEWLINE) bodyEnd--;
    if (bodyEnd > 0 && line[bodyEnd - 1] === CR) bodyEnd--;
    const body = line.subarray(0, bodyEnd);
    if (isBlank(body)) return line;

    const index = this.index++;
    this.stats.documents++;

    let text: string;
    try {
      text = this.decoder.decode(body);
    } catch (e) {
      return this.failOpen(line, { kind: "utf8", index, message: errorMessage(e), sample: sampleOf(body) });
    }

    // Prefix kept verbatim in the output: the guard (first document only) or a BOM.
    let prefix = "";
    const guard = index === 0 ? GUARD_PREFIX.exec(text) : null;
    if (guard !== null) {
      prefix = guard[0];
      this.stats.guard = true;
    } else {
      const bom = BOM_PREFIX.exec(text);
      if (bom !== null) prefix = bom[0];
    }

    let doc: unknown;
    try {
      doc = JSON.parse(prefix.length === 0 ? text : text.slice(prefix.length));
    } catch (e) {
      return this.failOpen(line, { kind: "parse", index, message: errorMessage(e), sample: sampleOf(body) });
    }

    let decision: Decision;
    try {
      decision = this.rule(doc, index);
    } catch (e) {
      return this.failOpen(line, { kind: "rule", index, message: errorMessage(e), sample: sampleOf(body) });
    }

    const prefixBytes = body.subarray(0, this.encoder.encode(prefix).length);
    switch (decision.action) {
      case "keep":
        this.stats.kept++;
        return line;
      case "drop":
        this.stats.dropped++;
        // Keep the guard in front of whatever document comes next.
        return guard !== null ? prefixBytes.slice() : EMPTY;
      case "replace": {
        let json: string | undefined;
        try {
          json = JSON.stringify(decision.value);
        } catch (e) {
          return this.failOpen(line, { kind: "rule", index, message: errorMessage(e), sample: sampleOf(body) });
        }
        if (json === undefined) {
          return this.failOpen(line, { kind: "rule", index, message: "replacement is not JSON", sample: sampleOf(body) });
        }
        this.stats.replaced++;
        return concatBytes([prefixBytes, this.encoder.encode(json), line.subarray(bodyEnd)]);
      }
    }
  }

  private failOpen(line: Uint8Array, error: FilterError): Uint8Array {
    this.stats.failedOpen++;
    this.report(error);
    return line;
  }

  private report(error: FilterError): void {
    try {
      this.onError?.(error);
    } catch {
      // A failing error listener must not break the stream.
    }
  }
}

function isBlank(bytes: Uint8Array): boolean {
  for (const b of bytes) {
    if (b !== 0x20 && b !== 0x09 && b !== CR) return false;
  }
  return true;
}

export function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
