// Facebook prefixes JSON responses with an anti-hijacking guard, `for (;;);`, also seen
// without the space. The guard may follow leading whitespace.
const LEADING_GUARD = /^\s*for ?\(;;\);/;

/**
 * Removes one leading `for (;;);` or `for(;;);` guard, together with any whitespace before
 * it, and returns the rest. Returns the text unchanged when it does not start with a guard.
 */
export function stripJsonGuard(text: string): string {
  const match = LEADING_GUARD.exec(text);
  return match === null ? text : text.slice(match[0].length);
}
