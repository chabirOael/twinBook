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

/** Inverse of bytesToLatin1: chars U+0000..U+00FF back to bytes. */
export function latin1ToBytes(text: string): Uint8Array {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0xff;
  return out;
}

/** Base64 of bytes, in pieces so large inputs do not overflow the argument list. */
export function bytesToBase64(bytes: Uint8Array): string {
  return btoa(bytesToLatin1(bytes));
}

export function base64ToBytes(b64: string): Uint8Array {
  return latin1ToBytes(atob(b64));
}

/** Lower-case hex of bytes. */
export function hex(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += b.toString(16).padStart(2, "0");
  return s;
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes as Uint8Array<ArrayBuffer>)));
}
