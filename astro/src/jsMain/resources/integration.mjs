import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import graphMd from "./graph-md-astro.js";

const VIRTUAL_MODULES = Object.freeze({
  graph: "virtual:graphmd/graph",
  site: "virtual:graphmd/site",
  sources: "virtual:graphmd/sources",
  search: "virtual:graphmd/search",
  diagnostics: "virtual:graphmd/diagnostics",
});

const RESOLVED_PREFIX = "\0graphmd:";
const EXCLUDED_DIRECTORIES = new Set([".git", ".astro", "node_modules", "build", "dist"]);

/**
 * Compiles Graph Markdown as part of Astro's Vite pipeline.
 * Source data stays in memory and Markdown edits trigger a fresh compilation.
 */
export default function graphMdIntegration(options = {}) {
  const extensions = new Set(
    (options.extensions ?? [".md"]).map((extension) => extension.toLowerCase()),
  );
  const configuredRoots = options.roots ?? ["documents"];
  let projectRoot = process.cwd();
  let siteBase = "/";
  let roots = [];
  let snapshot;
  let rebuildQueue = Promise.resolve();

  function rebuild() {
    const pending = rebuildQueue.then(async () => {
      const sources = await readSources(roots, extensions, projectRoot);
      const compilation = graphMd.dev.usbharu.graphmd.astro.GraphMdAstro.compile(
        JSON.stringify(sources),
      );
      const diagnostics = JSON.parse(compilation.diagnosticsJson());
      const nextSnapshot = {
        successful: compilation.successful,
        diagnostics,
        graph: JSON.parse(compilation.graphJson()),
        site: JSON.parse(compilation.siteJson(siteBase)),
        search: JSON.parse(compilation.searchFilesJson()),
        sources,
      };

      if (!nextSnapshot.successful) {
        throw new Error(formatDiagnostics(diagnostics));
      }
      snapshot = nextSnapshot;
      return snapshot;
    });
    rebuildQueue = pending.then(() => undefined, () => undefined);
    return pending;
  }

  const vitePlugin = {
    name: "graph-md:astro",
    enforce: "pre",
    async buildStart() {
      await rebuild();
      for (const root of roots) this.addWatchFile(root);
    },
    resolveId(id) {
      if (Object.values(VIRTUAL_MODULES).includes(id)) {
        return `${RESOLVED_PREFIX}${id.slice("virtual:graphmd/".length)}`;
      }
    },
    async load(id) {
      if (!id.startsWith(RESOLVED_PREFIX)) return;
      const current = snapshot ?? (await rebuild());
      const key = id.slice(RESOLVED_PREFIX.length);
      if (!(key in current)) return;
      return `export default ${JSON.stringify(current[key])};`;
    },
    generateBundle() {
      if (!snapshot?.successful) return;
      for (const [name, source] of Object.entries(snapshot.search)) {
        this.emitFile({ type: "asset", fileName: `search-index/${name}`, source });
      }
    },
    configureServer(server) {
      server.watcher.add(roots);
      let queued;

      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url ?? "/", "http://graphmd.local").pathname;
        const prefix = `${siteBase}search-index/`;
        if (!pathname.startsWith(prefix)) return next();
        const name = decodeURIComponent(pathname.slice(prefix.length));
        const source = snapshot?.search[name];
        if (source === undefined) return next();
        response.statusCode = 200;
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.end(source);
      });

      const refresh = (file) => {
        if (!isGraphMdSource(file, roots, extensions)) return;
        clearTimeout(queued);
        queued = setTimeout(async () => {
          try {
            await rebuild();
            if (server.environments) {
              for (const environment of Object.values(server.environments)) {
                environment.moduleGraph.invalidateAll();
              }
            } else {
              server.moduleGraph.invalidateAll();
            }
            server.ws.send({ type: "full-reload" });
          } catch (error) {
            server.config.logger.error(error.message, { error });
            server.ws.send({
              type: "error",
              err: { message: error.message, stack: error.stack ?? "" },
            });
          }
        }, 35);
      };

      server.watcher.on("add", refresh);
      server.watcher.on("change", refresh);
      server.watcher.on("unlink", refresh);
      server.httpServer?.once("close", () => {
        clearTimeout(queued);
        server.watcher.off("add", refresh);
        server.watcher.off("change", refresh);
        server.watcher.off("unlink", refresh);
      });
    },
  };

  return {
    name: "graph-md",
    hooks: {
      "astro:config:setup": ({ config, updateConfig }) => {
        projectRoot = path.normalize(fileURLToPath(config.root));
        siteBase = normalizeBase(config.base);
        roots = configuredRoots.map((root) => path.resolve(projectRoot, root));
        updateConfig({ vite: { plugins: [vitePlugin] } });
      },
    },
  };
}

export { VIRTUAL_MODULES };

async function readSources(roots, extensions, projectRoot) {
  const files = [];
  for (const root of roots) {
    const excludeProject = root !== projectRoot && isInside(projectRoot, root);
    await walk(root, extensions, files, { explicit: true, projectRoot, excludeProject });
  }
  const uniqueFiles = new Map();
  for (const entry of files) {
    const existing = uniqueFiles.get(entry.file);
    uniqueFiles.set(entry.file, {
      file: entry.file,
      explicit: entry.explicit || existing?.explicit || false,
    });
  }
  const sortedFiles = [...uniqueFiles.values()]
    .sort((left, right) => left.file.localeCompare(right.file));
  const sources = await Promise.all(
    sortedFiles.map(async ({ file, explicit }) => ({
      explicit,
      path: normalizePath(path.relative(projectRoot, file)),
      text: await fs.readFile(file, "utf8"),
    })),
  );
  return sources
    .filter((source) => source.explicit || firstLine(source.text) === "---")
    .map(({ path: sourcePath, text }) => ({ path: sourcePath, text }));
}

async function walk(directory, extensions, output, context) {
  let metadata;
  try {
    metadata = await fs.stat(directory);
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (metadata.isFile()) {
    // A file root is explicit and follows the CLI rule: validate it regardless
    // of its extension. Extension filtering only applies while walking a root
    // directory.
    output.push({ file: directory, explicit: context.explicit });
    return;
  }
  if (!metadata.isDirectory()) return;
  if (context.excludeProject && path.resolve(directory) === context.projectRoot) return;
  let entries;
  try {
    entries = await fs.readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  for (const entry of entries) {
    if (entry.isDirectory() && EXCLUDED_DIRECTORIES.has(entry.name)) continue;
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await walk(absolute, extensions, output, { ...context, explicit: false });
    } else if (entry.isFile() && hasExtension(entry.name, extensions)) {
      output.push({ file: absolute, explicit: false });
    }
  }
}

function firstLine(text) {
  return text.split(/\r?\n/, 1)[0];
}

function isInside(candidate, parent) {
  const relative = path.relative(parent, candidate);
  return relative !== "" && !relative.startsWith("..") && !path.isAbsolute(relative);
}

function isGraphMdSource(file, roots, extensions) {
  const absolute = path.resolve(file);
  return roots.some((root) =>
    absolute === root ||
    (absolute.startsWith(`${root}${path.sep}`) && hasExtension(absolute, extensions)),
  );
}

function hasExtension(file, extensions) {
  return extensions.has(path.extname(file).toLowerCase());
}

function formatDiagnostics(diagnostics) {
  const errors = diagnostics.filter((diagnostic) => diagnostic.severity === "error");
  const selected = errors.length > 0 ? errors : diagnostics;
  return selected
    .map((diagnostic) => {
      const location = diagnostic.source?.path ? `${diagnostic.source.path}: ` : "";
      return `${location}${diagnostic.message}`;
    })
    .join("\n");
}

function normalizePath(value) {
  return value.split(path.sep).join("/");
}

function normalizeBase(value) {
  const trimmed = value.trim().replace(/^\/+|\/+$/g, "");
  return trimmed ? `/${trimmed}/` : "/";
}
