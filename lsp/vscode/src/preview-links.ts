import * as path from "node:path";

/**
 * Returns a Markdown href from the source document to the target document.
 * VS Code's Markdown preview resolves these relative links in the extension
 * host, where they can safely open files outside the preview webview.
 */
export function relativeMarkdownHref(sourceDocumentPath: string, targetDocumentPath: string): string {
  const relative = path.relative(path.dirname(sourceDocumentPath), targetDocumentPath).replaceAll(path.sep, "/");
  return relative.startsWith(".") ? relative : `./${relative}`;
}
