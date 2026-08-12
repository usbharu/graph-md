package dev.usbharu.graphmd.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemCliFileSystemTest {
    @Test
    fun `index can be created at a path that does not exist yet`() {
        val root = Files.createTempDirectory("graphmd-cli-index")
        try {
            val documents = root.resolve("documents")
            Files.createDirectories(documents)
            Files.writeString(documents.resolve("Person.md"), "---\nid: Person\nkind: NodeType\n---")
            Files.writeString(
                documents.resolve("alice.md"),
                "---\nid: alice\nkind: Node\ntype: Person\n---\n勇者の記録",
            )
            val index = root.resolve("index")
            assertFalse(Files.exists(index))

            val built = GraphMdCli().run(
                listOf("index", "--output", index.toString(), documents.toString()),
            )
            val searched = GraphMdCli().run(
                listOf(
                    "search",
                    "MATCH (n) WHERE FULLTEXT(n, \"勇者\") RETURN ID(n) AS id",
                    "--index",
                    index.toString(),
                ),
            )

            assertEquals(0, built.exitCode, built.stderr)
            assertTrue(Files.exists(index.resolve("manifest.json")))
            assertEquals(0, searched.exitCode, searched.stderr)
            assertTrue(searched.stdout.contains("alice"), searched.stdout)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
