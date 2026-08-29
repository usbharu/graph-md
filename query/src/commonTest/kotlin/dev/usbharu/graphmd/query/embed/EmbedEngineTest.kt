package dev.usbharu.graphmd.query.embed

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.EmbedDirective
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.GraphSearchEngine
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbedEngineTest {
    @Test
    fun `renders query results and enforces the embed row limit`() {
        val engine = fixtureEngine()

        val result = runSuspend {
            EmbedEngine(engine).render(
                EmbedDirective.Query("MATCH (n:Person) RETURN ID(n) AS id ORDER BY id LIMIT 2"),
                "bob",
            )
        }
        val limited = runSuspend {
            EmbedEngine(engine).render(
                EmbedDirective.Query("MATCH (n:Person) RETURN ID(n) AS id LIMIT 101"),
                "bob",
            )
        }

        assertTrue(result.isSuccess)
        assertEquals(listOf("alice", "bob"), result.table!!.rows.map { it.cells.single().text })
        assertEquals(listOf("alice", "bob"), result.table.rows.map { it.cells.single().targetId })
        assertFalse(limited.isSuccess)
        assertEquals("GMQL5001", limited.diagnostics.single().code)
    }

    @Test
    fun `renders exact incoming backlinks in stable order with linked ids`() {
        val result = runSuspend {
            EmbedEngine(fixtureEngine()).render(EmbedDirective.BackLink("friendOf"), "bob")
        }

        assertTrue(result.isSuccess)
        val table = checkNotNull(result.table)
        assertEquals(listOf("id", "type", "validity"), table.columns.map { it.name })
        assertEquals(listOf("alice", "carol"), table.rows.map { it.cells.first().text })
        assertEquals("alice", table.rows.first().cells.first().targetId)
        assertEquals("Anytime", table.rows.first().cells.last().text)
    }

    @Test
    fun `markdown output escapes cells and resolves only linked targets`() {
        val table = EmbedTable(
            listOf(EmbedColumn("na|me", "string")),
            listOf(EmbedRow(listOf(EmbedCell("[a]|<b>\nline", "alice")))),
        )

        val markdown = table.toMarkdown { "docs/$it.md" }

        assertTrue("na\\|me" in markdown)
        assertTrue("&lt;b&gt;" in markdown)
        assertTrue("<br>" in markdown)
        assertTrue("(docs/alice.md)" in markdown)
    }

    private fun fixtureEngine(): GraphSearchEngine {
        val sources = listOf(
            SourceDocument(
                """
                ---
                id: Person
                kind: NodeType
                ---
                """.trimIndent(),
                "/graph/Person.md",
            ),
            SourceDocument(
                """
                ---
                id: friendOf
                kind: RelType
                from: [Person]
                to: [Person]
                ---
                """.trimIndent(),
                "/graph/friendOf.md",
            ),
            SourceDocument(node("alice", "@link[Bob](bob friendOf)"), "/graph/alice.md"),
            SourceDocument(node("bob", ""), "/graph/bob.md"),
            SourceDocument(node("carol", "@link[Bob](bob friendOf)"), "/graph/carol.md"),
        )
        return GraphSearchEngine.build(GraphCompiler().compileSources(sources), sources)
    }

    private fun node(id: String, body: String): String = """
        ---
        id: $id
        kind: Node
        type: Person
        ---
        $body
    """.trimIndent()
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return checkNotNull(outcome).getOrThrow()
}
