import { build } from "esbuild";

const production = process.argv.includes("--production");

await build({
  entryPoints: ["src/extension.ts"],
  bundle: true,
  outfile: "dist/extension.js",
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
