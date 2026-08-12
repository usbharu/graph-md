import MarkdownIt from "markdown-it";

// @ts-ignore The GraphMD plugin is materialized from the bundled runtime.
import { graphMdPlugin } from "../vendor/markdown-it-graphmd.js";
import { site } from "./site";

export function renderMarkdown(source: string): string {
  const markdown = new MarkdownIt({ html: false, linkify: true, typographer: true });
  const slugCounts = new Map<string, number>();
  markdown.renderer.rules.heading_open = (tokens, index, options, _env, renderer) => {
    const token = tokens[index];
    const title = tokens[index + 1]?.content ?? "section";
    const base = title
      .normalize("NFKC")
      .toLowerCase()
      .replace(/[^\p{Letter}\p{Number}]+/gu, "-")
      .replace(/^-|-$/g, "") || "section";
    const count = slugCounts.get(base) ?? 0;
    slugCounts.set(base, count + 1);
    token.attrSet("id", count ? `${base}-${count + 1}` : base);
    token.attrJoin("class", "section-heading");
    return renderer.renderToken(tokens, index, options);
  };
  graphMdPlugin(markdown, {
    hrefTransform: (target: string) =>
      (site.routes as Record<string, string>)[target] ?? null,
  });
  return markdown.render(source);
}

export function extractHeadings(source: string) {
  const markdown = new MarkdownIt();
  const tokens = markdown.parse(source, {});
  const counts = new Map<string, number>();
  return tokens.flatMap((token, index) => {
    if (token.type !== "heading_open") return [];
    const level = Number(token.tag.slice(1));
    if (level < 2 || level > 3) return [];
    const title = tokens[index + 1]?.content ?? "";
    const base = title.normalize("NFKC").toLowerCase()
      .replace(/[^\p{Letter}\p{Number}]+/gu, "-").replace(/^-|-$/g, "") || "section";
    const count = counts.get(base) ?? 0;
    counts.set(base, count + 1);
    return [{ level, title, id: count ? `${base}-${count + 1}` : base }];
  });
}
