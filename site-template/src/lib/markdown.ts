import MarkdownIt from "markdown-it";

// @ts-ignore The GraphMD plugin is materialized from the bundled runtime.
import { graphMdPlugin } from "../vendor/markdown-it-graphmd.js";
import { site } from "./site";

export function renderMarkdown(source: string): string {
  const markdown = new MarkdownIt({ html: false, linkify: true });
  graphMdPlugin(markdown, {
    hrefTransform: (target: string) =>
      (site.routes as Record<string, string>)[target] ?? null,
  });
  return markdown.render(source);
}
