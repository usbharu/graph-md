import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const grammar = JSON.parse(
  await readFile(new URL("./graphmd.tmLanguage.json", import.meta.url), "utf8"),
);
const linkBegin = new RegExp(grammar.repository["graphmd-link"].begin);
const bodyBlockPatterns = grammar.repository["graphmd-body-block"].patterns;
const bodyBlockBegin = new RegExp(bodyBlockPatterns[0].begin);
const bodyBlockEnd = new RegExp(bodyBlockPatterns[1].match);

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

for (const source of [
  "::: history validTime=CommonEra",
  "::::: spoiler annotation validTime=Branch",
  "   :::::::: repeated repeated",
]) {
  test(`highlights a body block opening fence in ${source}`, () => {
    assert.equal(bodyBlockBegin.test(source), true);
  });
}

for (const source of [":::", ":::::", "   ::::::::  "]) {
  test(`highlights a body block closing fence in ${source}`, () => {
    assert.equal(bodyBlockEnd.test(source), true);
  });
}

for (const source of [
  ":: history",
  "    ::: history",
  "- ::: history",
  "> ::: history",
  ":::",
]) {
  test(`does not treat an invalid opening fence as a body block in ${source}`, () => {
    assert.equal(bodyBlockBegin.test(source), false);
  });
}
