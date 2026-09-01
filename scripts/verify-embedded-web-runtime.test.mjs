import assert from "node:assert/strict";
import test from "node:test";
import { gzipSync } from "node:zlib";
import { verifyEmbeddedRuntime } from "./verify-embedded-web-runtime.mjs";

test("accepts an encoded runtime with identical decompressed content", () => {
  const runtime = Buffer.from("console.log('runtime');\n");
  const encoded = `${gzipSync(runtime, { level: 1 }).toString("base64")}\n`;

  assert.doesNotThrow(() => verifyEmbeddedRuntime(encoded, runtime));
});

test("rejects stale decompressed runtime content", () => {
  const encoded = gzipSync(Buffer.from("old runtime\n")).toString("base64");

  assert.throws(
    () => verifyEmbeddedRuntime(encoded, Buffer.from("new runtime\n"), "runtime.gz.b64"),
    /runtime\.gz\.b64 is stale: decompressed content differs at byte 0/,
  );
});
