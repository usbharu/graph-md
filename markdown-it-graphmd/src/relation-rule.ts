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
}

/**
 * Inline rule for RelationLink: `@[label](target relType){props}`.
 * Bound to markdown-it via `md.inline.ruler.push`. markdown-it's built-in
 * `escape` rule runs earlier, so `\@[...]` is consumed as literal `@` and
 * never reaches this rule.
 */
export function relationInlineRule(state: any, silent: boolean): boolean {
  const src: string = state.src;
  const start = state.pos;
  const end = state.posMax;

  if (src.charCodeAt(start) !== AT || src.charCodeAt(start + 1) !== LBRACKET) {
    return false;
  }

  const closeLabel = findUnescaped(src, RBRACKET, start + 2, end);
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

  const label = unescapeLabel(src.slice(start + 2, closeLabel));

  let propsJson: string | null = null;
  let last = closeParen + 1;
  if (src.charCodeAt(last) === LBRACE) {
    const closeBrace = findBalanced(src, last, end);
    if (closeBrace > last) {
      try {
        propsJson = parseInlineObjectJson(src.slice(last, closeBrace + 1));
        last = closeBrace + 1;
      } catch {
        propsJson = null;
      }
    }
  }

  if (!silent) {
    const token = state.push("graphmd_relation", "a", 0);
    token.meta = { label, target: parts.target, relType: parts.relType, props: propsJson } satisfies RelationTokenMeta;
    token.content = label;
  }

  state.pos = last;
  return true;
}

export function renderRelation(tokens: any[], idx: number, md: MarkdownIt, options: { hrefTransform?: (target: string, relType: string) => string }): string {
  const meta = (tokens[idx].meta ?? {}) as RelationTokenMeta;
  const href = options.hrefTransform ? options.hrefTransform(meta.target, meta.relType) : meta.target;
  const esc = md.utils.escapeHtml;
  const attrs = [`href="${esc(href)}"`, `data-rel-type="${esc(meta.relType)}"`];
  if (meta.props) {
    attrs.push(`data-rel-props="${escapeAttr(meta.props)}"`);
  }
  return `<a ${attrs.join(" ")}>${esc(meta.label)}</a>`;
}
