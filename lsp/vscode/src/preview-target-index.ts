import * as path from "node:path";

type PathImplementation = Pick<typeof path, "basename" | "extname" | "isAbsolute" | "relative" | "sep">;

const excludedWorkspaceSegments = new Set(["node_modules", ".git", "build", "dist"]);

export interface PreviewIndexSource {
  uri: string;
  fsPath: string;
  aliases: readonly string[];
}

interface IndexedContent {
  source: PreviewIndexSource;
  text: string;
}

interface PreviewTargetCandidate {
  source: PreviewIndexSource;
  mediaUrl: string | null;
}

export interface PreviewTargetSnapshot {
  readonly documents: ReadonlyMap<string, PreviewIndexSource>;
  readonly media: ReadonlyMap<string, string>;
}

export class PreviewDiskReadTracker {
  private readonly generations = new Map<string, number>();

  begin(uri: string): number {
    const generation = (this.generations.get(uri) ?? 0) + 1;
    this.generations.set(uri, generation);
    return generation;
  }

  isCurrent(uri: string, generation: number): boolean {
    return this.generations.get(uri) === generation;
  }
}

export class PreviewWorkspaceScanTracker {
  private latest = 0;

  begin(): number {
    this.latest += 1;
    return this.latest;
  }

  cancel(): void {
    this.latest += 1;
  }

  isCurrent(scan: number): boolean {
    return this.latest === scan;
  }

  beginRead(scan: number, uri: string, diskReads: PreviewDiskReadTracker): number | null {
    if (!this.isCurrent(scan)) return null;
    return diskReads.begin(uri);
  }
}

export type WorkspaceScanReadResult<T> =
  | { kind: "content"; value: T }
  | { kind: "missing" }
  | { kind: "preserve" }
  | { kind: "stale" };

export async function readWorkspaceScanEntry<T>(
  scan: number,
  uri: string,
  scans: PreviewWorkspaceScanTracker,
  diskReads: PreviewDiskReadTracker,
  read: () => PromiseLike<T>,
  isMissing: (error: unknown) => boolean,
  maxAttempts = 2,
): Promise<WorkspaceScanReadResult<T>> {
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const generation = scans.beginRead(scan, uri, diskReads);
    if (generation === null) return { kind: "stale" };
    try {
      const value = await read();
      if (!scans.isCurrent(scan) || !diskReads.isCurrent(uri, generation)) {
        return { kind: "stale" };
      }
      return { kind: "content", value };
    } catch (error) {
      if (!scans.isCurrent(scan) || !diskReads.isCurrent(uri, generation)) {
        return { kind: "stale" };
      }
      if (isMissing(error)) return { kind: "missing" };
      if (attempt + 1 === maxAttempts) return { kind: "preserve" };
    }
  }
  return { kind: "preserve" };
}

/**
 * Maintains the preview's small ID index. Open editor contents are kept as a
 * separate overlay so a disk notification can never replace an unsaved edit.
 */
export class PreviewTargetIndex {
  private readonly disk = new Map<string, IndexedContent>();
  private readonly overlays = new Map<string, IndexedContent>();
  private current: PreviewTargetSnapshot = {
    documents: new Map(),
    media: new Map(),
  };

  get snapshot(): PreviewTargetSnapshot {
    return this.current;
  }

  setDisk(source: PreviewIndexSource, text: string): boolean {
    this.disk.set(source.uri, { source, text });
    return this.rebuild();
  }

  replace(
    diskContents: ReadonlyArray<{ source: PreviewIndexSource; text: string }>,
    overlayContents: ReadonlyArray<{ source: PreviewIndexSource; text: string }>,
    preserveDiskSources: readonly PreviewIndexSource[] = [],
  ): boolean {
    const preserved = preserveDiskSources.flatMap((source) => {
      const previous = this.disk.get(source.uri);
      return previous ? [{ source, text: previous.text }] : [];
    });
    this.disk.clear();
    this.overlays.clear();
    for (const content of diskContents) this.disk.set(content.source.uri, content);
    for (const content of preserved) {
      if (!this.disk.has(content.source.uri)) this.disk.set(content.source.uri, content);
    }
    for (const content of overlayContents) this.overlays.set(content.source.uri, content);
    return this.rebuild();
  }

  deleteDisk(uri: string): boolean {
    if (!this.disk.delete(uri)) return false;
    return this.rebuild();
  }

  setOverlay(source: PreviewIndexSource, text: string): boolean {
    this.overlays.set(source.uri, { source, text });
    return this.rebuild();
  }

  deleteOverlay(uri: string): boolean {
    if (!this.overlays.delete(uri)) return false;
    return this.rebuild();
  }

  deleteAll(uri: string): boolean {
    const diskChanged = this.disk.delete(uri);
    const overlayChanged = this.overlays.delete(uri);
    return (diskChanged || overlayChanged) && this.rebuild();
  }

  private rebuild(): boolean {
    const effective = new Map(this.disk);
    this.overlays.forEach((content, uri) => effective.set(uri, content));

    // A preview can only link to one document. Sorting makes candidate
    // collection deterministic, while ambiguous IDs and aliases are omitted
    // below instead of resolving to an arbitrary candidate.
    const contents = [...effective.values()].sort((left, right) =>
      left.source.uri < right.source.uri ? -1 : left.source.uri > right.source.uri ? 1 : 0);
    const documentCandidates = new Map<string, Map<string, PreviewTargetCandidate>>();
    const mediaCandidates = new Map<string, Map<string, PreviewTargetCandidate>>();

    for (const content of contents) {
      const kind = parseFrontMatterScalar(content.text, "kind");
      if (kind !== "Node" && kind !== "Media") continue;
      const documentId = parseFrontMatterScalar(content.text, "id");
      if (!documentId) continue;
      const metadata = kind === "Media" ? parseMediaFrontMatter(content.text) : null;
      const candidate = { source: content.source, mediaUrl: metadata?.url ?? null };
      addCandidate(documentCandidates, documentId, candidate);

      const aliases = new Set([documentId]);
      if (metadata) {
        [content.source.uri, content.source.fsPath, ...content.source.aliases]
          .forEach((alias) => aliases.add(alias));
      }
      for (const alias of aliases) {
        addCandidate(mediaCandidates, alias, candidate);
      }
    }

    const documents = uniqueSources(documentCandidates);
    const media = new Map<string, string>();
    for (const [alias, candidates] of mediaCandidates) {
      if (candidates.size !== 1) continue;
      const candidate = [...candidates.values()][0];
      if (candidate.mediaUrl !== null) media.set(alias, candidate.mediaUrl);
    }

    if (mapsEqual(this.current.documents, documents, sourceEqual) &&
      mapsEqual(this.current.media, media, (left, right) => left === right)) {
      return false;
    }
    this.current = { documents, media };
    return true;
  }
}

export function isIndexedMarkdownPath(
  filePath: string,
  workspaceRootPaths: readonly string[],
  pathImplementation: PathImplementation = path,
): boolean {
  if (pathImplementation.extname(filePath) !== ".md") return false;
  return workspaceRootPaths.some((root) => {
    const relative = pathImplementation.relative(root, filePath);
    if (!isCoveredRelativePath(relative, pathImplementation)) return false;
    return !relative.split(pathImplementation.sep).some((segment) => excludedWorkspaceSegments.has(segment));
  });
}

export function previewIndexAliases(
  filePath: string,
  workspaceRootPaths: readonly string[],
  pathImplementation: PathImplementation = path,
): string[] {
  const aliases = new Set([
    pathImplementation.basename(filePath),
    pathImplementation.basename(filePath, pathImplementation.extname(filePath)),
  ]);
  for (const root of workspaceRootPaths) {
    const relative = pathImplementation.relative(root, filePath);
    if (!isCoveredRelativePath(relative, pathImplementation)) continue;
    if (relative.split(pathImplementation.sep).some((segment) => excludedWorkspaceSegments.has(segment))) continue;
    const normalized = relative.split(pathImplementation.sep).join("/");
    aliases.add(normalized);
    aliases.add(`./${normalized}`);
  }
  return [...aliases];
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
  return match[1].replace(/^(?:"(.*)"|'(.*)')$/, (_all, doubleQuoted, singleQuoted) =>
    doubleQuoted ?? singleQuoted);
}

function addCandidate(
  groups: Map<string, Map<string, PreviewTargetCandidate>>,
  key: string,
  candidate: PreviewTargetCandidate,
): void {
  const candidates = groups.get(key) ?? new Map<string, PreviewTargetCandidate>();
  candidates.set(candidate.source.uri, candidate);
  groups.set(key, candidates);
}

function uniqueSources(
  groups: Map<string, Map<string, PreviewTargetCandidate>>,
): Map<string, PreviewIndexSource> {
  const unique = new Map<string, PreviewIndexSource>();
  for (const [key, candidates] of groups) {
    if (candidates.size === 1) {
      unique.set(key, [...candidates.values()][0].source);
    }
  }
  return unique;
}

function sourceEqual(left: PreviewIndexSource, right: PreviewIndexSource): boolean {
  return left.uri === right.uri && left.fsPath === right.fsPath;
}

function mapsEqual<T>(
  left: ReadonlyMap<string, T>,
  right: ReadonlyMap<string, T>,
  valueEqual: (left: T, right: T) => boolean,
): boolean {
  if (left.size !== right.size) return false;
  for (const [key, value] of left) {
    const other = right.get(key);
    if (other === undefined || !valueEqual(value, other)) return false;
  }
  return true;
}

function isCoveredRelativePath(relative: string, pathImplementation: PathImplementation): boolean {
  return relative !== "" &&
    relative !== ".." &&
    !relative.startsWith(`..${pathImplementation.sep}`) &&
    !pathImplementation.isAbsolute(relative);
}
