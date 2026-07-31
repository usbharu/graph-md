import { build } from "esbuild";

const production = process.argv.includes("--production");

await build({
  entryPoints: {
    extension: "src/extension.ts",
    "search-webview": "src/search-webview.ts",
  },
  bundle: true,
  outdir: "dist",
  platform: "node",
  format: "cjs",
  target: "node18",
  // `vscode` is provided by the extension host at runtime; everything else
  // (vscode-languageclient, markdown-it-graphmd, ...) is inlined.
  external: ["vscode"],
  minify: production,
  sourcemap: !production,
  logLevel: "info",
  legalComments: "none",
});
