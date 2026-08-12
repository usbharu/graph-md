import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { defineConfig } from "astro/config";
import react from "@astrojs/react";

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

const site = JSON.parse(
  readFileSync(new URL("./src/generated/site.json", import.meta.url), "utf8"),
);

export default defineConfig({
  output: "static",
  base: site.base,
  build: { format: "directory" },
  integrations: [react()],
});
