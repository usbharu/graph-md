import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const grammar = JSON.parse(
  await readFile(new URL("./graphmd.tmLanguage.json", import.meta.url), "utf8"),
);
const linkBegin = new RegExp(grammar.repository["graphmd-link"].begin);

for (const source of [
  "@link[title](id relType)",
  "@link(validTime=CommonEra)[title](id relType)",
  "@link{}[title](id relType)",
  "@link(validTime=CommonEra){}[title](id relType)",
]) {
  test(`captures the complete @link keyword in ${source}`, () => {
    const match = linkBegin.exec(source);

    assert.equal(match?.[0].startsWith("@link"), true);
    assert.equal(match?.[1], "@link");
  });
}

for (const source of ["@linking[title](id relType)", "@link [title](id relType)"]) {
  test(`does not match non-GraphMD text in ${source}`, () => {
    assert.equal(linkBegin.test(source), false);
  });
}
