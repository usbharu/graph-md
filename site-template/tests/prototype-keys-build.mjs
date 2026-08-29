import { mkdtemp, cp, symlink, writeFile, access, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const temporaryRoot = await mkdtemp(path.join(tmpdir(), "graphmd-prototype-keys-"));

await cp(root, temporaryRoot, {
  recursive: true,
  filter: (source) => !["node_modules", "dist", ".astro"].includes(path.basename(source)),
});
await symlink(path.join(root, "node_modules"), path.join(temporaryRoot, "node_modules"), "dir");
await cp(
  path.join(root, "tests/fixtures/prototype-keys"),
  path.join(temporaryRoot, "documents"),
  { recursive: true },
);
await writeFile(
  path.join(temporaryRoot, "graphmd.config.mjs"),
  'export default { base: "/wiki/", roots: ["documents", "documents/explicit-source.graphmd"] };\n',
);

await new Promise((resolve, reject) => {
  const child = spawn(path.join(root, "node_modules/.bin/astro"), ["build"], {
    cwd: temporaryRoot,
    stdio: "inherit",
  });
  child.once("error", reject);
  child.once("exit", (code) => code === 0 ? resolve() : reject(new Error(`astro build exited with ${code}`)));
});

for (const slug of [
  "constructor",
  "__proto__",
  "constructor-item",
  "prototype-item",
  "uppercase-extension",
  "explicit-source",
]) {
  await access(path.join(temporaryRoot, "dist/documents", slug, "index.html"));
}
const constructorPage = await readFile(
  path.join(temporaryRoot, "dist/documents/constructor-item/index.html"),
  "utf8",
);
if (!constructorPage.includes("visible constructor") || !constructorPage.includes("visible prototype")) {
  throw new Error("prototype-named properties were not rendered");
}
