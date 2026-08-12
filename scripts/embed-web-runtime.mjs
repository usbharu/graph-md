import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { gzipSync } from "node:zlib";

const [output, queryRuntime, markdownRuntime] = process.argv.slice(2);
if (!output || !queryRuntime || !markdownRuntime) {
  throw new Error("Usage: embed-web-runtime.mjs OUTPUT QUERY_RUNTIME MARKDOWN_RUNTIME");
}

function kotlinLiteral(value) {
  return JSON.stringify(value).replace(/\$/g, "\\$");
}

const files = [
  ["graph-md-query-runtime.js", queryRuntime],
  ["markdown-it-graphmd.js", markdownRuntime],
];
let source = "package dev.usbharu.graphmd.cli\n\ninternal val embeddedWebRuntimeFiles: Map<String, String> = linkedMapOf(\n";
for (const [name, path] of files) {
  // Astro already requires Node.js at build time, so keep the CLI payload
  // compressed and let the generated Astro config materialize these files.
  // This avoids making every Kotlin/Native target compile the full JS text.
  const content = gzipSync(await readFile(path), { level: 9 }).toString("base64");
  source += `    ${kotlinLiteral(name)} to listOf(\n`;
  for (let offset = 0; offset < content.length; offset += 12_000) {
    source += `        ${kotlinLiteral(content.slice(offset, offset + 12_000))},\n`;
  }
  source += "    ).joinToString(\"\"),\n";
}
source += ")\n";
await mkdir(dirname(output), { recursive: true });
await writeFile(output, source);
