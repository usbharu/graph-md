import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { defineConfig } from "astro/config";
import react from "@astrojs/react";
import graphMd from "graph-md-astro/integration.mjs";
import graphMdConfig from "./graphmd.config.mjs";

function materialize(input, output) {
  const outputUrl = new URL(output, import.meta.url);
  mkdirSync(new URL(".", outputUrl), { recursive: true });
  writeFileSync(
    outputUrl,
    gunzipSync(
      Buffer.from(readFileSync(new URL(input, import.meta.url), "utf8"), "base64"),
    ),
  );
}

materialize(
  "./runtime-encoded/markdown-it-graphmd.js.gz.b64",
  "./src/vendor/markdown-it-graphmd.js",
);
materialize(
  "./runtime-encoded/graph-md-query-runtime.js.gz.b64",
  "./public/runtime/graph-md-query-runtime.js",
);

export default defineConfig({
  output: "static",
  base: graphMdConfig.base,
  build: { format: "directory" },
  integrations: [graphMd({ roots: graphMdConfig.roots }), react()],
});
