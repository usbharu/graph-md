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
    val protectedPaths = buildList {
        add(fileSystem.canonical("."))
        command.paths.ifEmpty { listOf(".") }.mapTo(this, fileSystem::canonical)
        sources.mapTo(this) { fileSystem.canonical(it.sourcePath) }
    }
    if (isFileSystemRoot(output) || protectedPaths.any { isSameOrAncestor(output, it) }) {
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

    val staging = unusedSibling(fileSystem, output, "graphmd-tmp")
    try {
        fileSystem.createDirectories(staging)
        files.forEach { (relative, content) ->
            val segments = relative.split('/')
            val parent = segments.dropLast(1).fold(staging) { directory, child ->
                fileSystem.child(directory, child).also(fileSystem::createDirectories)
            }
            val target = fileSystem.child(parent, segments.last())
            fileSystem.writeText(target, content)
        }
        installStagedSite(fileSystem, staging, output)
    } catch (exception: Throwable) {
        runCatching { deleteTree(fileSystem, staging) }
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

private fun installStagedSite(fileSystem: CliFileSystem, staging: String, output: String) {
    if (fileSystem.kind(output) != FileKind.Directory) {
        fileSystem.atomicMove(staging, output)
        return
    }
    val backup = unusedSibling(fileSystem, output, "graphmd-backup")
    fileSystem.atomicMove(output, backup)
    val oldModules = fileSystem.child(backup, "node_modules")
    val stagedModules = fileSystem.child(staging, "node_modules")
    var modulesMoved = false
    try {
        if (fileSystem.kind(oldModules) == FileKind.Directory) {
            fileSystem.atomicMove(oldModules, stagedModules)
            modulesMoved = true
        }
        fileSystem.atomicMove(staging, output)
    } catch (exception: Throwable) {
        if (modulesMoved && fileSystem.kind(stagedModules) == FileKind.Directory) {
            runCatching { fileSystem.atomicMove(stagedModules, oldModules) }
        }
        if (fileSystem.kind(output) == FileKind.Directory) runCatching { deleteTree(fileSystem, output) }
        if (fileSystem.kind(backup) == FileKind.Directory) runCatching { fileSystem.atomicMove(backup, output) }
        throw exception
    }
    deleteTree(fileSystem, backup)
}

private fun unusedSibling(fileSystem: CliFileSystem, output: String, suffix: String): String {
    for (index in 0..999) {
        val candidate = "$output.$suffix-$index"
        if (fileSystem.kind(candidate) == null) return candidate
    }
    throw CliIoException("Cannot allocate a temporary output directory beside $output")
}

private fun deleteTree(fileSystem: CliFileSystem, path: String) {
    if (fileSystem.kind(path) == null) return
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

private fun isFileSystemRoot(path: String): Boolean {
    val normalized = normalizeSafetyPath(path)
    return normalized == "/" || WINDOWS_VOLUME_ROOT.matches(normalized) || UNC_ROOT.matches(normalized)
}

private fun isSameOrAncestor(ancestor: String, candidate: String): Boolean {
    val normalizedAncestor = normalizeSafetyPath(ancestor)
    val normalizedCandidate = normalizeSafetyPath(candidate)
    val ignoreCase = WINDOWS_PATH.matches(normalizedAncestor) || WINDOWS_PATH.matches(normalizedCandidate)
    return normalizedCandidate.equals(normalizedAncestor, ignoreCase) ||
        normalizedCandidate.startsWith("$normalizedAncestor/", ignoreCase)
}

private fun normalizeSafetyPath(path: String): String {
    val normalized = path.replace('\\', '/')
    return if (normalized == "/") normalized else normalized.trimEnd('/')
}

private val WINDOWS_PATH = Regex("^[A-Za-z]:/.*")
private val WINDOWS_VOLUME_ROOT = Regex("^[A-Za-z]:$")
private val UNC_ROOT = Regex("^//[^/]+/[^/]+$")

internal fun safeSlug(id: String): String = buildString {
    id.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        val character = value.toChar()
        if (character in 'a'..'z' || character in '0'..'9' || character in setOf('_', '-')
        ) append(character) else append('~').append(value.toString(16).uppercase().padStart(2, '0'))
    }
}
