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

  for (let cursor = closeBrace + 1; cursor < state.eMarks[lastLine]; cursor += 1) {
    const ch = src.charCodeAt(cursor);
    if (ch !== SPACE && ch !== TAB) {
      return false;
    }
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
  const variant = tag === "div" ? "block" : "inline";
  const classes = `graphmd-props graphmd-props--${variant}`;
  if (!propsJson) return `<${tag} class="${classes}"></${tag}>`;
  let props: Record<string, unknown>;
  try {
    props = JSON.parse(propsJson) as Record<string, unknown>;
  } catch {
    return `<${tag} class="${classes}"></${tag}>`;
  }
  const values = Object.entries(props).map(([name, value]) => {
    const hiddenLabel = variant === "inline" ? ' aria-hidden="true"' : "";
    return `<span class="graphmd-prop" data-props-name="${escapeAttr(name)}"><span class="graphmd-prop-name"${hiddenLabel}>${escapeText(name)}</span><span class="graphmd-prop-values">${renderPropertyValue(value)}</span></span>`;
  }).join("");
  return `<${tag} class="${classes}" data-props-bind="${escapeAttr(propsJson)}">${values}</${tag}>`;
}

function renderPropertyValue(value: unknown): string {
  if (!isPropertyAssertions(value)) {
    return renderAssertionValue(value, "plain");
  }

  const hasAlternatives = value.length > 1;
  return value.map((entry) => {
    const validities = renderValidities(entry);
    const kind = validities ? "temporal" : "default";
    const alternativeClass = hasAlternatives ? " graphmd-prop-assertion--alternative" : "";
    return `<span class="graphmd-prop-assertion graphmd-prop-assertion--${kind}${alternativeClass}">${renderAssertionValue(entry.value, kind)}${validities}</span>`;
  }).join("");
}

function renderAssertionValue(value: unknown, kind: "plain" | "default" | "temporal"): string {
  return `<span class="graphmd-prop-value graphmd-prop-value--${kind}">${renderHumanValue(value)}</span>`;
}

function renderValidities(entry: Record<string, unknown>): string {
  if (!Array.isArray(entry.validTime)) return "";
  const seen = new Set<string>();
  const validities = entry.validTime.flatMap((validTime) => {
    if (!isRecord(validTime) || typeof validTime.timeline !== "string") return [];
    const key = JSON.stringify(validTime);
    if (seen.has(key)) return [];
    seen.add(key);

    const hasRange = "from" in validTime || "to" in validTime;
    const range = hasRange
      ? `<span class="graphmd-prop-valid-range"><span>${renderRangeEnd(validTime.from, "始点なし")}</span><span class="graphmd-prop-range-separator" aria-hidden="true">–</span><span>${renderRangeEnd(validTime.to, "継続中")}</span></span>`
      : "";
    return [`<span class="graphmd-prop-validity"><span class="graphmd-prop-timeline">${escapeText(validTime.timeline)}</span>${range}</span>`];
  });
  return validities.length > 0 ? `<span class="graphmd-prop-validities">${validities.join("")}</span>` : "";
}

function renderRangeEnd(value: unknown, fallback: string): string {
  return value === undefined || value === null
    ? `<span class="graphmd-prop-open-time">${fallback}</span>`
    : renderHumanValue(value);
}

function renderHumanValue(value: unknown): string {
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return escapeText(String(value));
  }
  if (value === null || value === undefined) {
    return `<span class="graphmd-prop-empty">—</span>`;
  }
  if (Array.isArray(value)) {
    return `<span class="graphmd-prop-list">${value.map((item) => `<span>${renderHumanValue(item)}</span>`).join("")}</span>`;
  }
  if (isRecord(value)) {
    const entries = Object.entries(value);
    if (entries.length === 1 && entries[0][0] === "default") {
      return renderHumanValue(entries[0][1]);
    }
    return `<span class="graphmd-prop-fields">${entries.map(([key, fieldValue]) => `<span class="graphmd-prop-field"><span class="graphmd-prop-field-name">${escapeText(formatFieldName(key))}</span><span class="graphmd-prop-field-value">${renderHumanValue(fieldValue)}</span></span>`).join("")}</span>`;
  }
  return escapeText(String(value));
}

function formatFieldName(name: string): string {
  return name.startsWith("lang:") ? name.slice("lang:".length) : name;
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
