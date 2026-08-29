import { mkdir, readFile, writeFile } from "node:fs/promises";
import { gzipSync } from "node:zlib";

const [outputDirectory, queryRuntime, markdownRuntime] = process.argv.slice(2);
if (!outputDirectory || !queryRuntime || !markdownRuntime) {
  throw new Error(
    "Usage: embed-web-runtime.mjs OUTPUT_DIRECTORY QUERY_RUNTIME MARKDOWN_RUNTIME",
  );
}

const files = [
  ["graph-md-query-runtime.js", queryRuntime],
  ["markdown-it-graphmd.js", markdownRuntime],
];
await mkdir(outputDirectory, { recursive: true });
for (const [name, path] of files) {
  // Astro already requires Node.js at build time, so keep the CLI payload
  // compressed and let the generated Astro config materialize these files.
  // This avoids making every Kotlin/Native target compile the full JS text.
  const content = gzipSync(await readFile(path), { level: 9 }).toString("base64");
  await writeFile(`${outputDirectory}/${name}.gz.b64`, `${content}\n`);
}
