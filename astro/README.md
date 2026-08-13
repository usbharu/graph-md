# graph-md-astro

Astro/Vite bridge for Graph Markdown. This module owns framework-specific file
discovery, virtual modules, diagnostics, and development-time recompilation;
`core` and `query` remain framework-independent.

For its role in the generated Wiki pipeline, see
[Static Wiki generation](../docs/site-generation.md). For UI work, see the
[Wiki template guide](../site-template/README.md).

```js
import { defineConfig } from "astro/config";
import graphMd from "graph-md-astro/integration.mjs";

export default defineConfig({
  integrations: [graphMd({ roots: ["documents"] })],
});
```

Pages and components can import these build-time virtual modules:

| Module | Value |
| --- | --- |
| `virtual:graphmd/graph` | Compiler-produced graph data |
| `virtual:graphmd/site` | Documents, routes, backlinks, graph views, and normalized base path |
| `virtual:graphmd/sources` | Discovered source paths and contents |
| `virtual:graphmd/search` | Static search-index files keyed by output name |
| `virtual:graphmd/diagnostics` | Structured compiler diagnostics |

Roots may be files or directories outside the Astro project. The integration
recompiles and reloads the page whenever a source Markdown file is added,
changed, or removed; source files do not need to be copied into the site.

Explicitly configured files are parsed regardless of their first line.
Recursively discovered `.md` files are considered GraphMD only when their first
line is `---`. Missing roots are ignored so an empty development project can
still start.

## Build and development behavior

The integration is installed as an Astro integration and adds a Vite plugin:

- `buildStart` reads and compiles all configured roots;
- virtual-module loads return the current in-memory snapshot;
- development middleware serves search-index shards below the Astro base path;
- add/change/remove events rebuild the snapshot and request a full reload;
- `generateBundle` emits production search assets under `search-index/`;
- compiler errors fail startup/build with formatted diagnostics.

The public JavaScript entry points and TypeScript declarations live in
`src/jsMain/resources/`. Kotlin logic is compiled as a library distribution:

```bash
./gradlew :astro:jsNodeProductionLibraryDistribution
```

Direct `site-template` development consumes that output through
`file:../astro/build/dist/js/productionLibrary`. During CLI packaging,
`:cli:stageSiteTemplate` copies the same distribution into
`vendor/graph-md-astro` and rewrites the dependency to the vendored path. A
generated site therefore does not depend on the source monorepo for this
module.

## Validation

```bash
./gradlew :astro:jsTest
./gradlew :astro:jsNodeProductionLibraryDistribution
cd site-template
pnpm check
pnpm build
```
