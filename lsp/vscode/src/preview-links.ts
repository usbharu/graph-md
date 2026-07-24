import * as path from "node:path";

type PathImplementation = Pick<typeof path, "dirname" | "parse" | "relative" | "sep">;

/**
 * Returns a Markdown href from the source document to the target document.
 * VS Code's Markdown preview resolves these relative links in the extension
 * host, where they can safely open files outside the preview webview.
 * Returns null when both files do not share a filesystem root.
 */
export function relativeMarkdownHref(
  sourceDocumentPath: string,
  targetDocumentPath: string,
  pathImplementation: PathImplementation = path,
): string | null {
  const sourceRoot = pathImplementation.parse(sourceDocumentPath).root.toLowerCase();
  const targetRoot = pathImplementation.parse(targetDocumentPath).root.toLowerCase();
  if (sourceRoot !== targetRoot) return null;

  const relative = pathImplementation
    .relative(pathImplementation.dirname(sourceDocumentPath), targetDocumentPath)
    .split(pathImplementation.sep)
    .map((segment) => segment === "." || segment === ".." ? segment : encodeURIComponent(segment))
    .join("/");
  return relative.startsWith(".") ? relative : `./${relative}`;
}
