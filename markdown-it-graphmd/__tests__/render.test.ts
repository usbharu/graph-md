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

  it("supports double-quoted relType with spaces", () => {
    const html = render('@link{}[Bob](bob "best friend")');
    expect(html).toContain('data-link-rel="best friend"');
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

  it("stacks validTime above the property name and accepts spaces around equals", () => {
    const html = render("年齢は@props(validTime = CommonEra){age = 25}歳");
    expect(html).toContain('<span class="graphmd-prop-value">25</span><span class="graphmd-prop-annotations"><sup class="graphmd-prop-valid-time">CommonEra</sup><sub class="graphmd-prop-name">age</sub>');
  });

  it("handles a multi-line block", () => {
    const html = render("@props{\n  height = 162.5\n  active = true\n}");
    expect(dataProps(html)).toBe('{"height":162.5,"active":true}');
  });

  it("supports inline occurrence inside a paragraph", () => {
    const html = render("leading @props{x = 1} trailing");
    expect(html).toContain('class="graphmd-props"');
    expect(dataProps(html)).toBe('{"x":1}');
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
