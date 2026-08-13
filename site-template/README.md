# GraphMD Wiki template

`site-template` is the editable Astro project used by `graphmd site`. Develop
the Wiki UI here with the standard Astro toolchain. Do not edit
`GeneratedSiteTemplate.kt`; Gradle regenerates that file mechanically from
this directory.

For the complete CLI-to-deployment pipeline, see
[Static Wiki generation](../docs/site-generation.md).

## Prerequisites

- Node.js supported by the pinned Astro version;
- pnpm matching the `packageManager` field in `package.json`;
- a built `graph-md-astro` Kotlin/JS distribution.

Prepare the local integration and dependencies:

```bash
./gradlew :astro:jsNodeProductionLibraryDistribution
cd site-template
pnpm install
```

## Direct development

```bash
pnpm dev
```

`graphmd.config.mjs` controls direct-development inputs:

```js
export default {
  base: "/",
  roots: ["documents"],
};
```

Roots are resolved relative to the Astro project unless absolute. They may
also point outside `site-template/`. Markdown changes are watched by the
`graph-md-astro` integration and trigger recompilation and reload.

Useful commands:

```bash
pnpm check    # Astro and TypeScript diagnostics
pnpm build    # production SSG output in dist/
pnpm preview  # serve the completed production build
```

## Directory layout

| Path | Purpose |
| --- | --- |
| `astro.config.mjs` | Static Astro config, React integration, GraphMD integration, and runtime materialization |
| `graphmd.config.mjs` | Base path and GraphMD source roots; overwritten by `graphmd site` |
| `src/layouts/` | Shared static Wiki shell |
| `src/pages/` | Index, document, search, and graph routes |
| `src/components/` | React islands and reusable UI components |
| `src/lib/site.ts` | Typed access to `virtual:graphmd/site` |
| `src/lib/markdown.ts` | Safe markdown-it setup and ID-route link transformation |
| `src/styles/` | Global responsive Wiki styles |
| `runtime-encoded/` | Checked-in compressed browser runtimes |
| `src/vendor/` | Materialized Markdown runtime; generated and ignored |
| `public/runtime/` | Materialized browser query runtime; generated and ignored |
| `public/search-index/` | Checked-in sample index for the template; excluded from CLI packaging and replaced by compiler output during real builds |
| `.astro/`, `dist/`, `node_modules/` | Local Astro/build/dependency outputs; generated and ignored |

The source of page data is the `virtual:graphmd/site` module, not a handwritten
JSON fixture. The files currently tracked in `public/search-index/` are sample
assets for the standalone template and are excluded by `stageSiteTemplate`.
Search shards are provided in memory by the integration during development and
emitted into the production bundle during `pnpm build`.

## Routes and hydration

- `src/pages/index.astro` renders the static document index.
- `src/pages/documents/[slug].astro` uses `getStaticPaths()` to pre-render every
  GraphMD document.
- `src/pages/search.astro` hydrates `SearchApp` and loads the browser GMQL
  runtime plus static index shards.
- `src/pages/graph.astro` hydrates Cytoscape-based graph components.

Keep ordinary pages as Astro/static HTML. Add client hydration only to features
that require browser interaction so search and graph JavaScript do not leak
into document-page bundles.

## Editing rules

- Edit Astro, TSX, and CSS only in this project, not in Kotlin string literals.
- Resolve GraphMD links through the compiler-provided `site.routes` map.
- Keep raw HTML disabled in markdown-it unless the language security model is
  deliberately changed.
- Treat `runtime-encoded/*.gz.b64` as generated assets. Regenerate them through
  `./gradlew :cli:updateEmbeddedWebRuntime` from the repository root.
- Do not add workspace-only or absolute dependencies to the generated package.
  `stageSiteTemplate` must be able to replace the local Astro bridge with its
  vendored copy.

## Validation checklist

After UI changes:

```bash
pnpm check
pnpm build
```

Inspect `dist/` to confirm:

- all expected document routes contain pre-rendered body HTML;
- `search-index/manifest.json` and all referenced shards exist;
- ordinary document pages do not load search or Cytoscape chunks;
- links and assets include the configured base path.

Before merging a packaging change, also run `./gradlew check`, generate a fresh
project with `graphmd site`, install with `--frozen-lockfile`, and build that
generated project.
