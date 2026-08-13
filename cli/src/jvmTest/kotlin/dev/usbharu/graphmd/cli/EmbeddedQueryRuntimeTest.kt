package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.GraphSearchEngine
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedQueryRuntimeTest {
    @Test
    fun `embedded browser runtime loads the current static index format`() {
        val sources = listOf(
            source("Person", "NodeType"),
            source("alice", "Node", "type: Person", "Brave searchable text"),
        )
        val compiler = GraphCompiler()
        val parsed = sources.map { compiler.parseDocument(it.text, it.sourcePath) }
        val graph = compiler.compileParsed(parsed)
        val bundle = GraphSearchEngine.build(graph, sources).exportStatic()
        val directory = createTempDirectory("graphmd-embedded-runtime-test")
        try {
            val runtime = directory.resolve("runtime.js")
            val encodedRuntime = Files.readString(
                java.nio.file.Path.of(checkNotNull(System.getProperty("graphmd.embeddedQueryRuntime"))),
            )
            runtime.writeBytes(
                GZIPInputStream(ByteArrayInputStream(Base64.getMimeDecoder().decode(encodedRuntime))).use {
                    it.readBytes()
                },
            )
            directory.resolve("manifest.json").writeText(bundle.manifest)
            bundle.shards.forEach { (name, contents) -> directory.resolve(name).writeText(contents) }
            val script = directory.resolve("verify.cjs")
            script.writeText(
                """
                const fs = require("node:fs");
                const path = require("node:path");
                const vm = require("node:vm");
                const root = process.argv[2];
                const source = fs.readFileSync(path.join(root, "runtime.js"), "utf8");
                vm.runInThisContext(source + ";globalThis.__graphMdRuntime = GraphMdQueryRuntime;");
                const manifestText = fs.readFileSync(path.join(root, "manifest.json"), "utf8");
                const manifest = JSON.parse(manifestText);
                const shards = {};
                for (const name of Object.values(manifest.shards).flat()) {
                  shards[name] = fs.readFileSync(path.join(root, name), "utf8");
                }
                const api = globalThis.__graphMdRuntime.dev.usbharu.graphmd.query.GraphMdWebSearch;
                const engine = api.load(manifestText, JSON.stringify(shards));
                engine.queryGmql('MATCH (n) RETURN ID(n) AS id ORDER BY id').then((result) => {
                  const decoded = JSON.parse(result);
                  if (decoded.rows.some((row) => row[0] === "alice")) process.stdout.write("ok");
                  else throw new Error(`Unexpected query result: ${'$'}{result}`);
                }).catch((error) => { console.error(error); process.exitCode = 1; });
                """.trimIndent(),
            )

            val process = ProcessBuilder("node", script.toString(), directory.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            assertTrue(output.contains("ok"), output)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun source(id: String, kind: String, fields: String = "", body: String = "") = SourceDocument(
        buildString {
            appendLine("---")
            appendLine("id: $id")
            appendLine("kind: $kind")
            if (fields.isNotEmpty()) appendLine(fields)
            appendLine("---")
            append(body)
        },
        "$id.md",
    )
}
