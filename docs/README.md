# GraphMD documentation

This directory contains project-wide design and usage documentation. Details
that apply to only one subproject live beside that subproject and are linked
from here.

## Language and data model

- [GraphMD specification](spec.md) defines the normative syntax and semantics.
- [Timeline guide](timeline-guide.md) explains the temporal model with practical
  examples.
- [Query engine guide](../query/README.md) covers graph search, GMQL, and static
  search-index distribution.

## Tooling and applications

- [Static Wiki generation](site-generation.md) describes the complete CLI to
  Astro SSG pipeline, ownership boundaries, generated artifacts, Gradle tasks,
  and deployment flow.
- [CLI guide](../cli/README.md) documents `graphmd site`, its safety rules and
  output, and the CLI-owned Gradle maintenance tasks.
- [Astro integration](../astro/README.md) documents the Kotlin/JS bridge,
  virtual modules, source watching, and search-index emission.
- [Markdown renderer](../markdown-it-graphmd/README.md) documents the
  markdown-it plugin API and its generated browser bundle.
- [Wiki template development](../site-template/README.md) documents direct UI
  development, fixtures, directory layout, and validation.
- [LSP guide](../lsp/README.md) documents editor integration and VSIX packaging.
