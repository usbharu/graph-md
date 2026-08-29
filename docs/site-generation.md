# Static Wiki generation

GraphMD can generate an Astro project that turns Graph Markdown into a static
Wiki. Astro performs static site generation (SSG), while React is limited to
the search and graph islands. Ordinary document pages are emitted as static
HTML without a React runtime.

For command-level details, see the [CLI guide](../cli/README.md). For editing
the UI itself, see the [Wiki template guide](../site-template/README.md).

## Components and ownership

| Component | Owns |
| --- | --- |
| `core` | GraphMD parsing, validation, compilation, links, backlinks, and graph data |
| `query` | GMQL, static search-index encoding/loading, and the browser query API |
| `astro` | Kotlin/JS-to-Astro integration, Markdown discovery, virtual modules, diagnostics, watching, and search-index assets |
| `markdown-it-graphmd` | GraphMD-aware Markdown rendering and ID-link transformation |
| `site-template` | Astro layouts/pages, React islands, CSS, configuration, and development sample assets |
| `cli` | `site` argument handling, safety checks, template packaging, and project generation |

This division keeps GraphMD semantics in Kotlin Multiplatform and keeps the UI
as a normal Astro project. Handwritten Astro, HTML, TSX, and CSS do not live in
Kotlin source.

The renderer's standalone API and development commands are documented in
[markdown-it-graphmd/README.md](../markdown-it-graphmd/README.md).

## End-to-end flow

### 1. Package the template into the CLI

Every `compileKotlin*` task in `cli` depends on `generateSiteTemplate`:

```text
compileKotlin*
└── :cli:generateSiteTemplate
    └── :cli:stageSiteTemplate
        └── :astro:jsNodeProductionLibraryDistribution
```

`stageSiteTemplate` copies the tracked files from `site-template/`, vendors the
Kotlin/JS output of `graph-md-astro`, and rewrites the local package reference
to `./vendor/graph-md-astro`. It excludes development and generated material
such as `node_modules/`, `dist/`, `.astro/`, materialized runtimes, and emitted
search-index files.

`generateSiteTemplate` then mechanically encodes the staged directory as
`GeneratedSiteTemplate.kt` under `cli/build/generated/siteTemplate/`. That file
is build output; do not edit or commit it.

### 2. Generate an Astro project

`graphmd site OUTPUT [paths...]` discovers and validates the supplied GraphMD
sources. On success, the CLI writes the packaged template to `OUTPUT` and
creates `graphmd.config.mjs` containing:

- the normalized Astro base path;
- canonical paths to the GraphMD source roots.

The Markdown sources themselves are not copied. Consequently, `pnpm dev` and
`pnpm build` in the generated project must still be able to read the configured
source roots. The final `dist/` directory is self-contained and does not need
the original Markdown, Kotlin, a database, or a runtime server.

### 3. Compile GraphMD through Astro/Vite

At Astro startup, `graph-md-astro` reads the configured roots and invokes the
Kotlin/JS compiler. It exposes the compilation snapshot through virtual
modules:

- `virtual:graphmd/graph`
- `virtual:graphmd/site`
- `virtual:graphmd/sources`
- `virtual:graphmd/search`
- `virtual:graphmd/diagnostics`

During development, Markdown add/change/remove events trigger recompilation
and a full browser reload. During a production build, the integration emits
the static query-index shards under `search-index/`.

### 4. Generate routes and browser features

Astro statically generates:

- `/` — document index;
- `/documents/<safe-id>/` — one page per GraphMD document;
- `/search/` — React search island backed by the static GMQL index;
- `/graph/` — React/Cytoscape graph island.

Document links are resolved from the compiler-produced ID-to-route map. A safe
ID preserves lowercase ASCII letters, digits, `_`, and `-`, and encodes every
other UTF-8 byte as `~HH`. Encoding uppercase letters and `.` keeps routes
distinct on case-insensitive filesystems and avoids special path segments.
Route construction does not guess from source file names.

The Markdown renderer disables raw HTML. `markdown-it-graphmd` receives the
same ID-to-route map, so GraphMD links resolve consistently with Astro routes.

## Browser runtimes

Two generated browser runtimes are checked in under
`site-template/runtime-encoded/`:

- `graph-md-query-runtime.js.gz.b64` — the Kotlin/JS GMQL/search runtime;
- `markdown-it-graphmd.js.gz.b64` — the GraphMD Markdown renderer bundle.

They are gzip-compressed and Base64-encoded to keep the multiplatform CLI
source payload manageable. `site-template/astro.config.mjs` materializes them
when Astro starts:

- the query runtime becomes `public/runtime/graph-md-query-runtime.js`;
- the Markdown bundle becomes `src/vendor/markdown-it-graphmd.js`.

The materialized files are generated and ignored by Git. Change the source
module, run `:cli:updateEmbeddedWebRuntime`, and commit the updated encoded
files instead.

## Gradle task reference

| Task | Purpose | Direct tools | Commit its output? |
| --- | --- | --- | --- |
| `:cli:syncMarkdownCoreVendor` | Copies Kotlin/JS `core` files used while bundling `markdown-it-graphmd` | Gradle/Kotlin JS | No |
| `:cli:bundleMarkdownItGraphMd` | Builds the minified Markdown plugin | pnpm, tsup | No; `dist/` is intermediate |
| `:cli:bundleQueryWebRuntime` | Bundles the Kotlin/JS query API for browsers | pnpm, esbuild | No; `cli/build/` is intermediate |
| `:cli:updateEmbeddedWebRuntime` | Compresses both bundles into `site-template/runtime-encoded/` | Node.js and pnpm through dependencies | Yes |
| `:cli:stageSiteTemplate` | Copies the clean template and vendors `graph-md-astro` | Gradle/Kotlin JS | No |
| `:cli:generateSiteTemplate` | Encodes the staged project into generated Kotlin source | Gradle | No |

The task dependencies are:

```text
:cli:updateEmbeddedWebRuntime
├── :cli:bundleQueryWebRuntime
│   └── :query:jsProductionLibraryCompileSync
└── :cli:bundleMarkdownItGraphMd
    └── :cli:syncMarkdownCoreVendor
        └── :core:jsNodeProductionLibraryDistribution
```

Normal template packaging uses the checked-in runtime assets and does not run
`updateEmbeddedWebRuntime` automatically.

## Development workflows

### Work on the Wiki UI

Use `site-template/` directly; do not edit generated Kotlin:

```bash
cd site-template
pnpm install
pnpm dev
```

The template's `graphmd.config.mjs` selects the source roots used for direct
development. See [site-template/README.md](../site-template/README.md) for
fixtures, generated files, and checks.

### Change the Astro bridge

Build the Kotlin/JS library and validate the template:

```bash
./gradlew :astro:jsNodeProductionLibraryDistribution
cd site-template
pnpm install
pnpm check
pnpm build
```

The local template package links to `../astro/build/dist/js/productionLibrary`.
This keeps lockfile updates independent of whether the Kotlin/JS distribution
has already been built. Generated sites instead link to the vendored
`./vendor/graph-md-astro` copy.

### Change the query API or Markdown plugin

Regenerate the checked-in runtimes:

```bash
./gradlew :cli:updateEmbeddedWebRuntime
```

If an IDE-started Gradle daemon cannot see a version-manager installation,
pass absolute executable paths:

```bash
./gradlew :cli:updateEmbeddedWebRuntime \
  -PpnpmExecutable=/absolute/path/to/pnpm \
  -PnodeExecutable=/absolute/path/to/node
```

The build also searches `PATH` and common pnpm, mise, asdf, and Volta
locations.

### Validate the complete pipeline

At minimum, run:

```bash
./gradlew check
cd site-template
pnpm check
pnpm build
```

For a release-facing change, also generate a project with `graphmd site`, run
`pnpm install --frozen-lockfile` and `pnpm build` in it, and inspect the emitted
routes and `search-index/` files.

## Deployment

Generate and build the site:

```bash
./gradlew :cli:run --args="site ./wiki ./documents --base /wiki/"
cd wiki
pnpm install --frozen-lockfile
pnpm build
```

Deploy only `wiki/dist/` to a static host. When hosting below a subdirectory,
pass that public prefix through `--base`; it is applied to Astro and all
compiler-generated internal routes.

## Troubleshooting

### Gradle cannot start `pnpm` or `node`

The executable may exist in an interactive shell but not in the environment
captured by an IDE-started Gradle daemon. Use the explicit properties shown
above, or stop and restart the daemon after fixing its environment.

### Direct template development cannot import `graph-md-astro`

Run `./gradlew :astro:jsNodeProductionLibraryDistribution` from the repository
root, then reinstall the template dependencies. The direct-development
package link intentionally points to that local build output.

### Search-index requests fail

In development, the Astro integration serves the in-memory shard contents. In
a production build, confirm that `dist/search-index/manifest.json` and its
referenced shards exist and that the configured base path matches the deployed
URL prefix.
