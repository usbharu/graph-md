const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");
const {
  isIndexedMarkdownPath,
  previewIndexAliases,
  readWorkspaceScanEntry,
  PreviewDiskReadTracker,
  PreviewTargetIndex,
  PreviewWorkspaceScanTracker,
} = require("../src/preview-target-index.ts");
const { relativeMarkdownHref } = require("../src/preview-links.ts");

function source(name, aliases = []) {
  return {
    uri: `file:///workspace/${name}.md`,
    fsPath: `/workspace/${name}.md`,
    aliases: [`${name}.md`, name, ...aliases],
  };
}

function node(id) {
  return `---\nid: ${id}\nkind: Node\n---\n`;
}

function media(id, url) {
  return `---\nid: ${id}\nkind: Media\nurl: ${url}\n---\n`;
}

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

test("an open document overlay immediately replaces its disk ID", () => {
  const index = new PreviewTargetIndex();
  const alice = source("alice");

  assert.equal(index.setDisk(alice, node("Alice")), true);
  assert.equal(index.setOverlay(alice, node("Alicia")), true);
  assert.equal(index.snapshot.documents.has("Alice"), false);
  const target = index.snapshot.documents.get("Alicia");
  assert.equal(target.uri, alice.uri);
  assert.equal(relativeMarkdownHref("/workspace/source.md", target.fsPath), "./alice.md");
});

test("an unsaved Media ID and URL replace all disk aliases", () => {
  const index = new PreviewTargetIndex();
  const portrait = source("portrait", ["media/portrait.md", "./media/portrait.md"]);

  index.setDisk(portrait, media("AlicePortrait", "https://example.test/old.png"));
  assert.equal(index.setOverlay(
    portrait,
    media("UpdatedPortrait", "https://example.test/new.png"),
  ), true);

  assert.equal(index.snapshot.media.has("AlicePortrait"), false);
  assert.equal(index.snapshot.media.get("UpdatedPortrait"), "https://example.test/new.png");
  assert.equal(index.snapshot.media.get("portrait.md"), "https://example.test/new.png");
  assert.equal(index.snapshot.media.get("./media/portrait.md"), "https://example.test/new.png");
});

test("closing an unsaved document restores the indexed disk contents", () => {
  const index = new PreviewTargetIndex();
  const alice = source("alice");
  index.setDisk(alice, node("Alice"));
  index.setOverlay(alice, node("Alicia"));

  assert.equal(index.deleteOverlay(alice.uri), true);
  assert.equal(index.snapshot.documents.has("Alicia"), false);
  assert.equal(index.snapshot.documents.get("Alice").uri, alice.uri);
});

test("watcher and save updates cannot replace an open editor overlay", () => {
  const index = new PreviewTargetIndex();
  const alice = source("alice");
  index.setDisk(alice, node("Disk"));
  index.setOverlay(alice, node("Unsaved"));

  assert.equal(index.setDisk(alice, node("WatcherResult")), false);
  assert.equal(index.snapshot.documents.has("WatcherResult"), false);
  assert.equal(index.snapshot.documents.get("Unsaved").uri, alice.uri);

  index.setDisk(alice, node("Saved"));
  index.setOverlay(alice, node("Saved"));
  assert.equal(index.deleteOverlay(alice.uri), false);
  assert.equal(index.snapshot.documents.get("Saved").uri, alice.uri);
});

test("a save invalidates an older in-flight watcher read", () => {
  const reads = new PreviewDiskReadTracker();
  const uri = source("alice").uri;
  const watcherRead = reads.begin(uri);
  const save = reads.begin(uri);

  assert.equal(reads.isCurrent(uri, watcherRead), false);
  assert.equal(reads.isCurrent(uri, save), true);
});

test("deleting an open file preserves its overlay until the editor closes", () => {
  const index = new PreviewTargetIndex();
  const alice = source("alice");
  index.setDisk(alice, node("Alice"));
  index.setOverlay(alice, node("Alicia"));

  assert.equal(index.deleteDisk(alice.uri), false);
  assert.equal(index.snapshot.documents.get("Alicia").uri, alice.uri);
  assert.equal(index.deleteOverlay(alice.uri), true);
  assert.equal(index.snapshot.documents.size, 0);
});

test("duplicate IDs resolve deterministically and react to multiple overlays", () => {
  const index = new PreviewTargetIndex();
  const zeta = source("zeta");
  const alpha = source("alpha");
  index.setDisk(zeta, node("Duplicate"));
  index.setDisk(alpha, node("Duplicate"));

  assert.equal(index.snapshot.documents.get("Duplicate").uri, alpha.uri);

  index.setOverlay(alpha, node("Renamed"));
  assert.equal(index.snapshot.documents.get("Duplicate").uri, zeta.uri);
  index.setOverlay(zeta, node("AlsoRenamed"));
  assert.equal(index.snapshot.documents.has("Duplicate"), false);
  assert.equal(index.snapshot.documents.get("Renamed").uri, alpha.uri);
  assert.equal(index.snapshot.documents.get("AlsoRenamed").uri, zeta.uri);
});

test("body-only edits do not request an unnecessary target refresh", () => {
  const index = new PreviewTargetIndex();
  const alice = source("alice");
  index.setDisk(alice, `${node("Alice")}body`);
  assert.equal(index.setOverlay(alice, `${node("Alice")}changed body`), false);
});

test("workspace exclusions match complete path segments only", () => {
  const root = "/workspace";
  for (const segment of ["node_modules", ".git", "build", "dist"]) {
    assert.equal(isIndexedMarkdownPath(`${root}/${segment}/nested/a.md`, [root]), false);
  }
  for (const segment of ["node_modules_backup", ".github", "builder", "distant"]) {
    assert.equal(isIndexedMarkdownPath(`${root}/${segment}/nested/a.md`, [root]), true);
  }
  assert.equal(isIndexedMarkdownPath(`${root}/notes/a.txt`, [root]), false);
  assert.equal(isIndexedMarkdownPath("/elsewhere/a.md", [root]), false);
});

test("workspace exclusions and coverage use Windows path semantics", () => {
  const root = String.raw`C:\workspace`;
  assert.equal(
    isIndexedMarkdownPath(String.raw`C:\workspace\node_modules\pkg\a.md`, [root], path.win32),
    false,
  );
  assert.equal(
    isIndexedMarkdownPath(String.raw`C:\workspace\notes\a.md`, [root], path.win32),
    true,
  );
  assert.equal(
    isIndexedMarkdownPath(String.raw`D:\workspace\notes\a.md`, [root], path.win32),
    false,
  );
});

test("a nested workspace root can cover a path excluded relative to its parent", () => {
  const parent = "/workspace";
  const nested = "/workspace/build/project";
  const file = `${nested}/a.md`;

  assert.equal(isIndexedMarkdownPath(file, [parent]), false);
  assert.equal(isIndexedMarkdownPath(file, [parent, nested]), true);
  assert.deepEqual(
    previewIndexAliases(file, [parent, nested]),
    ["a.md", "a", "./a.md"],
  );
});

test("relative aliases are recomputed for added and removed overlapping roots", () => {
  const file = "/workspace/nested/people/alice.md";
  assert.deepEqual(
    previewIndexAliases(file, ["/workspace"]),
    ["alice.md", "alice", "nested/people/alice.md", "./nested/people/alice.md"],
  );
  assert.deepEqual(
    previewIndexAliases(file, ["/workspace", "/workspace/nested"]),
    [
      "alice.md",
      "alice",
      "nested/people/alice.md",
      "./nested/people/alice.md",
      "people/alice.md",
      "./people/alice.md",
    ],
  );
  assert.deepEqual(
    previewIndexAliases(file, ["/workspace/nested"]),
    ["alice.md", "alice", "people/alice.md", "./people/alice.md"],
  );
});

test("atomic workspace replacement purges removed disks and preserves open overlays", () => {
  const index = new PreviewTargetIndex();
  const removed = source("alpha-removed");
  const kept = source("kept");
  const open = source("open");
  index.replace(
    [
      { source: removed, text: node("Duplicate") },
      { source: kept, text: node("Duplicate") },
      { source: open, text: node("DiskOpen") },
    ],
    [{ source: open, text: node("UnsavedOpen") }],
  );

  assert.equal(index.replace(
    [{ source: kept, text: node("Duplicate") }],
    [{ source: open, text: node("UnsavedOpen") }],
  ), true);
  assert.equal(index.snapshot.documents.get("Duplicate").uri, kept.uri);
  assert.equal(index.snapshot.documents.has("DiskOpen"), false);
  assert.equal(index.snapshot.documents.get("UnsavedOpen").uri, open.uri);
});

test("an older delayed workspace scan cannot invalidate the latest scan read", async () => {
  const scans = new PreviewWorkspaceScanTracker();
  const diskReads = new PreviewDiskReadTracker();
  const uri = source("x").uri;
  const s1FindFiles = deferred();
  const s2FindFiles = deferred();
  const s2ReadFile = deferred();
  let readCount = 0;

  async function scan(scanId, findFiles, readFile) {
    await findFiles.promise;
    if (!scans.isCurrent(scanId)) return { kind: "stale" };
    return readWorkspaceScanEntry(
      scanId,
      uri,
      scans,
      diskReads,
      () => {
        readCount += 1;
        return readFile.promise;
      },
      (error) => error?.code === "FileNotFound",
    );
  }

  const s1 = scans.begin();
  const s1Result = scan(s1, s1FindFiles, deferred());
  const s2 = scans.begin();
  const s2Result = scan(s2, s2FindFiles, s2ReadFile);
  s2FindFiles.resolve([uri]);
  await Promise.resolve();
  s1FindFiles.resolve([uri]);
  await Promise.resolve();
  s2ReadFile.resolve(node("X"));

  assert.deepEqual(await s1Result, { kind: "stale" });
  assert.deepEqual(await s2Result, { kind: "content", value: node("X") });
  assert.equal(readCount, 1);

  const index = new PreviewTargetIndex();
  let refreshCount = 0;
  if (index.replace([{ source: source("x"), text: node("X") }], [])) refreshCount += 1;
  assert.equal(index.snapshot.documents.get("X").uri, uri);
  assert.equal(refreshCount, 1);
});

test("workspace scan retries a transient read and indexes the successful result", async () => {
  const scans = new PreviewWorkspaceScanTracker();
  const diskReads = new PreviewDiskReadTracker();
  const scan = scans.begin();
  let attempts = 0;
  const result = await readWorkspaceScanEntry(
    scan,
    source("retry").uri,
    scans,
    diskReads,
    async () => {
      attempts += 1;
      if (attempts === 1) throw Object.assign(new Error("busy"), { code: "EBUSY" });
      return node("RetrySucceeded");
    },
    (error) => error?.code === "FileNotFound",
  );

  assert.deepEqual(result, { kind: "content", value: node("RetrySucceeded") });
  assert.equal(attempts, 2);
});

test("workspace scan omits a permanently missing file without retrying", async () => {
  const scans = new PreviewWorkspaceScanTracker();
  const diskReads = new PreviewDiskReadTracker();
  const scan = scans.begin();
  let attempts = 0;
  const result = await readWorkspaceScanEntry(
    scan,
    source("missing").uri,
    scans,
    diskReads,
    async () => {
      attempts += 1;
      throw Object.assign(new Error("missing"), { code: "FileNotFound" });
    },
    (error) => error?.code === "FileNotFound",
  );

  assert.deepEqual(result, { kind: "missing" });
  assert.equal(attempts, 1);
});

test("exhausted transient reads preserve prior contents with recomputed aliases", async () => {
  const index = new PreviewTargetIndex();
  const oldSource = source("portrait", ["old/portrait.md"]);
  const newSource = source("portrait", ["new/portrait.md"]);
  index.setDisk(oldSource, media("Portrait", "https://example.test/portrait.png"));

  const scans = new PreviewWorkspaceScanTracker();
  const diskReads = new PreviewDiskReadTracker();
  const scan = scans.begin();
  const result = await readWorkspaceScanEntry(
    scan,
    oldSource.uri,
    scans,
    diskReads,
    async () => {
      throw Object.assign(new Error("denied"), { code: "EACCES" });
    },
    (error) => error?.code === "FileNotFound",
  );
  assert.deepEqual(result, { kind: "preserve" });

  assert.equal(index.replace([], [], [newSource]), true);
  assert.equal(index.snapshot.media.has("old/portrait.md"), false);
  assert.equal(index.snapshot.media.get("new/portrait.md"), "https://example.test/portrait.png");
});
