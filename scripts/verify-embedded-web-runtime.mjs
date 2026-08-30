import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { gunzipSync } from "node:zlib";

export function verifyEmbeddedRuntime(encodedContent, expectedContent, name = "embedded runtime") {
  const actual = gunzipSync(Buffer.from(encodedContent.toString().trim(), "base64"));
  const expected = Buffer.from(expectedContent);
  if (actual.equals(expected)) {
    return;
  }

  const sharedLength = Math.min(actual.length, expected.length);
  let firstDifference = sharedLength;
  for (let index = 0; index < sharedLength; index += 1) {
    if (actual[index] !== expected[index]) {
      firstDifference = index;
      break;
    }
  }
  throw new Error(
    `${name} is stale: decompressed content differs at byte ${firstDifference} ` +
      `(checked in: ${actual.length} bytes, generated: ${expected.length} bytes)`,
  );
}

async function main(args) {
  if (args.length === 0 || args.length % 2 !== 0) {
    throw new Error(
      "Usage: verify-embedded-web-runtime.mjs ENCODED_RUNTIME GENERATED_RUNTIME [...]",
    );
  }

  for (let index = 0; index < args.length; index += 2) {
    const encodedPath = args[index];
    const generatedPath = args[index + 1];
    verifyEmbeddedRuntime(
      await readFile(encodedPath),
      await readFile(generatedPath),
      encodedPath,
    );
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main(process.argv.slice(2));
}
