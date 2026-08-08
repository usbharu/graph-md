import * as path from "node:path";

type PathImplementation = Pick<
  typeof path,
  "basename" | "dirname" | "parse" | "relative" | "sep"
>;

export interface PreviewDocumentUri {
  readonly scheme: string;
  readonly authority: string;
  readonly fsPath: string;
  toString(): string;
}

export interface PreviewEnvironment {
  readonly currentDocument?: PreviewDocumentUri;
}

export function resolveGraphMdHref(
  target: string,
  env: PreviewEnvironment | undefined,
  mediaTargets: ReadonlyMap<string, string>,
  documentTargets: ReadonlyMap<string, PreviewDocumentUri>,
  pathImplementation: PathImplementation = path,
): string {
  // Relation tokens have a custom renderer and never pass through link_open.
  // Resolve a Media ID here before it can become a Markdown document href.
  const mediaTarget = mediaTargets.get(target);
  if (mediaTarget) return mediaTarget;

  const targetUri = documentTargets.get(target);
  const currentDocument = env?.currentDocument;
  if (
    !targetUri ||
    !currentDocument ||
    targetUri.scheme !== currentDocument.scheme ||
    targetUri.authority !== currentDocument.authority
  ) {
    return target;
  }

  return relativeMarkdownHref(
    currentDocument.fsPath,
    targetUri.fsPath,
    pathImplementation,
  ) ?? target;
}

export function resolveGraphMdDocumentHref(
  target: string,
  env: PreviewEnvironment | undefined,
  documentTargets: ReadonlyMap<string, PreviewDocumentUri>,
  pathImplementation: PathImplementation = path,
): string | null {
  const targetUri = documentTargets.get(target);
  const currentDocument = env?.currentDocument;
  if (
    !targetUri ||
    !currentDocument ||
    targetUri.scheme !== currentDocument.scheme ||
    targetUri.authority !== currentDocument.authority
  ) {
    return null;
  }
  return relativeMarkdownHref(currentDocument.fsPath, targetUri.fsPath, pathImplementation);
}

export function resolveMediaHref(
  href: string,
  mediaTargets: ReadonlyMap<string, string>,
  pathImplementation: PathImplementation = path,
): string | null {
  const direct = mediaTargets.get(href);
  if (direct) return direct;
  try {
    const decoded = decodeURIComponent(href);
    return mediaTargets.get(decoded)
      ?? mediaTargets.get(pathImplementation.basename(decoded))
      ?? null;
  } catch {
    return mediaTargets.get(pathImplementation.basename(href)) ?? null;
  }
}

export function parseMediaFrontMatter(text: string): { id: string; url: string } | null {
  if (parseFrontMatterScalar(text, "kind") !== "Media") return null;
  const id = parseFrontMatterScalar(text, "id");
  const url = parseFrontMatterScalar(text, "url");
  return id && url ? { id, url } : null;
}

export function parseFrontMatterScalar(text: string, name: string): string | null {
  const normalized = text.replaceAll("\r\n", "\n");
  if (!normalized.startsWith("---\n")) return null;
  const end = normalized.indexOf("\n---", 4);
  if (end < 0) return null;
  const frontMatter = normalized.slice(4, end);
  const match = new RegExp(`^${name}:\\s*(.+?)\\s*$`, "m").exec(frontMatter);
  if (!match) return null;
  return match[1].replace(
    /^(?:"(.*)"|'(.*)')$/,
    (_all, doubleQuoted, singleQuoted) => doubleQuoted ?? singleQuoted,
  );
}

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
