import { escapeAttr, findBalanced } from "./scan";
import { parsePropsDirectiveJson } from "./core-bindings";

const AT = 0x40;
const SPACE = 0x20;
const TAB = 0x09;
const LBRACE = 0x7b;

export interface PropsTokenMeta {
  props: string | null;
}

function safePropsJson(content: string): string | null {
  try {
    return parsePropsDirectiveJson(content);
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
  if (src[p] === "(") {
    const closeArgs = findBalanced(src, p, src.length, 0x28, 0x29);
    if (closeArgs < 0) return false;
    p = closeArgs + 1;
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
    token.meta = { props: safePropsJson(src.slice(startPos, closeBrace + 1)) } satisfies PropsTokenMeta;
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
  if (src[p] === "(") {
    const closeArgs = findBalanced(src, p, end, 0x28, 0x29);
    if (closeArgs < 0) return false;
    p = closeArgs + 1;
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
    token.meta = { props: safePropsJson(src.slice(start, closeBrace + 1)) } satisfies PropsTokenMeta;
    token.content = "";
  }

  state.pos = closeBrace + 1;
  return true;
}

export function renderPropsBlock(tokens: any[], idx: number): string {
  const meta = (tokens[idx].meta ?? {}) as PropsTokenMeta;
  return renderBoundProps("div", meta.props);
}

export function renderPropsInline(tokens: any[], idx: number): string {
  const meta = (tokens[idx].meta ?? {}) as PropsTokenMeta;
  return renderBoundProps("span", meta.props);
}

function renderBoundProps(tag: "div" | "span", propsJson: string | null): string {
  if (!propsJson) return `<${tag} class="graphmd-props"></${tag}>`;
  let props: Record<string, unknown>;
  try {
    props = JSON.parse(propsJson) as Record<string, unknown>;
  } catch {
    return `<${tag} class="graphmd-props"></${tag}>`;
  }
  const values = Object.entries(props).map(([name, value]) => {
    return `<span data-props-name="${escapeAttr(name)}"><span class="graphmd-prop-value">${renderPropertyValue(value)}</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">${escapeText(name)}</sub></span></span>`;
  }).join("");
  return `<${tag} class="graphmd-props" data-props-bind="${escapeAttr(propsJson)}">${values}</${tag}>`;
}

function renderPropertyValue(value: unknown): string {
  if (!isPropertyAssertions(value)) {
    return escapeText(displayValue(value));
  }

  if (value.length === 1) {
    const entry = value[0];
    return `${escapeText(displayValue(entry.value))}${renderTimelines(entry)}`;
  }

  const entries = value.map((entry) => {
    return `${escapeText(displayArrayEntry(entry.value))}${renderTimelines(entry)}`;
  });
  return `[${entries.join(",")}]`;
}

function renderTimelines(entry: Record<string, unknown>): string {
  const timelines = displayTimelines(entry);
  return timelines.length > 0
    ? `<sup class="graphmd-prop-valid-time">${escapeText(timelines.join(","))}</sup>`
    : "";
}

function displayArrayEntry(value: unknown): string {
  const serialised = JSON.stringify(value);
  return serialised === undefined ? String(value) : serialised;
}

function displayValue(value: unknown): string {
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (isRecord(value) && typeof value.default === "string") {
    return value.default;
  }
  return JSON.stringify(value);
}

function displayTimelines(entry: Record<string, unknown>): string[] {
  if (!Array.isArray(entry.validTime)) return [];
  const timelines: string[] = [];
  for (const validTime of entry.validTime) {
    if (isRecord(validTime) && typeof validTime.timeline === "string" && !timelines.includes(validTime.timeline)) {
      timelines.push(validTime.timeline);
    }
  }
  return timelines;
}

function isPropertyAssertions(value: unknown): value is Record<string, unknown>[] {
  return Array.isArray(value) && value.every((entry) => isRecord(entry) && "value" in entry);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function escapeText(value: string): string {
  return value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}
