import { describe, expect, it } from "vitest";
import MarkdownIt from "markdown-it";
import { graphMdPlugin, type GraphMdOptions } from "../src/index";

function render(input: string, options?: GraphMdOptions, env?: unknown): string {
  const md = new MarkdownIt();
  md.use(graphMdPlugin, options);
  return md.render(input, env);
}

/** Extracts and HTML-decodes a props attribute value from rendered HTML. */
function dataProps(html: string): string | null {
  const m = html.match(/data-(?:link-props|props-bind)="([^"]*)"/);
  if (!m) {
    return null;
  }
  return m[1]
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">");
}

describe("relation link", () => {
  it("renders the recommended hyperlink form", () => {
    const html = render("Hello @link[Bob](bob friendOf)");
    expect(html).toContain('<a href="bob" data-link-rel="friendOf">Bob</a>');
  });

  it("renders a property-less link with validTime", () => {
    const html = render("@link(validTime=CommonEra)[Bob](bob friendOf)");
    expect(html).toContain('data-link-valid-time="CommonEra"');
    expect(html).not.toContain("data-link-props");
  });

  it("renders canonical properties and validTime", () => {
    const html = render("@link(validTime=CommonEra){weight=0.2}[Bob](bob friendOf)");
    expect(html).toContain('data-link-valid-time="CommonEra"');
    expect(dataProps(html)).toBe('{"weight":0.2}');
  });

  it("serialises props into an escaped data-props attribute", () => {
    const html = render("@link{weight = 0.82}[Bob](bob friendOf)");
    expect(html).toContain("data-link-props=");
    expect(html).toContain("&quot;weight&quot;:0.82");
    expect(dataProps(html)).toBe('{"weight":0.82}');
  });

  it("parses nested inline objects", () => {
    const html = render('@link{note = { default = "close", ja = "親密" }}[Bob](bob friendOf)');
    expect(dataProps(html)).toBe('{"note":{"default":"close","ja":"親密"}}');
  });

  it("rejects double-quoted relType values with spaces", () => {
    const html = render('@link{}[Bob](bob "best friend")');
    expect(html).not.toContain("data-link-rel=");
  });

  it("unescapes and html-escapes the label", () => {
    const html = render("@link{}[A & B \\] C](ab friendOf)");
    expect(html).toContain("A &amp; B ] C");
  });

  it("does not extract inside inline code spans", () => {
    const html = render("`@link{}[Bob](bob friendOf)`");
    expect(html).not.toContain("data-link-rel");
  });

  it("does not extract inside fenced code blocks", () => {
    const html = render("```\n@link{}[Bob](bob friendOf)\n```");
    expect(html).not.toContain("data-link-rel");
  });

  it("is disabled by a preceding backslash escape", () => {
    const html = render("\\@link{}[Bob](bob friendOf)");
    expect(html).not.toContain("data-link-rel");
  });

  it("leaves malformed relations as ordinary text", () => {
    const html = render("@link{}[Bob](bob)");
    expect(html).not.toContain("data-link-rel");
  });

  it("applies hrefTransform", () => {
    const html = render("@link{}[Bob](bob friendOf)", { hrefTransform: (t) => `${t}.html` });
    expect(html).toContain('href="bob.html"');
  });

  it.each(["missing", "duplicate"])("omits href when %s is unresolved by a transform", (target) => {
    const html = render(
      `@link(validTime=CommonEra){note="<unsafe>"}[A & B](${target} friendOf)`,
      { hrefTransform: () => null },
    );

    expect(html).toContain('<a data-link-rel="friendOf"');
    expect(html).not.toContain("href=");
    expect(html).toContain('data-link-valid-time="CommonEra"');
    expect(dataProps(html)).toBe('{"note":"<unsafe>"}');
    expect(html).toContain("A &amp; B</a>");
  });

  it("keeps the raw target href when no transform is configured", () => {
    const html = render("@link{}[Bob](missing friendOf)");
    expect(html).toContain('<a href="missing" data-link-rel="friendOf">Bob</a>');
  });

  it("passes the render environment to hrefTransform", () => {
    const env = { currentDocument: "/workspace/people/alice.md" };
    let receivedEnv: unknown;
    const html = render(
      "@link{}[Bob](bob friendOf)",
      {
        hrefTransform: (_target, _relType, received) => {
          receivedEnv = received;
          return "./robert.md";
        },
      },
      env,
    );

    expect(receivedEnv).toBe(env);
    expect(html).toContain('href="./robert.md"');
  });

  it("renders inline within Japanese text", () => {
    const html = render("Aliceは@link{}[Bob](bob friendOf)と友人です。");
    expect(html).toContain('<a href="bob" data-link-rel="friendOf">Bob</a>');
  });
});

describe("@props", () => {
  it("renders bound properties as visible values", () => {
    const html = render('@props{name = "Alice"}');
    expect(html).toContain('class="graphmd-props"');
    expect(html).not.toContain("hidden");
    expect(html).toContain('data-props-name="name"><span class="graphmd-prop-value">Alice</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">name</sub>');
    expect(html).toContain("&quot;name&quot;:&quot;Alice&quot;");
    expect(dataProps(html)).toBe('{"name":"Alice"}');
  });

  it("renders every bound property in source order and escapes values", () => {
    const html = render('@props{name = "<Alice>",age=20}');
    expect(html.indexOf('data-props-name="name"')).toBeLessThan(html.indexOf('data-props-name="age"'));
    expect(html).toContain("&lt;Alice&gt;");
    expect(html).toContain('<span class="graphmd-prop-value">20</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">age</sub>');
  });

  it("renders a subscript property name immediately before its value in prose", () => {
    const html = render("年齢は@props{age=25}歳");
    expect(html).toContain('年齢は<span class="graphmd-props"');
    expect(html).toContain('<span class="graphmd-prop-value">25</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">age</sub>');
    expect(html).toContain("歳</p>");
  });

  it("renders validTime next to its value and accepts spaces around equals", () => {
    const html = render("年齢は@props(validTime = CommonEra){age = 25}歳");
    expect(html).toContain('<span class="graphmd-prop-value">25<sup class="graphmd-prop-valid-time">CommonEra</sup></span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">age</sub>');
  });

  it("renders each validTime next to the property assertion it applies to", () => {
    const html = render("@props{age(validTime=TimelineA)=17,age=18}");
    expect(html).toContain('<span class="graphmd-prop-value">[18,17<sup class="graphmd-prop-valid-time">TimelineA</sup>]</span><span class="graphmd-prop-annotations"><sub class="graphmd-prop-name">age</sub>');
  });

  it("keeps multiple timelines on their own property assertion", () => {
    const html = render("@props{age(validTime=[TimelineA,TimelineB,TimelineA])=17,age(validTime=TimelineC)=18}");
    expect(html).toContain('[17<sup class="graphmd-prop-valid-time">TimelineA,TimelineB</sup>,18<sup class="graphmd-prop-valid-time">TimelineC</sup>]');
  });

  it("handles a multi-line block", () => {
    const html = render("@props{\n  height = 162.5\n  active = true\n}");
    expect(html).toMatch(/^<div class="graphmd-props"/);
    expect(dataProps(html)).toBe('{"height":162.5,"active":true}');
  });

  it("keeps a standalone props directive with trailing whitespace as a block", () => {
    const html = render("@props{x = 1} \t");
    expect(html).toMatch(/^<div class="graphmd-props"/);
    expect(dataProps(html)).toBe('{"x":1}');
  });

  it("supports inline occurrence inside a paragraph", () => {
    const html = render("leading @props{x = 1} trailing");
    expect(html).toContain('class="graphmd-props"');
    expect(dataProps(html)).toBe('{"x":1}');
  });

  it("preserves prose and markdown after props at the start of a line", () => {
    const html = render('@props{name = "Alice"}です。 **表示されます**');
    expect(html).toMatch(/^<p><span class="graphmd-props"/);
    expect(html).toContain("です。 <strong>表示されます</strong></p>");
    expect(dataProps(html)).toBe('{"name":"Alice"}');
  });

  it("does not extract inside fenced code blocks", () => {
    const html = render("```\n@props{name = \"Alice\"}\n```");
    expect(html).not.toContain("data-props");
  });

  it("keeps malformed block as ordinary paragraph text", () => {
    const html = render("@props not a block");
    expect(html).not.toContain("graphmd-props");
  });

  it("serialises directive-wide and property validTime assertions", () => {
    const html = render('@props(validTime=CommonEra){age=25,name(validTime=Branch(from=1,to=2))="Alice"}');
    const parsed = JSON.parse(dataProps(html) ?? "null");
    expect(parsed.age[0].validTime[0].timeline).toBe("CommonEra");
    expect(parsed.name[0].validTime[0].timeline).toBe("Branch");
    expect(parsed.name[0].validTime[0].from.timecode).toBe(1);
  });

  it("serialises fallback and timed assertions for the same property", () => {
    const html = render("@props{age=17,age(validTime = TimelineA) = 18}");

    expect(JSON.parse(dataProps(html) ?? "null").age).toEqual([
      { value: 17 },
      { value: 18, validTime: [{ timeline: "TimelineA" }] },
    ]);
  });

  it("serialises text key annotations", () => {
    const html = render('@props{name(key="lang:ja")="アリス",name(key="lang:us")="Alice"}');
    expect(JSON.parse(dataProps(html) ?? "null").name).toEqual({ "lang:ja": "アリス", "lang:us": "Alice" });
  });
});

describe("embed blocks", () => {
  it("renders the saved Markdown as a fallback without a resolver", () => {
    const html = render('::: embed:query="MATCH (n) RETURN n"\n\n| old |\n| --- |\n| value |\n:::\n');

    expect(html).toContain("<table>");
    expect(html).toContain("value");
    expect(html).not.toContain("graphmd-embed");
  });

  it("uses the last embed attribute and replaces fallback with a dynamic table", () => {
    const html = render(
      '::: embed:back-link=friendOf embed:query="MATCH (n) RETURN n"\nold\n:::\n',
      {
        hrefTransform: (target) => `./${target}.md`,
        embedResolver: (directive) => ({
          status: "ready",
          table: {
            columns: [{ name: "id", type: "string" }],
            rows: [{ cells: [{ text: "<alice>", targetId: "alice" }] }],
          },
        }),
      },
    );

    expect(html).toContain('data-embed-kind="query"');
    expect(html).toContain('href="./alice.md"');
    expect(html).toContain("&lt;alice&gt;");
    expect(html).not.toContain("old");
  });

  it("keeps fallback and prepends an escaped diagnostic on failure", () => {
    const html = render('::: embed:back-link=friendOf\nfallback\n:::\n', {
      embedResolver: () => ({ status: "error", message: "bad <query>" }),
    });

    expect(html).toContain("graphmd-embed-error");
    expect(html).toContain("bad &lt;query&gt;");
    expect(html).toContain("fallback");
  });

  it("resolves again when a cached token stream is rendered", () => {
    let resolution: ReturnType<NonNullable<GraphMdOptions["embedResolver"]>> = { status: "pending" };
    const md = new MarkdownIt();
    md.use(graphMdPlugin, { embedResolver: () => resolution });
    const env = {};
    const tokens = md.parse('::: embed:query="MATCH (n) RETURN n"\nfallback\n:::\n', env);

    expect(md.renderer.render(tokens, md.options, env)).toContain("fallback");

    resolution = {
      status: "ready",
      table: {
        columns: [{ name: "id", type: "string" }],
        rows: [{ cells: [{ text: "alice" }] }],
      },
    };
    const refreshed = md.renderer.render(tokens, md.options, env);

    expect(refreshed).toContain('data-embed-kind="query"');
    expect(refreshed).toContain("alice");
    expect(refreshed).not.toContain("fallback");
  });
});

describe("named body blocks", () => {
  it("renders valid block boundaries transparently", () => {
    const html = render(
      "::: history annotation validTime=CommonEra(from=0 ,to=1)\n## Heading\n\nBody **text**\n:::",
    );

    expect(html).toContain("<h2>Heading</h2>");
    expect(html).toContain("<p>Body <strong>text</strong></p>");
    expect(html).not.toContain(":::");
    expect(html).not.toContain("graphmd-block");
  });

  it("supports nested fences whose lengths increase by more than one", () => {
    const html = render(
      [
        "::: outer validTime=CommonEra",
        "outer",
        "::::: inner note validTime=Branch",
        "inner",
        ":::::",
        "after inner",
        ":::",
      ].join("\n"),
    );

    expect(html).toContain("<p>outer</p>");
    expect(html).toContain("<p>inner</p>");
    expect(html).toContain("<p>after inner</p>");
    expect(html).not.toContain(":::::");
  });

  it("does not treat block-looking lines inside code fences as boundaries", () => {
    const html = render(
      "::: outer\n```\n::: code validTime=Hidden\n:::\n```\n:::",
    );

    expect(html).toContain("::: code validTime=Hidden");
    expect(html).toContain(":::");
    expect(html).toContain("<pre><code>");
  });

  it("does not recognize nested block fences in list or quote containers", () => {
    const html = render(
      [
        "::: outer",
        "- list item",
        "  ::::: list-block",
        "  :::::",
        "> ::::: quote-block",
        "> :::::",
        "",
        "after",
        ":::",
      ].join("\n"),
    );

    expect(html).toContain("::::: list-block");
    expect(html).toContain("::::: quote-block");
    expect(html).toContain("<p>after</p>");
    expect(html).not.toContain("::: outer");
  });

  it("uses CommonMark list padding when excluding fence-shaped list content", () => {
    const html = render(
      [
        "::: outer",
        "-     item",
        "  ::::: fake",
        ":::",
      ].join("\n"),
    );

    expect(html).not.toContain("::: outer");
    expect(html).toContain("::::: fake");
  });

  it("keeps a nested opening fence that is not longer than its parent", () => {
    const html = render(
      [
        "::: outer",
        "::: invalid-inner",
        ":::",
        ":::",
      ].join("\n"),
    );

    expect(html).toContain("::: invalid-inner");
    expect(html).toContain("<p>:::</p>");
  });

  it("keeps completed child fences inside an unclosed parent", () => {
    const html = render(
      [
        "::: outer",
        "::::: child",
        "text",
        ":::::",
      ].join("\n"),
    );

    expect(html).toContain("::: outer");
    expect(html).toContain("::::: child");
    expect(html).toContain(":::::");
  });

  it("keeps malformed and unclosed blocks as ordinary markdown", () => {
    const invalid = render("::: history validTime=Broken(from=)\ntext\n:::");
    const emptyValidTime = render("::: history validTime=[]\ntext\n:::");
    const unclosed = render("::: history validTime=CommonEra\ntext");

    expect(invalid).toContain("::: history");
    expect(emptyValidTime).toContain("::: history");
    expect(unclosed).toContain("::: history");
  });
});
