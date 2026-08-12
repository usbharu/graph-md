import type { MarkdownIt } from "markdown-it";
import {
  bodyBlockRule,
  renderBodyBlockBoundary,
  renderEmbedBlock,
  renderEmbedTable,
  type EmbedResolver,
  type EmbedResolution,
  type EmbedTable,
} from "./block-rule";
import { propsBlockRule, propsInlineRule, renderPropsBlock, renderPropsInline } from "./props-rule";
import { relationInlineRule, renderRelation } from "./relation-rule";

export interface GraphMdOptions {
  /**
   * Transform the relation target into the `href` value. Default keeps the raw
   * target identifier (spec §24). Use this to e.g. append `.html` for
   * static-site output. The optional render environment can be used when the
   * transformed href depends on the document currently being rendered.
   * Return `null` when the target is unresolved or ambiguous to render the
   * relation metadata and label without an `href`.
   */
  hrefTransform?: (target: string, relType: string, env?: unknown) => string | null;
  /** Resolve back-link IDs to their source document. Return null when unresolved. */
  embedHrefTransform?: (target: string, env?: unknown) => string | null;
  embedResolver?: EmbedResolver;
}

export type { EmbedResolver, EmbedResolution, EmbedTable } from "./block-rule";
export type { EmbedParts } from "./core-bindings";

/**
 * markdown-it plugin rendering Graph Markdown body syntax.
 *
 * - `@link(validTime=...){props}[label](target relType)` -> GraphMD relation anchor
 * - `@link[label](target relType)`                         -> property-less relation anchor
 * - `@props{ ... }`                                       -> visible bound property values
 */
export function graphMdPlugin(md: MarkdownIt, options: GraphMdOptions = {}): void {
  const embedHrefTransform = options.embedHrefTransform
    ?? ((target: string, env?: unknown) => options.hrefTransform?.(target, "", env) ?? null);
  md.inline.ruler.push("graphmd_relation", relationInlineRule);
  md.inline.ruler.push("graphmd_props_inline", propsInlineRule);
  md.block.ruler.before(
    "fence",
    "graphmd_body_block",
    bodyBlockRule,
    { alt: ["paragraph", "reference", "blockquote", "list"] },
  );
  md.block.ruler.before("paragraph", "graphmd_props_block", propsBlockRule);

  md.renderer.rules["graphmd_relation"] = (tokens, idx, _renderOptions, env) => renderRelation(tokens, idx, md, options, env);
  md.renderer.rules["graphmd_props_block"] = renderPropsBlock;
  md.renderer.rules["graphmd_props_inline"] = renderPropsInline;
  md.renderer.rules["graphmd_body_block_open"] = renderBodyBlockBoundary;
  md.renderer.rules["graphmd_body_block_close"] = renderBodyBlockBoundary;
  md.renderer.rules["graphmd_embed_block"] = (tokens, idx, _renderOptions, env) =>
    renderEmbedBlock(
      tokens,
      idx,
      md,
      options.embedResolver,
      embedHrefTransform,
      env,
    );
  md.renderer.rules["graphmd_embed_table"] = (tokens, idx, _renderOptions, env) =>
    renderEmbedTable(tokens, idx, embedHrefTransform, env);
}

export default graphMdPlugin;
