// Small byte helpers shared by the stream filters. Everything works on Uint8Array so that
// untouched input can be forwarded byte for byte.

export const EMPTY = new Uint8Array(0);

/** Concatenates chunks into one new array. Returns EMPTY for no bytes. */
export function concatBytes(chunks: readonly Uint8Array[]): Uint8Array {
  let length = 0;
  for (const c of chunks) length += c.length;
  if (length === 0) return EMPTY;
  if (chunks.length === 1) return chunks[0]!.slice();
  const out = new Uint8Array(length);
  let offset = 0;
  for (const c of chunks) {
    out.set(c, offset);
    offset += c.length;
  }
  return out;
}

/**
 * Finds `needle` (lower-case ASCII) in `haystack` from `from`, ignoring ASCII case.
 * Returns -1 if absent.
 */
export function indexOfAsciiCaseInsensitive(haystack: Uint8Array, needle: string, from = 0): number {
  const n = needle.length;
  if (n === 0) return from;
  const first = needle.charCodeAt(0);
  const last = haystack.length - n;
  outer: for (let i = Math.max(0, from); i <= last; i++) {
    if (lowerAscii(haystack[i]!) !== first) continue;
    for (let j = 1; j < n; j++) {
      if (lowerAscii(haystack[i + j]!) !== needle.charCodeAt(j)) continue outer;
    }
    return i;
  }
  return -1;
}

function lowerAscii(b: number): number {
  return b >= 0x41 && b <= 0x5a ? b + 0x20 : b;
}

/** Decodes bytes one to one into a string (each byte becomes U+0000..U+00FF). For ASCII inspection only. */
export function bytesToLatin1(bytes: Uint8Array): string {
  let s = "";
  const step = 8192;
  for (let i = 0; i < bytes.length; i += step) {
    s += String.fromCharCode(...bytes.subarray(i, i + step));
  }
  return s;
}

/** A short printable sample of a byte range, for error reports. */
export function sampleOf(bytes: Uint8Array, max = 120): string {
  const text = new TextDecoder("utf-8", { fatal: false }).decode(bytes.subarray(0, max));
  return bytes.length > max ? `${text}…` : text;
}
