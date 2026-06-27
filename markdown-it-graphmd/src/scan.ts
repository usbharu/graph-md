const BACKSLASH = 0x5c;
const NEWLINE = 0x0a;
const QUOTE = 0x22;

/**
 * Finds the next unescaped occurrence of `target` (a char code) in `src`
 * within `[start, end)`. Returns -1 if not found, or if a newline is met
 * first (relation labels and targets must not span newlines).
 */
export function findUnescaped(src: string, target: number, start: number, end: number): number {
  let escaped = false;
  for (let i = start; i < end; i += 1) {
    const ch = src.charCodeAt(i);
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === BACKSLASH) {
      escaped = true;
      continue;
    }
    if (ch === NEWLINE) {
      return -1;
    }
    if (ch === target) {
      return i;
    }
  }
  return -1;
}

/**
 * Finds the index of the brace that closes the `{` at `openPos`, respecting
 * string literals and nested braces. Returns -1 if unbalanced.
 */
export function findBalanced(src: string, openPos: number, end: number, open = 0x7b, close = 0x7d): number {
  if (src.charCodeAt(openPos) !== open) {
    return -1;
  }
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = openPos; i < end; i += 1) {
    const ch = src.charCodeAt(i);
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === BACKSLASH) {
        escaped = true;
      } else if (ch === QUOTE) {
        inString = false;
      }
      continue;
    }
    if (ch === QUOTE) {
      inString = true;
      continue;
    }
    if (ch === open) {
      depth += 1;
    } else if (ch === close) {
      depth -= 1;
      if (depth === 0) {
        return i;
      }
    }
  }
  return -1;
}

export function escapeAttr(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
}
