import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { build } from "esbuild";
import { normalizeKotlinJsMetadata } from "./normalize-kotlin-js-metadata.mjs";

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  const [entryPoint, outfile] = process.argv.slice(2);
  if (!entryPoint || !outfile) {
    throw new Error("Usage: bundle-query-runtime.mjs ENTRY_POINT OUTPUT_FILE");
  }

  await build({
    entryPoints: [entryPoint],
    bundle: true,
    platform: "browser",
    format: "iife",
    globalName: "GraphMdQueryRuntime",
    minify: true,
    outfile,
    plugins: [
      {
        name: "normalize-kotlin-js-metadata",
        setup(build) {
          build.onLoad({ filter: /\.js$/ }, async ({ path }) => ({
            contents: normalizeKotlinJsMetadata(await readFile(path, "utf8")),
            loader: "js",
          }));
        },
      },
    ],
  });
}
