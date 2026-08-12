package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.Diagnostic
import dev.usbharu.graphmd.core.model.Severity

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

    val output = resolveSiteOutput(command) ?: return CliResult(
        stderr = "Output parent directory does not exist: ${command.outputDirectory}\n",
        exitCode = 2,
    )
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
    val sourceRoots = command.paths.ifEmpty { listOf(".") }
        .map(fileSystem::canonical)
        .distinct()
    val files = generatedSiteTemplateFiles.toMutableMap().apply {
        put(
            "graphmd.config.mjs",
            "export default { base: ${jsonString(command.base).encode()}, roots: ${jsonArray(sourceRoots.map(::jsonString)).encode()} };\n",
        )
    }

    try {
        if (fileSystem.kind(output) == FileKind.Directory && command.force) clearOutput(fileSystem, output)
        fileSystem.createDirectories(output)
        files.forEach { (relative, content) ->
            val target = relative.split('/').fold(output) { parent, child -> fileSystem.child(parent, child) }
            fileSystem.createDirectories(target.substringBeforeLast('/', output))
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
        "diagnostics" to jsonArray(warnings.map(Diagnostic::toJson)),
    )
    return if (json) CliResult(stdout = summary.encode() + "\n") else CliResult(
        stdout = "Generated Astro site project in $output (${documents.size} documents)\n",
        stderr = warnings.joinToString("") { "${it.source?.path ?: "<workspace>"}: warning: ${it.message}\n" },
    )
}

private fun GraphMdCli.resolveSiteOutput(command: CliCommand.Site): String? {
    if (fileSystem.kind(command.outputDirectory) != null) return fileSystem.canonical(command.outputDirectory)
    val normalized = command.outputDirectory.replace('\\', '/').trimEnd('/')
    val parent = normalized.substringBeforeLast('/', missingDelimiterValue = ".").ifEmpty { "/" }
    val name = normalized.substringAfterLast('/')
    if (name.isEmpty() || fileSystem.kind(parent) != FileKind.Directory) return null
    return fileSystem.child(fileSystem.canonical(parent), name)
}

private fun clearOutput(fileSystem: CliFileSystem, output: String) {
    fileSystem.children(output).forEach { child ->
        val name = child.replace('\\', '/').substringAfterLast('/')
        if (name != "node_modules") deleteTree(fileSystem, child)
    }
}

private fun deleteTree(fileSystem: CliFileSystem, path: String) {
    if (fileSystem.kind(path) == FileKind.Directory) fileSystem.children(path).forEach { child ->
        val canonical = runCatching { fileSystem.canonical(child) }.getOrNull()
        if (canonical != null && canonical.replace('\\', '/') != child.replace('\\', '/')) {
            fileSystem.delete(child, mustExist = false)
        } else deleteTree(fileSystem, child)
    }
    try {
        fileSystem.delete(path, mustExist = false)
    } catch (exception: Throwable) {
        throw CliIoException("Cannot delete $path: ${exception.message ?: "deletion failed"}")
    }
}

internal fun safeSlug(id: String): String = buildString {
    id.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character in setOf('_', '-', '.')
        ) append(character) else append('~').append(value.toString(16).uppercase().padStart(2, '0'))
    }
}
