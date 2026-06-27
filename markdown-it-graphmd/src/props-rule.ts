import { escapeAttr, findBalanced } from "./scan";
import { parseInlineObjectJson } from "./core-bindings";

const AT = 0x40;
const SPACE = 0x20;
const TAB = 0x09;
const LBRACE = 0x7b;

export interface PropsTokenMeta {
  props: string | null;
}

function safePropsJson(content: string): string | null {
  try {
    return parseInlineObjectJson(content);
  } catch {
    return null;
  }
}

/**
 * Block rule for `@props{ ... }` (single- or multi-line). Bound via
 * `md.block.ruler.before('paragraph', ...)`.
 */
export function propsBlockRule(state: any, startLine: number, endLine: number, silent: boolean): boolean {
  if (state.sCount[startLine] - state.blkIndent >= 4) {
    return false;
  }

  const src: string = state.src;
  const startPos = state.bMarks[startLine] + state.tShift[startLine];

  if (src.charCodeAt(startPos) !== AT || !src.startsWith("@props", startPos)) {
    return false;
  }

  let p = startPos + "@props".length;
  const lineEnd = state.eMarks[startLine];
  while (p < lineEnd && (src.charCodeAt(p) === SPACE || src.charCodeAt(p) === TAB)) {
    p += 1;
  }
  if (src.charCodeAt(p) !== LBRACE) {
    return false;
  }

  const closeBrace = findBalanced(src, p, src.length);
  if (closeBrace < 0) {
    return false;
  }

  let lastLine = startLine;
  while (lastLine < endLine && state.eMarks[lastLine] < closeBrace) {
    lastLine += 1;
  }
  if (state.eMarks[lastLine] < closeBrace) {
    return false;
  }

  if (!silent) {
    const token = state.push("graphmd_props_block", "div", 0);
    token.block = true;
    token.map = [startLine, lastLine + 1];
    token.markup = "@props";
    token.meta = { props: safePropsJson(src.slice(p, closeBrace + 1)) } satisfies PropsTokenMeta;
  }

  state.line = lastLine + 1;
  return true;
}

/** Inline rule for `@props{ ... }` appearing within a paragraph. */
export function propsInlineRule(state: any, silent: boolean): boolean {
  const src: string = state.src;
  const start = state.pos;
  const end = state.posMax;

  if (src.charCodeAt(start) !== AT || !src.startsWith("@props", start)) {
    return false;
  }

  let p = start + "@props".length;
  while (p < end && (src.charCodeAt(p) === SPACE || src.charCodeAt(p) === TAB)) {
    p += 1;
  }
  if (src.charCodeAt(p) !== LBRACE) {
    return false;
  }

  const closeBrace = findBalanced(src, p, end);
  if (closeBrace < 0) {
    return false;
  }

  if (!silent) {
    const token = state.push("graphmd_props_inline", "span", 0);
    token.meta = { props: safePropsJson(src.slice(p, closeBrace + 1)) } satisfies PropsTokenMeta;
    token.content = "";
  }

  state.pos = closeBrace + 1;
  return true;
}

export function renderPropsBlock(tokens: any[], idx: number): string {
  const meta = (tokens[idx].meta ?? {}) as PropsTokenMeta;
  const props = meta.props ? ` data-props="${escapeAttr(meta.props)}"` : "";
  return `<div class="graphmd-props"${props} hidden></div>`;
}

export function renderPropsInline(tokens: any[], idx: number): string {
  const meta = (tokens[idx].meta ?? {}) as PropsTokenMeta;
  const props = meta.props ? ` data-props="${escapeAttr(meta.props)}"` : "";
  return `<span class="graphmd-props"${props} hidden></span>`;
}
