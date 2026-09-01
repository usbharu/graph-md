import { defineConfig } from "tsup";
import { readFile } from "node:fs/promises";
// This repository-local build helper is plain JavaScript by design.
// @ts-expect-error - no declaration file is needed for the tsup config
import { normalizeKotlinJsMetadata } from "../scripts/normalize-kotlin-js-metadata.mjs";

export default defineConfig({
  entry: ["src/index.ts"],
  format: ["cjs", "esm"],
  // TypeScript 7 is not supported by tsup's bundled declaration plugin yet.
  // Declarations are emitted by tsc via tsconfig.build.json instead.
  dts: false,
  sourcemap: true,
  clean: true,
  target: "es2022",
  platform: "node",
  esbuildPlugins: [
    {
      name: "normalize-kotlin-js-metadata",
      setup(build) {
        build.onLoad({ filter: /vendor\/.*\.js$/ }, async ({ path }) => ({
          contents: normalizeKotlinJsMetadata(await readFile(path, "utf8")),
          loader: "js",
        }));
      },
    },
  ],
});
