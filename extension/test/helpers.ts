// Shared helpers for the filter tests.
import { concatBytes } from "../src/lib/bytes";

export const enc = new TextEncoder();
export const dec = new TextDecoder("utf-8", { ignoreBOM: true });

export interface Pushable {
  push(chunk: Uint8Array): Uint8Array;
  end(): Uint8Array;
}

/** Feeds `input` cut at `cuts` (sorted byte offsets) and returns every output chunk. */
export function feed(filter: Pushable, input: Uint8Array, cuts: readonly number[]): Uint8Array[] {
  const outputs: Uint8Array[] = [];
  let prev = 0;
  for (const cut of [...cuts, input.length]) {
    outputs.push(filter.push(input.slice(prev, cut)));
    prev = cut;
  }
  outputs.push(filter.end());
  return outputs;
}

export function joined(outputs: readonly Uint8Array[]): string {
  return dec.decode(concatBytes(outputs));
}

/** Deterministic pseudo-random cut points. */
export function randomCuts(length: number, count: number, seed: number): number[] {
  let s = seed;
  const cuts = new Set<number>();
  while (cuts.size < Math.min(count, length - 1)) {
    s = (s * 1103515245 + 12345) & 0x7fffffff;
    cuts.add(1 + (s % (length - 1)));
  }
  return [...cuts].sort((a, b) => a - b);
}
