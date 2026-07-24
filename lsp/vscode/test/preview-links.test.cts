const assert = require("node:assert/strict");
const test = require("node:test");
const { relativeMarkdownHref } = require("../src/preview-links.ts");

test("uses the real filename for a target in the same directory", () => {
  assert.equal(
    relativeMarkdownHref("/workspace/people/alice.md", "/workspace/people/robert-profile.md"),
    "./robert-profile.md",
  );
});

test("resolves a target in a sibling directory", () => {
  assert.equal(
    relativeMarkdownHref("/workspace/notes/alice.md", "/workspace/people/robert-profile.md"),
    "../people/robert-profile.md",
  );
});

test("resolves a target below the source directory", () => {
  assert.equal(
    relativeMarkdownHref("/workspace/alice.md", "/workspace/people/robert-profile.md"),
    "./people/robert-profile.md",
  );
});
