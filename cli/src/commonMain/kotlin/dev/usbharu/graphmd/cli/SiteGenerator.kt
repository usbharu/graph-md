package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.GraphSearchEngine

internal fun GraphMdCli.site(command: CliCommand.Site, json: Boolean): CliResult {
    val sources = WorkspaceLoader(fileSystem).load(command.paths)
    val compiler = GraphCompiler()
    val parsed = sources.map { compiler.parseDocument(it.text, it.sourcePath) }
    val compilation = compiler.compileParsed(parsed)
    val errors = compilation.diagnostics.filter { it.severity == Severity.Error }
    if (errors.isNotEmpty()) {
        val stderr = if (json) jsonArray(errors.map(Diagnostic::toJson)).encode() + "\n"
        else errors.joinToString("") { "${it.source?.path ?: "<workspace>"}: ${it.message}\n" }
        return CliResult(stderr = stderr, exitCode = 1)
    }

    val output = if (fileSystem.kind(command.outputDirectory) == null) {
        val normalized = command.outputDirectory.replace('\\', '/').trimEnd('/')
        val parent = normalized.substringBeforeLast('/', missingDelimiterValue = ".").ifEmpty { "/" }
        val name = normalized.substringAfterLast('/')
        if (name.isEmpty() || fileSystem.kind(parent) != FileKind.Directory) {
            return CliResult(stderr = "Output parent directory does not exist: $parent\n", exitCode = 2)
        }
        fileSystem.child(fileSystem.canonical(parent), name)
    } else {
        fileSystem.canonical(command.outputDirectory)
    }
    val current = fileSystem.canonical(".")
    if (output == "/" || output == current || sources.any { fileSystem.canonical(it.sourcePath) == output }) {
        return CliResult(stderr = "Refusing unsafe site output directory: ${command.outputDirectory}\n", exitCode = 2)
    }
    when (fileSystem.kind(output)) {
        FileKind.File, FileKind.Other -> return CliResult(stderr = "Output path is not a directory: $output\n", exitCode = 1)
        FileKind.Directory -> if (fileSystem.children(output).isNotEmpty() && !command.force) {
            return CliResult(stderr = "Output directory must be empty (use --force to replace it): $output\n", exitCode = 1)
        }
        null -> Unit
    }

    val documents = parsed.mapNotNull { it.document }.sortedBy { it.id }
    val generator = AstroSiteGenerator(command.base, documents, compilation)
    val files = generator.files().toMutableMap()
    val search = GraphSearchEngine.build(compilation, sources).exportStatic()
    search.files().forEach { (name, content) -> files["public/search-index/$name"] = content }

    try {
        if (fileSystem.kind(output) == FileKind.Directory && command.force) clearOutput(fileSystem, output)
        fileSystem.createDirectories(output)
        files.forEach { (relative, content) ->
            val target = relative.split('/').fold(output) { parent, child -> fileSystem.child(parent, child) }
            val parent = target.substringBeforeLast('/', output)
            fileSystem.createDirectories(parent)
            fileSystem.writeText(target, content)
        }
    } catch (exception: Throwable) {
        return CliResult(stderr = "Cannot generate site in $output: ${exception.message ?: "I/O error"}\n", exitCode = 1)
    }

    val warnings = compilation.diagnostics.filter { it.severity == Severity.Warning }
    val summary = jsonObject(
        "outputDirectory" to jsonString(output),
        "documents" to jsonNumber(documents.size),
        "routes" to jsonNumber(documents.size + 3),
        "searchIndexFiles" to jsonNumber(search.files().size),
        "diagnostics" to jsonArray(warnings.map(Diagnostic::toJson)),
    )
    return if (json) CliResult(stdout = summary.encode() + "\n") else CliResult(
        stdout = "Generated Astro site project in $output (${documents.size} documents)\n",
        stderr = warnings.joinToString("") { "${it.source?.path ?: "<workspace>"}: warning: ${it.message}\n" },
    )
}

private fun clearOutput(fileSystem: CliFileSystem, output: String) {
    fileSystem.children(output).forEach { child ->
        val name = child.replace('\\', '/').substringAfterLast('/')
        // Dependency installs can contain symlink forests that kotlinx-io cannot
        // portably unlink. Keep node_modules, but discard the lockfile so a
        // regenerated package.json cannot remain pinned to an incompatible set.
        if (name != "node_modules") deleteTree(fileSystem, child)
    }
}

private fun deleteTree(fileSystem: CliFileSystem, path: String) {
    if (fileSystem.kind(path) == FileKind.Directory) fileSystem.children(path).forEach { child ->
        val canonical = runCatching { fileSystem.canonical(child) }.getOrNull()
        if (canonical != null && canonical.replace('\\', '/') != child.replace('\\', '/')) {
            fileSystem.delete(child, mustExist = false)
        } else {
            deleteTree(fileSystem, child)
        }
    }
    try {
        fileSystem.delete(path, mustExist = false)
    } catch (exception: Throwable) {
        throw CliIoException("Cannot delete $path: ${exception.message ?: "deletion failed"}")
    }
}

private class AstroSiteGenerator(
    private val base: String,
    private val documents: List<GraphDocument>,
    private val graph: GraphCompilationResult,
) {
    private val routes = documents.associate { it.id to "${base}documents/${safeSlug(it.id)}/" }

    fun files(): Map<String, String> = generatedSiteTemplateFiles.toMutableMap().apply {
        put("src/generated/site.json", siteJson())
    }

    private fun siteJson(): String {
        val incoming = graph.relations.groupBy { it.to }
        val docs = documents.map { document ->
            val node = graph.nodes.firstOrNull { it.id == document.id }
            jsonObject(
                "id" to jsonString(document.id),
                "slug" to jsonString(safeSlug(document.id)),
                "route" to jsonString(routes.getValue(document.id)),
                "title" to jsonString(firstHeading(document.body) ?: document.id),
                "kind" to jsonString(document.kind.name),
                "type" to jsonNullableString(node?.type),
                "url" to jsonNullableString(node?.url),
                "body" to jsonString(document.body),
                "backlinks" to jsonArray(incoming[document.id].orEmpty().map { relation ->
                    jsonObject(
                        "id" to jsonString(relation.from),
                        "type" to jsonString(relation.type),
                        "route" to jsonNullableString(routes[relation.from]),
                    )
                }),
            )
        }
        val nodes = graph.nodes.map { node ->
            jsonObject("data" to jsonObject(
                "id" to jsonString(node.id),
                "label" to jsonString(firstHeading(documents.firstOrNull { it.id == node.id }?.body.orEmpty()) ?: node.id),
                "route" to jsonNullableString(routes[node.id]),
                "kind" to jsonString(node.kind.name),
            ))
        }
        val edges = graph.relations.mapIndexed { index, relation ->
            jsonObject("data" to jsonObject(
                "id" to jsonString("e$index"), "source" to jsonString(relation.from),
                "target" to jsonString(relation.to), "label" to jsonString(relation.type),
            ))
        }
        return jsonObject(
            "base" to jsonString(base),
            "documents" to jsonArray(docs),
            "routes" to JsonValue.Object(routes.mapValues { jsonString(it.value) }),
            "graph" to jsonObject("nodes" to jsonArray(nodes), "edges" to jsonArray(edges)),
        ).encode() + "\n"
    }
}

internal fun safeSlug(id: String): String = buildString {
    id.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in setOf('_', '-', '.')) append(character)
        else append('~').append(value.toString(16).uppercase().padStart(2, '0'))
    }
}

private fun firstHeading(body: String): String? = body.lineSequence().map(String::trim)
    .firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.trimEnd('#')?.trim()?.takeIf(String::isNotEmpty)
