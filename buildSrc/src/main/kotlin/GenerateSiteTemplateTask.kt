import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class GenerateSiteTemplateTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val root = sourceRoot.get().asFile
        val files = templateFiles.files.filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine("package dev.usbharu.graphmd.cli")
            writer.appendLine()
            writer.appendLine("internal val generatedSiteTemplateFiles: Map<String, String> = linkedMapOf(")
            files.forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                writer.appendLine("    ${path.kotlinLiteral()} to listOf(")
                file.readText().chunked(12_000).forEach { chunk ->
                    writer.appendLine("        ${chunk.kotlinLiteral()},")
                }
                writer.appendLine("    ).joinToString(\"\"),")
            }
            writer.appendLine(")")
        }
    }

    private fun String.kotlinLiteral(): String = buildString {
        append('"')
        this@kotlinLiteral.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '$' -> append("\\$")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
