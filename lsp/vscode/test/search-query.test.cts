const assert = require("node:assert/strict");
const test = require("node:test");
const { buildFormQuery, quoteIdentifier } = require("../src/search-query.ts");

function form(overrides = {}) {
  return {
    nodeType: "",
    keyword: "",
    conditions: [],
    temporalMode: "anytime",
    timeline: "",
    instant: "",
    from: "",
    to: "",
    sort: "id-asc",
    limit: 100,
    ...overrides,
  };
}

test("builds a parameterized full-text and property query", () => {
  const result = buildFormQuery(form({
    nodeType: "Person",
    keyword: "勇者 OR 魔王",
    conditions: [{ property: "age", propertyType: "number", operator: ">=", value: "18" }],
    sort: "relevance",
  }));

  assert.match(result.query, /^MATCH \(node:Person\)/);
  assert.match(result.query, /FULLTEXT\(node, \$keyword\)/);
  assert.match(result.query, /node\.age >= \$property0/);
  assert.match(result.query, /ORDER BY score DESC, id ASC/);
  assert.deepEqual(result.parameters, {
    keyword: "\"勇者 OR 魔王\"",
    property0: "18",
  });
});

test("builds temporal queries and clamps the result limit", () => {
  const result = buildFormQuery(form({
    temporalMode: "overlaps",
    timeline: "Main Story",
    from: "10",
    to: "20",
    limit: 5000,
  }));

  assert.match(result.query, /VALID ON `Main Story` OVERLAPS \[\$from, \$to\]/);
  assert.match(result.query, /LIMIT 1000$/);
  assert.deepEqual(result.parameters, { from: "10", to: "20" });
});

test("allows Anytime to target one timeline or all timelines", () => {
  assert.match(
    buildFormQuery(form({ temporalMode: "anytime", timeline: "Main Story" })).query,
    /VALID ON `Main Story` ANYTIME/,
  );
  assert.match(
    buildFormQuery(form({ temporalMode: "anytime", timeline: "" })).query,
    /\nVALID ANYTIME\n/,
  );
});

test("quotes non-standard identifiers and rejects backticks", () => {
  assert.equal(quoteIdentifier("display-name"), "`display-name`");
  assert.throws(() => quoteIdentifier("bad`name"), /cannot be represented/);
});

test("encodes string-like property parameters to preserve their type", () => {
  const result = buildFormQuery(form({
    conditions: [{ property: "active", propertyType: "string", operator: "=", value: "true" }],
  }));

  assert.equal(result.parameters.property0, "\"true\"");
});
