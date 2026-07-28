const assert = require("node:assert/strict");
const path = require("node:path");
const test = require("node:test");
const {
  parseFrontMatterScalar,
  parseMediaFrontMatter,
  relativeMarkdownHref,
  resolveGraphMdHref,
  resolveMediaHref,
} = require("../src/preview-links.ts");
const MarkdownIt = require(require.resolve("markdown-it", {
  paths: [path.resolve(__dirname, "../../../markdown-it-graphmd")],
}));
const { graphMdPlugin } = require("markdown-it-graphmd");

function uri(fsPath, scheme = "file", authority = "") {
  return {
    scheme,
    authority,
    fsPath,
    toString() {
      return `${scheme}://${authority}${fsPath}`;
    },
  };
}

function frontMatter(fields) {
  return `---\n${Object.entries(fields).map(([key, value]) => `${key}: ${value}`).join("\n")}\n---\n`;
}

function preview(documents) {
  const mediaTargets = new Map();
  const documentTargets = new Map();
  for (const document of documents) {
    const id = parseFrontMatterScalar(document.text, "id");
    if (id) documentTargets.set(id, document.uri);
    const media = parseMediaFrontMatter(document.text);
    if (!media) continue;
    mediaTargets.set(media.id, media.url);
    mediaTargets.set(document.uri.toString(), media.url);
    mediaTargets.set(document.uri.fsPath, media.url);
    mediaTargets.set(path.basename(document.uri.fsPath), media.url);
    mediaTargets.set(path.basename(document.uri.fsPath, path.extname(document.uri.fsPath)), media.url);
    const relative = path.relative("/workspace", document.uri.fsPath).replaceAll(path.sep, "/");
    if (!relative.startsWith("..")) {
      mediaTargets.set(relative, media.url);
      mediaTargets.set(`./${relative}`, media.url);
    }
  }

  const md = new MarkdownIt();
  md.use(graphMdPlugin, {
    hrefTransform: (target, _relType, env) =>
      resolveGraphMdHref(target, env, mediaTargets, documentTargets),
  });
  const defaultLinkOpen = md.renderer.rules.link_open
    ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const hrefIndex = tokens[idx].attrIndex("href");
    if (hrefIndex >= 0) {
      const resolved = resolveMediaHref(tokens[idx].attrs[hrefIndex][1], mediaTargets);
      if (resolved) tokens[idx].attrs[hrefIndex][1] = resolved;
    }
    return defaultLinkOpen(tokens, idx, options, env, self);
  };
  return md;
}

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

test("GraphMD relation resolves a Media ID directly to its external URL", () => {
  const source = uri("/workspace/notes/alice.md");
  const media = uri("/workspace/assets/portrait.md");
  const md = preview([
    { uri: source, text: frontMatter({ id: "alice", kind: "Node" }) },
    {
      uri: media,
      text: frontMatter({
        id: '"hero-image"',
        kind: "Media",
        url: '"https://cdn.example/images/hero%20image.png?size=large&fit=cover"',
      }),
    },
  ]);

  const html = md.render("@link[Portrait](hero-image depicts)", {
    currentDocument: source,
  });

  assert.match(
    html,
    /href="https:\/\/cdn\.example\/images\/hero%20image\.png\?size=large&amp;fit=cover"/,
  );
  assert.doesNotMatch(html, /portrait\.md/);
});

test("GraphMD relation keeps Node IDs as encoded relative Markdown hrefs", () => {
  const source = uri("/workspace/notes/alice.md");
  const target = uri("/workspace/people/Bob #1.md");
  const md = preview([
    { uri: source, text: frontMatter({ id: "alice", kind: "Node" }) },
    { uri: target, text: frontMatter({ id: "bob", kind: "Node" }) },
  ]);

  const html = md.render("@link[Bob](bob friendOf)", { currentDocument: source });

  assert.match(html, /href="\.\.\/people\/Bob%20%231\.md"/);
});

test("GraphMD relation leaves an unknown target unchanged", () => {
  const source = uri("/workspace/notes/alice.md");
  const md = preview([
    { uri: source, text: frontMatter({ id: "alice", kind: "Node" }) },
  ]);

  const html = md.render("@link[Unknown](missing friendOf)", {
    currentDocument: source,
  });

  assert.match(html, /href="missing"/);
});

test("ordinary Markdown media aliases still resolve after Markdown URI encoding", () => {
  const source = uri("/workspace/notes/alice.md");
  const media = uri("/workspace/assets/hero image.md");
  const md = preview([
    {
      uri: media,
      text: frontMatter({
        id: '"hero image"',
        kind: "Media",
        url: "'../binary/hero image.png'",
      }),
    },
  ]);

  const html = md.render("[Portrait](<../assets/hero image.md>)", {
    currentDocument: source,
  });

  assert.match(html, /href="\.\.\/binary\/hero image\.png"/);
});

test("only exact Media kind redirects a GraphMD relation", () => {
  const source = uri("/workspace/alice.md");
  const target = uri("/workspace/not-media.md");
  const missingUrl = uri("/workspace/missing-url.md");
  const md = preview([
    {
      uri: target,
      text: frontMatter({
        id: "almost-media",
        kind: "media",
        url: "data:text/plain,not-used",
      }),
    },
    {
      uri: missingUrl,
      text: frontMatter({
        id: "missing-url",
        kind: "Media",
      }),
    },
  ]);

  const html = md.render(
    "@link[Target](almost-media relatedTo) @link[Missing](missing-url relatedTo)",
    {
      currentDocument: source,
    },
  );

  assert.match(html, /href="\.\/not-media\.md"/);
  assert.match(html, /href="\.\/missing-url\.md"/);
  assert.doesNotMatch(html, /data:text/);
});

test("Media URLs are returned verbatim without double-encoding", () => {
  const source = uri("/workspace/alice.md", "vscode-remote", "ssh-remote+host");
  const media = uri("/workspace/media.md", "file");
  const md = preview([
    {
      uri: media,
      text: frontMatter({
        id: "inline-data",
        kind: "Media",
        url: '"data:text/plain;charset=utf-8,already%20encoded"',
      }),
    },
  ]);

  const html = md.render("@link[Data](inline-data embeds)", {
    currentDocument: source,
  });

  assert.match(html, /href="data:text\/plain;charset=utf-8,already%20encoded"/);
  assert.doesNotMatch(html, /%2520/);
});
