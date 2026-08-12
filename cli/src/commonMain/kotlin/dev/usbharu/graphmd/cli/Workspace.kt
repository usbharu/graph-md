package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.model.SourceDocument
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.writeString

internal enum class FileKind { File, Directory, Other }

internal interface CliFileSystem {
    fun kind(path: String): FileKind?
    fun canonical(path: String): String
    fun children(path: String): List<String>
    fun readText(path: String): String
    fun child(path: String, name: String): String
    fun createDirectories(path: String)
    fun writeText(path: String, text: String)
    fun move(source: String, destination: String)
    fun delete(path: String, mustExist: Boolean = true)
}

internal object SystemCliFileSystem : CliFileSystem {
    override fun kind(path: String): FileKind? {
        val metadata = SystemFileSystem.metadataOrNull(Path(path)) ?: return null
        return when {
            metadata.isRegularFile -> FileKind.File
            metadata.isDirectory -> FileKind.Directory
            else -> FileKind.Other
        }
    }

    override fun canonical(path: String): String {
        val requested = Path(path)
        if (SystemFileSystem.metadataOrNull(requested) != null) {
            return SystemFileSystem.resolve(requested).toString()
        }
        val parent = requested.parent ?: Path(".")
        return Path(canonical(parent.toString()), requested.name).toString()
    }

    override fun children(path: String): List<String> =
        SystemFileSystem.list(Path(path)).map { it.toString() }

    override fun readText(path: String): String =
        SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray().decodeToString() }

    override fun child(path: String, name: String): String = Path(Path(path), name).toString()

    override fun createDirectories(path: String) {
        SystemFileSystem.createDirectories(Path(path))
    }

    override fun writeText(path: String, text: String) {
        SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
    }

    override fun move(source: String, destination: String) {
        SystemFileSystem.atomicMove(Path(source), Path(destination))
    }

    override fun delete(path: String, mustExist: Boolean) {
        SystemFileSystem.delete(Path(path), mustExist)
    }
}

internal class WorkspaceLoader(
    private val fileSystem: CliFileSystem,
) {
    private val excludedDirectories = setOf(".git", "node_modules", "build", "dist")

    fun load(paths: List<String>): List<SourceDocument> {
        val requested = paths.ifEmpty { listOf(".") }
        val files = linkedMapOf<String, SourceDocument>()
        requested.forEach { input ->
            when (fileSystem.kind(input)) {
                FileKind.File -> addFile(input, explicit = true, files)
                FileKind.Directory -> walk(input, files)
                FileKind.Other -> throw CliIoException("Unsupported filesystem entry: $input")
                null -> throw CliIoException("Path does not exist: $input")
            }
        }
        return files.values.sortedBy { normalizePath(it.sourcePath) }
    }

    private fun walk(path: String, files: MutableMap<String, SourceDocument>) {
        val canonicalDirectory = canonical(path)
        fileSystem.children(canonicalDirectory)
            .sortedBy(::normalizePath)
            .forEach { child ->
                val name = normalizePath(child).substringAfterLast('/')
                if (name in excludedDirectories) return@forEach
                val canonicalChild = runCatching { canonical(child) }.getOrNull() ?: return@forEach
                if (normalizePath(canonicalChild) != normalizePath(child)) return@forEach
                when (fileSystem.kind(child)) {
                    FileKind.Directory -> walk(child, files)
                    FileKind.File -> if (name.endsWith(".md", ignoreCase = true)) {
                        addFile(child, explicit = false, files)
                    }
                    else -> Unit
                }
            }
    }

    private fun addFile(
        path: String,
        explicit: Boolean,
        files: MutableMap<String, SourceDocument>,
    ) {
        val canonical = canonical(path)
        if (canonical in files) return
        val text = try {
            fileSystem.readText(canonical)
        } catch (exception: Throwable) {
            throw CliIoException("Cannot read $path: ${exception.message ?: "I/O error"}")
        }
        val firstLine = text.substringBefore('\n').removeSuffix("\r")
        if (explicit || firstLine == "---") {
            files[canonical] = SourceDocument(text, canonical)
        }
    }

    private fun canonical(path: String): String = try {
        fileSystem.canonical(path)
    } catch (exception: Throwable) {
        throw CliIoException("Cannot resolve $path: ${exception.message ?: "I/O error"}")
    }

    private fun normalizePath(path: String): String = path.replace('\\', '/').removeSuffix("/")
}

internal class CliIoException(message: String) : RuntimeException(message)
