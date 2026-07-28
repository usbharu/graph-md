const assert = require("node:assert/strict");
const test = require("node:test");
const {
  loadPreviewTargetIndex,
  resolveDocumentPreviewHref,
  resolveMediaPreviewHref,
} = require("../dist/preview-targets.js");

const currentDocument = {
  filePath: "/workspace/notes/current.md",
  scheme: "file",
  authority: "",
};

function source(filePath, workspaceRelativePaths = []) {
  return {
    uri: `file://${encodeURI(filePath)}`,
    filePath,
    scheme: "file",
    authority: "",
    workspaceRelativePaths,
  };
}

function document(id, kind = "Node", extra = "") {
  return `---\nid: ${id}\nkind: ${kind}\n${extra}---\n`;
}

async function load(entries, delays = new Map()) {
  const texts = new Map(entries.map(([entrySource, text]) => [entrySource.uri, text]));
  return loadPreviewTargetIndex(
    entries.map(([entrySource]) => entrySource),
    (entrySource) => new Promise((resolve) => {
      setTimeout(() => resolve(texts.get(entrySource.uri)), delays.get(entrySource.uri) ?? 0);
    }),
  );
}

test("duplicate document IDs stay unresolved regardless of read completion order", async () => {
  const first = source("/workspace/people/first.md");
  const second = source("/workspace/people/second.md");
  const entries = [[first, document("duplicate")], [second, document("duplicate")]];

  for (const delays of [
    new Map([[first.uri, 20], [second.uri, 0]]),
    new Map([[first.uri, 0], [second.uri, 20]]),
  ]) {
    const index = await load(entries, delays);
    assert.equal(resolveDocumentPreviewHref(index, "duplicate", currentDocument), null);
  }
});

test("unique IDs resolve to encoded relative hrefs and unrelated IDs are unaffected", async () => {
  const unique = source("/workspace/people/Bob #1 日本.md");
  const duplicateA = source("/workspace/people/duplicate-a.md");
  const duplicateB = source("/workspace/people/duplicate-b.md");
  const index = await load([
    [unique, document("bob")],
    [duplicateA, document("duplicate")],
    [duplicateB, document("duplicate")],
  ]);

  assert.equal(
    resolveDocumentPreviewHref(index, "bob", currentDocument),
    "../people/Bob%20%231%20%E6%97%A5%E6%9C%AC.md",
  );
  assert.equal(resolveDocumentPreviewHref(index, "duplicate", currentDocument), null);
  assert.equal(resolveDocumentPreviewHref(index, "missing", currentDocument), null);
});

test("Node and Media sharing an ID are ambiguous while a unique Media resolves to its URL", async () => {
  const index = await load([
    [source("/workspace/node.md"), document("shared")],
    [source("/workspace/shared-media.md"), document("shared", "Media", "url: https://example.com/shared\n")],
    [source("/workspace/unique-media.md"), document("photo", "Media", "url: https://example.com/photo\n")],
  ]);

  assert.equal(resolveDocumentPreviewHref(index, "shared", currentDocument), null);
  assert.equal(resolveMediaPreviewHref(index, "shared"), null);
  assert.equal(resolveDocumentPreviewHref(index, "photo", currentDocument), "https://example.com/photo");
});

test("non-Node document kinds do not become link targets", async () => {
  const index = await load([
    [source("/workspace/type.md"), document("Person", "NodeType")],
  ]);
  assert.equal(resolveDocumentPreviewHref(index, "Person", currentDocument), null);
});

test("a unique document without a safe relative-link context has no href", async () => {
  const target = source("/workspace/target.md");
  const index = await load([[target, document("target")]]);

  assert.equal(resolveDocumentPreviewHref(index, "target", undefined), null);
  assert.equal(
    resolveDocumentPreviewHref(index, "target", {
      filePath: "/workspace/current.md",
      scheme: "vscode-remote",
      authority: "ssh-remote+host",
    }),
    null,
  );
});

test("media aliases are deterministic and duplicate aliases stay unresolved", async () => {
  const mediaA = source("/workspace/a/photo.md", ["a/photo.md"]);
  const mediaB = source("/workspace/b/photo.md", ["b/photo.md"]);
  const index = await load([
    [mediaA, document("photo-a", "Media", "url: https://example.com/a\n")],
    [mediaB, document("photo-b", "Media", "url: https://example.com/b\n")],
  ], new Map([[mediaA.uri, 20], [mediaB.uri, 0]]));

  assert.equal(resolveMediaPreviewHref(index, "photo.md"), null);
  assert.equal(resolveMediaPreviewHref(index, "a/photo.md"), "https://example.com/a");
  assert.equal(resolveMediaPreviewHref(index, "a%2Fphoto.md"), "https://example.com/a");
});

test("aliases repeated within the same Media document count as one candidate", async () => {
  const media = source("/workspace/photo.md", ["photo.md"]);
  const index = await load([
    [media, document("photo", "Media", "url: https://example.com/photo\n")],
  ]);

  assert.equal(resolveMediaPreviewHref(index, "photo"), "https://example.com/photo");
  assert.equal(resolveMediaPreviewHref(index, "photo.md"), "https://example.com/photo");
});

test("a file deleted during refresh is skipped without affecting other IDs", async () => {
  const deleted = source("/workspace/deleted.md");
  const remaining = source("/workspace/remaining.md");
  const index = await loadPreviewTargetIndex(
    [deleted, remaining],
    async (entrySource) => {
      if (entrySource.uri === deleted.uri) throw new Error("file not found");
      return document("remaining");
    },
  );

  assert.equal(resolveDocumentPreviewHref(index, "deleted", currentDocument), null);
  assert.equal(resolveDocumentPreviewHref(index, "remaining", currentDocument), "../remaining.md");
});
