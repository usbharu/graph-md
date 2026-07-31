import type MarkdownIt from "markdown-it";
import { escapeAttr, findBalanced, findUnescaped } from "./scan";
import { parseInlineObjectJson, parseRelationTargetAndType } from "./core-bindings";

const AT = 0x40;
const LBRACKET = 0x5b;
const RBRACKET = 0x5d;
const LPAREN = 0x28;
const RPAREN = 0x29;
const LBRACE = 0x7b;

function unescapeLabel(label: string): string {
  return label.replace(/\\]/g, "]").replace(/\\\\/g, "\\");
}

export interface RelationTokenMeta {
  label: string;
  target: string;
  relType: string;
  props: string | null;
  validTime: string | null;
}

/**
 * Inline rule for RelationLink: `@link(validTime=...){props}[label](target relType)`.
 * Bound to markdown-it via `md.inline.ruler.push`. markdown-it's built-in
 * `escape` rule runs earlier, so `\@link...` is consumed as literal `@` and
 * never reaches this rule.
 */
export function relationInlineRule(state: any, silent: boolean): boolean {
  const src: string = state.src;
  const start = state.pos;
  const end = state.posMax;

  if (src.charCodeAt(start) !== AT) {
    return false;
  }

  let linkStart: number;
  let propsJson: string | null = null;
  let validTime: string | null = null;
  if (src.startsWith("@link", start)) {
    let cursor = start + 5;
    if (src.charCodeAt(cursor) === LPAREN) {
      const closeArgs = findBalanced(src, cursor, end, LPAREN, RPAREN);
      if (closeArgs < 0) return false;
      const args = src.slice(cursor + 1, closeArgs).trim();
      const validTimeMatch = /^validTime\s*=\s*(.+)$/.exec(args);
      if (!validTimeMatch) return false;
      validTime = validTimeMatch[1].trim();
      cursor = closeArgs + 1;
    }
    if (src.charCodeAt(cursor) === LBRACE) {
      const closeProps = findBalanced(src, cursor, end);
      if (closeProps < 0) return false;
      try {
        propsJson = parseInlineObjectJson(src.slice(cursor, closeProps + 1));
        if (propsJson === "{}") propsJson = null;
      } catch {
        return false;
      }
      linkStart = closeProps + 1;
    } else {
      linkStart = cursor;
    }
    if (src.charCodeAt(linkStart) !== LBRACKET) return false;
  } else {
    return false;
  }

  const closeLabel = findUnescaped(src, RBRACKET, linkStart + 1, end);
  if (closeLabel < 0 || src.charCodeAt(closeLabel + 1) !== LPAREN) {
    return false;
  }

  const closeParen = findUnescaped(src, RPAREN, closeLabel + 2, end);
  if (closeParen < 0) {
    return false;
  }

  const parts = parseRelationTargetAndType(src.slice(closeLabel + 2, closeParen).trim());
  if (!parts) {
    return false;
  }

  const label = unescapeLabel(src.slice(linkStart + 1, closeLabel));

  let last = closeParen + 1;
  if (!silent) {
    const token = state.push("graphmd_relation", "a", 0);
    token.meta = { label, target: parts.target, relType: parts.relType, props: propsJson, validTime } satisfies RelationTokenMeta;
    token.content = label;
  }

  state.pos = last;
  return true;
}

export function renderRelation(
  tokens: any[],
  idx: number,
  md: MarkdownIt,
  options: { hrefTransform?: (target: string, relType: string, env?: unknown) => string | null },
  env?: unknown,
): string {
  const meta = (tokens[idx].meta ?? {}) as RelationTokenMeta;
  const href = options.hrefTransform ? options.hrefTransform(meta.target, meta.relType, env) : meta.target;
  const esc = md.utils.escapeHtml;
  const attrs = [`data-link-rel="${esc(meta.relType)}"`];
  if (href !== null) {
    attrs.unshift(`href="${esc(href)}"`);
  }
  if (meta.props) {
    attrs.push(`data-link-props="${escapeAttr(meta.props)}"`);
  }
  if (meta.validTime) {
    attrs.push(`data-link-valid-time="${escapeAttr(meta.validTime)}"`);
  }
  return `<a ${attrs.join(" ")}>${esc(meta.label)}</a>`;
}
