import assert from "node:assert/strict";
import test from "node:test";
import { normalizeKotlinJsMetadata } from "./normalize-kotlin-js-metadata.mjs";

test("sorts Kotlin/JS metadata interfaces", () => {
  const first = "  initMetadataForClass(HashSet, 'HashSet', create, AbstractSet, [KtSet, Collection]);";
  const second = "  initMetadataForClass(HashSet, 'HashSet', create, AbstractSet, [Collection, KtSet]);";

  assert.equal(normalizeKotlinJsMetadata(first), normalizeKotlinJsMetadata(second));
  assert.equal(
    normalizeKotlinJsMetadata(first),
    "  initMetadataForClass(HashSet, 'HashSet', create, AbstractSet, [Collection, KtSet]);",
  );
});

test("sorts inherited interfaces in interface metadata", () => {
  const source = "  initMetadataForInterface(KtList, 'List', VOID, VOID, [Sequence, Collection]);";
  assert.equal(
    normalizeKotlinJsMetadata(source),
    "  initMetadataForInterface(KtList, 'List', VOID, VOID, [Collection, Sequence]);",
  );
});

test("leaves ordinary arrays unchanged", () => {
  const source = "const interfaces = [KtSet, Collection];";
  assert.equal(normalizeKotlinJsMetadata(source), source);
});
