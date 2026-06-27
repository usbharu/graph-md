import type MarkdownIt from "markdown-it";
import { propsBlockRule, propsInlineRule, renderPropsBlock, renderPropsInline } from "./props-rule";
import { relationInlineRule, renderRelation } from "./relation-rule";

export interface GraphMdOptions {
  /**
   * Transform the relation target into the `href` value. Default keeps the raw
   * target identifier (spec §24). Use this to e.g. append `.html` for
   * static-site output.
   */
  hrefTransform?: (target: string, relType: string) => string;
}

/**
 * markdown-it plugin rendering Graph Markdown body syntax.
 *
 * - `@[label](target relType){props}` -> `<a href data-rel-type [data-props]>label</a>`
 * - `@props{ ... }`                   -> hidden element carrying `data-props`
 */
export function graphMdPlugin(md: MarkdownIt, options: GraphMdOptions = {}): void {
  md.inline.ruler.push("graphmd_relation", relationInlineRule);
  md.inline.ruler.push("graphmd_props_inline", propsInlineRule);
  md.block.ruler.before("paragraph", "graphmd_props_block", propsBlockRule);

  md.renderer.rules["graphmd_relation"] = (tokens, idx) => renderRelation(tokens, idx, md, options);
  md.renderer.rules["graphmd_props_block"] = renderPropsBlock;
  md.renderer.rules["graphmd_props_inline"] = renderPropsInline;
}

export default graphMdPlugin;
