const assert = require("node:assert/strict");
const path = require("node:path");
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

test("URI-encodes reserved characters in each filename segment", () => {
  assert.equal(
    relativeMarkdownHref(
      "/workspace/notes/alice.md",
      "/workspace/people/hash#query?percent% name 日本.md",
    ),
    "../people/hash%23query%3Fpercent%25%20name%20%E6%97%A5%E6%9C%AC.md",
  );
});

test("normalizes and encodes a relative path on Windows", () => {
  assert.equal(
    relativeMarkdownHref(
      String.raw`C:\workspace\notes\alice.md`,
      String.raw`C:\workspace\people\Bob #1.md`,
      path.win32,
    ),
    "../people/Bob%20%231.md",
  );
});

test("does not create a relative link across Windows drives", () => {
  assert.equal(
    relativeMarkdownHref(
      String.raw`C:\workspace\notes\alice.md`,
      String.raw`D:\people\bob.md`,
      path.win32,
    ),
    null,
  );
});
