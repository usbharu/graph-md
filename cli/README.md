# GraphMD CLI

The `cli` Kotlin Multiplatform module provides the `graphmd` command for JVM,
Node.js, macOS, Linux, and Windows targets. This document focuses on static
Wiki generation and its CLI-owned build tasks. See the
[project documentation index](../docs/README.md) for the language, query, and
LSP guides.

## Install the CLI

The installer downloads the native archive for the current platform and verifies
it against the release's `SHA256SUMS` before replacing an existing installation.
Releases v0.0.7 and earlier do not contain that file and are not supported by the
installer; use the first later release containing `SHA256SUMS` or newer.

On macOS arm64/x64 or Linux x64, use either `curl` or `wget`:

```sh
curl -fsSL https://raw.githubusercontent.com/usbharu/graph-md/main/install.sh | sh
# or
wget -qO- https://raw.githubusercontent.com/usbharu/graph-md/main/install.sh | sh
```

The default destination is `~/.local/bin/graphmd`. Pin a version or choose a
different directory by passing arguments through `sh`:

```sh
curl -fsSL https://raw.githubusercontent.com/usbharu/graph-md/main/install.sh \
  | sh -s -- --version v0.1.0 --install-dir "$HOME/bin"
```

`GRAPHMD_VERSION` and `GRAPHMD_INSTALL_DIR` provide the same settings; explicit
arguments take precedence. Add the chosen directory to `PATH` if it is not
already present, for example:

```sh
export PATH="$HOME/.local/bin:$PATH"
```

On Windows x64, run the PowerShell installer:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/usbharu/graph-md/main/install.ps1)))
```

It installs to `%LOCALAPPDATA%\Programs\GraphMD\bin\graphmd.exe`. Environment
variables work well with the piped form:

```powershell
$env:GRAPHMD_VERSION = "0.1.0"
$env:GRAPHMD_INSTALL_DIR = "$HOME\bin"
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/usbharu/graph-md/main/install.ps1)))
```

Alternatively, download the script and pass `--version` or `--install-dir`
directly. The installer prints a reminder when its destination is not on `PATH`;
it never edits shell profiles or the Windows user environment automatically.

## Generate a static Wiki project

```text
graphmd site OUTPUT [paths...] [--base PATH] [--force] [--json]
```

From the repository, run the JVM target with:

```bash
./gradlew :cli:run --args="site ./wiki ./documents --base /wiki/"
```

| Argument | Meaning |
| --- | --- |
| `OUTPUT` | New or empty directory that receives the Astro project |
| `paths...` | GraphMD files or directories; defaults to the current directory |
| `--base PATH` | Public URL prefix beginning with `/`; defaults to `/` and is normalized with a trailing slash |
| `--force` | Replaces an existing non-empty output, while retaining its `node_modules/` directory |
| `--json` | Emits a machine-readable generation summary and diagnostics |

The command validates the workspace before writing. Compilation errors stop
generation with exit code 1. Warnings are reported but do not prevent output.
The CLI rejects the filesystem root, the current directory, a source file
itself, non-directory output, and a non-empty output without `--force`.

On success, the project contains the tracked Wiki template, vendored Astro
integration, browser runtimes, dependency metadata, and a generated
`graphmd.config.mjs`. The config points to the canonical input roots; it does
not copy the Markdown sources.

Text output reports the destination and document count. JSON output contains
`outputDirectory`, `documents`, `routes`, and warning `diagnostics`.

Build and deploy the result with:

```bash
cd wiki
pnpm install --frozen-lockfile
pnpm build
# deploy dist/
```

The complete architecture is documented in
[Static Wiki generation](../docs/site-generation.md).

## Site-related Gradle tasks

### `generateSiteTemplate`

Packages the staged Astro project as generated Kotlin source. All
`compileKotlin*` tasks depend on it, so it normally runs automatically.

```bash
./gradlew :cli:generateSiteTemplate
```

Its output is `cli/build/generated/siteTemplate/` and must not be edited or
committed.

### `stageSiteTemplate`

Copies the clean `site-template/` tree to
`cli/build/generated/siteTemplateFiles/`, builds and vendors
`graph-md-astro`, and rewrites its local package path for the generated
project. It runs automatically through `generateSiteTemplate`.

```bash
./gradlew :cli:stageSiteTemplate
```

### `updateEmbeddedWebRuntime`

Rebuilds both checked-in browser runtimes after the public Kotlin/JS query API
or `markdown-it-graphmd` changes:

```bash
./gradlew :cli:updateEmbeddedWebRuntime
```

This maintenance task requires Node.js and pnpm. Its dependencies build the
query bundle and Markdown renderer before writing:

- `site-template/runtime-encoded/graph-md-query-runtime.js.gz.b64`
- `site-template/runtime-encoded/markdown-it-graphmd.js.gz.b64`

Commit both changed encoded files. Do not commit intermediate files under
`cli/build/`, `query/build/`, `core/build/`, or `markdown-it-graphmd/dist/`.

For Gradle daemons that cannot inherit version-manager paths:

```bash
./gradlew :cli:updateEmbeddedWebRuntime \
  -PpnpmExecutable=/absolute/path/to/pnpm \
  -PnodeExecutable=/absolute/path/to/node
```

The subordinate tasks `syncMarkdownCoreVendor`,
`bundleMarkdownItGraphMd`, and `bundleQueryWebRuntime` are implementation
steps and normally should not be invoked individually. Their exact inputs,
outputs, and dependency graph are listed in
[the project-wide site guide](../docs/site-generation.md#gradle-task-reference).

## Release artifacts

The module also defines `jvmReleaseJar`, `jsReleaseArchive`,
`macosArm64ReleaseArchive`, `macosX64ReleaseArchive`,
`linuxX64ReleaseArchive`, and `mingwX64ReleaseArchive`. These package the CLI;
they do not build or deploy a generated Wiki's `dist/` directory. Tagged releases
also publish `SHA256SUMS`, which is required by both native CLI installers.
