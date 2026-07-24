import type MarkdownIt from "markdown-it";
import { propsBlockRule, propsInlineRule, renderPropsBlock, renderPropsInline } from "./props-rule";
import { relationInlineRule, renderRelation } from "./relation-rule";

export interface GraphMdOptions {
  /**
   * Transform the relation target into the `href` value. Default keeps the raw
   * target identifier (spec §24). Use this to e.g. append `.html` for
   * static-site output. The optional render environment can be used when the
   * transformed href depends on the document currently being rendered.
   */
  hrefTransform?: (target: string, relType: string, env?: unknown) => string;
}

/**
 * markdown-it plugin rendering Graph Markdown body syntax.
 *
 * - `@link(validTime=...){props}[label](target relType)` -> GraphMD relation anchor
 * - `@link[label](target relType)`                         -> property-less relation anchor
 * - `@props{ ... }`                                       -> visible bound property values
 */
export function graphMdPlugin(md: MarkdownIt, options: GraphMdOptions = {}): void {
  md.inline.ruler.push("graphmd_relation", relationInlineRule);
  md.inline.ruler.push("graphmd_props_inline", propsInlineRule);
  md.block.ruler.before("paragraph", "graphmd_props_block", propsBlockRule);

  md.renderer.rules["graphmd_relation"] = (tokens, idx, _renderOptions, env) => renderRelation(tokens, idx, md, options, env);
  md.renderer.rules["graphmd_props_block"] = renderPropsBlock;
  md.renderer.rules["graphmd_props_inline"] = renderPropsInline;
}

export default graphMdPlugin;
