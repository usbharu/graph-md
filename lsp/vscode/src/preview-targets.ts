import * as path from "node:path";
import { relativeMarkdownHref } from "./preview-links";

export interface PreviewSource {
  uri: string;
  filePath: string;
  scheme: string;
  authority: string;
  workspaceRelativePaths: readonly string[];
}

interface PreviewTarget extends PreviewSource {
  id: string;
  mediaUrl: string | null;
}

export interface PreviewTargetIndex {
  readonly documentsById: ReadonlyMap<string, readonly PreviewTarget[]>;
  readonly mediaByAlias: ReadonlyMap<string, readonly PreviewTarget[]>;
}

export async function loadPreviewTargetIndex(
  sources: readonly PreviewSource[],
  readText: (source: PreviewSource) => Promise<string>,
): Promise<PreviewTargetIndex> {
  const targets = (await Promise.all(sources.map(async (source) => {
    let text: string;
    try {
      text = await readText(source);
    } catch {
      // A file can be renamed or deleted after findFiles returns it.
      return null;
    }
    const kind = parseFrontMatterScalar(text, "kind");
    if (kind !== "Node" && kind !== "Media") return null;

    const id = parseFrontMatterScalar(text, "id");
    if (!id) return null;
    const mediaUrl = kind === "Media" ? parseFrontMatterScalar(text, "url") : null;
    return { ...source, id, mediaUrl };
  }))).filter((target): target is PreviewTarget => target !== null);

  const documentsById = groupTargets(targets.map((target) => [target.id, target] as const));
  const mediaAliases = targets.flatMap((target) => {
    const aliases = new Set([target.id]);
    if (target.mediaUrl) {
      [
        target.uri,
        target.filePath,
        path.basename(target.filePath),
        path.basename(target.filePath, path.extname(target.filePath)),
        ...target.workspaceRelativePaths.flatMap((relative) => [relative, `./${relative}`]),
      ].forEach((alias) => aliases.add(alias));
    }
    return [...aliases].map((alias) => [alias, target] as const);
  });

  return {
    documentsById,
    mediaByAlias: groupTargets(mediaAliases),
  };
}

export function resolveDocumentPreviewHref(
  index: PreviewTargetIndex,
  targetId: string,
  currentDocument: Pick<PreviewSource, "filePath" | "scheme" | "authority"> | undefined,
): string | null {
  const candidates = index.documentsById.get(targetId);
  if (candidates?.length !== 1) return null;

  const target = candidates[0];
  if (target.mediaUrl) return target.mediaUrl;
  if (
    !currentDocument ||
    target.scheme !== currentDocument.scheme ||
    target.authority !== currentDocument.authority
  ) {
    return null;
  }
  return relativeMarkdownHref(currentDocument.filePath, target.filePath);
}

export function resolveMediaPreviewHref(index: PreviewTargetIndex, href: string): string | null {
  for (const alias of mediaAliasCandidates(href)) {
    const targets = index.mediaByAlias.get(alias);
    if (targets?.length === 1) return targets[0].mediaUrl;
    if (targets && targets.length > 1) return null;
  }
  return null;
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

function groupTargets(
  entries: ReadonlyArray<readonly [string, PreviewTarget]>,
): ReadonlyMap<string, readonly PreviewTarget[]> {
  const grouped = new Map<string, Map<string, PreviewTarget>>();
  for (const [key, target] of entries) {
    const candidates = grouped.get(key) ?? new Map<string, PreviewTarget>();
    candidates.set(target.uri, target);
    grouped.set(key, candidates);
  }
  return new Map(
    [...grouped].map(([key, candidates]) => [
      key,
      [...candidates.values()].sort((left, right) => left.uri.localeCompare(right.uri)),
    ]),
  );
}

function mediaAliasCandidates(href: string): string[] {
  const aliases = new Set<string>([href]);
  try {
    const decoded = decodeURIComponent(href);
    aliases.add(decoded);
    aliases.add(path.basename(decoded));
  } catch {
    aliases.add(path.basename(href));
  }
  return [...aliases];
}
