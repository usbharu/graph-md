const assert = require("node:assert/strict");
const test = require("node:test");
const {
  parseCreateDefinitionCommandArgs,
  selectedCreateDefinition,
} = require("../src/create-definition.ts");

const args = {
  uri: "file:///workspace/nodes/bob.md",
  kind: "Node",
  id: "bob",
  choices: [
    { label: "Person", content: "---\nid: bob\nkind: Node\ntype: Person\n---\n" },
    { label: "Company", content: "---\nid: bob\nkind: Node\ntype: Company\n---\n" },
  ],
};

test("parses NodeType choices for the VS Code command", () => {
  assert.deepEqual(parseCreateDefinitionCommandArgs(args), args);
});

test("returns the selected document content", () => {
  assert.deepEqual(selectedCreateDefinition(args, "Company"), {
    uri: args.uri,
    label: "Company",
    content: args.choices[1].content,
  });
});

test("cancellation or an unknown choice does not produce a document", () => {
  assert.equal(selectedCreateDefinition(args, undefined), undefined);
  assert.equal(selectedCreateDefinition(args, "Missing"), undefined);
});

test("rejects commands without usable choices", () => {
  assert.equal(parseCreateDefinitionCommandArgs({ ...args, choices: [] }), undefined);
  assert.equal(parseCreateDefinitionCommandArgs({ ...args, kind: "Timeline" }), undefined);
});
