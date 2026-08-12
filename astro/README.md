# graph-md-astro

Astro/Vite bridge for Graph Markdown. This module owns framework-specific file
discovery, virtual modules, diagnostics, and development-time recompilation;
`core` and `query` remain framework-independent.

```js
import { defineConfig } from "astro/config";
import graphMd from "graph-md-astro/integration.mjs";

export default defineConfig({
  integrations: [graphMd({ roots: ["documents"] })],
});
```

Pages and components can import these build-time virtual modules:

- `virtual:graphmd/graph`
- `virtual:graphmd/site`
- `virtual:graphmd/sources`
- `virtual:graphmd/search`
- `virtual:graphmd/diagnostics`

The integration recompiles and reloads the page whenever a source Markdown file
is added, changed, or removed.
