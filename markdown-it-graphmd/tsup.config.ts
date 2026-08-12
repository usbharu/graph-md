import { defineConfig } from "tsup";

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
});
