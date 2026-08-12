package dev.usbharu.graphmd.astro

import dev.usbharu.graphmd.core.model.SourceDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphMdAstroCompilerTest {
    @Test
    fun `builds normalized graph and static search artifacts`() {
        val result = GraphMdAstroCompiler().compile(
            listOf(
                source("Person", "NodeType"),
                source("knows", "RelType"),
                source("alice", "Node", "type: Person", "@link[Bob](bob knows)"),
                source("bob", "Node", "type: Person"),
            ),
        )

        assertTrue(result.successful)
        assertNotNull(result.search)
        assertTrue("manifest.json" in result.search.files())
        assertTrue(result.graph.nodes.any { it.id == "alice" })
        assertTrue(result.graph.relations.any { it.from == "alice" && it.to == "bob" })
    }

    @Test
    fun `does not build search artifacts when compilation fails`() {
        val result = GraphMdAstroCompiler().compile(
            listOf(source("alice", "Node", "type: Missing")),
        )

        assertFalse(result.successful)
        assertTrue(result.search == null)
    }

    private fun source(id: String, kind: String, fields: String = "", body: String = "") = SourceDocument(
        """
        ---
        id: $id
        kind: $kind
        $fields
        ---
        $body
        """.trimIndent(),
        "$id.md",
    )
}
