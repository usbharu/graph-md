# markdown-it-graphmd

`markdown-it-graphmd` renders GraphMD body syntax through markdown-it. It is a
TypeScript package and does not own parsing or validation of GraphMD document
frontmatter; those semantics remain in the Kotlin Multiplatform `core` module.

Its role in static Wiki generation is documented in
[Static Wiki generation](../docs/site-generation.md).

## Usage

```ts
import MarkdownIt from "markdown-it";
import { graphMdPlugin } from "markdown-it-graphmd";

const markdown = new MarkdownIt({ html: false });
markdown.use(graphMdPlugin, {
  hrefTransform: (target, relationType) => routes[target] ?? null,
});
```

The plugin renders:

- relation links such as `@link[label](target RelType)`;
- relation properties and valid-time metadata;
- `@props` inline and block syntax;
- resolved embed blocks and embed tables.

`hrefTransform` receives the GraphMD target ID and relation type. Return the
compiler-produced route for a resolved target. Return `null` for unresolved or
ambiguous targets so the label and relation metadata render without a link.
`embedHrefTransform` can resolve backlink IDs separately; by default it
delegates to `hrefTransform`. `embedResolver` supplies resolved embed content.

Raw HTML behavior is controlled by the parent markdown-it instance. The Wiki
template deliberately constructs it with `html: false`.

## Development

Install dependencies and run the package checks from this directory:

```bash
pnpm install
pnpm test
pnpm typecheck
pnpm build
```

The package's vendor directory contains Kotlin/JS `core` bindings used by the
TypeScript implementation. Its `prebuild`, `pretypecheck`, and `pretest`
scripts refresh those bindings.

## Use in the generated Wiki

The Wiki does not install this package at deployment time. The CLI maintenance
pipeline builds and minifies it, then stores the compressed browser bundle as:

```text
site-template/runtime-encoded/markdown-it-graphmd.js.gz.b64
```

After changing renderer behavior or its public API, regenerate that checked-in
asset from the repository root:

```bash
./gradlew :cli:updateEmbeddedWebRuntime
```

`site-template/astro.config.mjs` materializes the encoded bundle into the
ignored `site-template/src/vendor/` directory when Astro starts.
