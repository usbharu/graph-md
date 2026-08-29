package dev.usbharu.graphmd.astro

import dev.usbharu.graphmd.core.model.SourceDocument
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphMdAstroCompilerTest {
    @Test
    fun `builds normalized graph and static search artifacts`() {
        val result = GraphMdAstroCompiler().compile(
            listOf(
                source("Person", "NodeType"),
                source("knows", "RelType"),
                source("alice", "Node", "type: Person", "@link[Bob](bob knows)"),
                source("bob", "Node", "type: Person\nprops:\n  name: Bob"),
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

    @Test
    fun `encodes the complete wiki view model without a generated file`() {
        val result = GraphMdAstroCompiler().compile(
            listOf(
                source("Living", "NodeType"),
                source("Person", "NodeType", "extends: [Living]\nprops:\n  name: { type: string, required: true }"),
                source("Idol", "NodeType", "extends: [Person]"),
                source("friend", "RelType", "from: [Person]\nto: [Person]"),
                source("alice", "Node", "type: Person\nprops:\n  name: Alice", "@link[Bob](bob friend)"),
                source("bob", "Node", "type: Person"),
            ),
        )

        val site = WikiSiteEncoder("/wiki", result.documents, result.graph, result.sources).encode()

        assertTrue(site.contains("\"base\":\"/wiki/\""))
        assertTrue(site.contains("\"schema\":[{\"name\":\"name\""))
        assertTrue(site.contains("\"nodeType\":{\"parents\":[{\"id\":\"Living\""))
        assertTrue(site.contains("\"children\":[{\"id\":\"Idol\""))
        assertTrue(site.contains("\"usage\":[{\"id\":\"alice\""))
        assertTrue(site.contains("\"properties\":[{\"name\":\"name\",\"value\":\"Alice\""))
        assertTrue(site.contains("\"relationUsage\":[{\"from\":\"alice\""))
    }

    @Test
    fun `resolves query and backlink embeds for the Astro renderer`() {
        val sources = listOf(
            source("Person", "NodeType"),
            source("friend", "RelType", "from: [Person]\nto: [Person]"),
            source("alice", "Node", "type: Person", "@link[Bob](bob friend)"),
            source(
                "bob",
                "Node",
                "type: Person",
                """
                ::: embed:query="MATCH (n:Person) RETURN ID(n) AS id ORDER BY id"
                stale
                :::
                ::: embed:back-link=friend
                stale
                :::
                """.trimIndent(),
            ),
        )
        val result = GraphMdAstroCompiler().compile(sources)

        val site = WikiSiteEncoder("/", result.documents, result.graph, result.sources).encode()

        assertTrue(site.contains("\"kind\":\"query\",\"value\":\"MATCH (n:Person) RETURN ID(n) AS id ORDER BY id\",\"status\":\"ready\""))
        assertTrue(site.contains("\"kind\":\"back-link\",\"value\":\"friend\",\"status\":\"ready\""))
        assertTrue(site.contains("\"targetId\":\"alice\""))
    }

    @Test
    fun `site view rejects executable document URLs`() {
        val result = GraphMdAstroCompiler().compile(
            listOf(
                source("Person", "NodeType"),
                source("alice", "Node", "type: Person\nurl: javascript:alert(1)"),
                source("bob", "Node", "type: Person\nurl: https://example.com/bob"),
            ),
        )

        val site = WikiSiteEncoder("/", result.documents, result.graph, result.sources).encode()

        assertTrue(site.contains("\"id\":\"alice\",\"slug\":\"alice\""))
        assertTrue(site.contains("\"url\":null"))
        assertTrue(site.contains("\"url\":\"https://example.com/bob\""))
    }

    @Test
    fun `site slugs remain distinct on case insensitive filesystems`() {
        assertEquals("alice", safeSlug("alice"))
        assertEquals("~41lice", safeSlug("Alice"))
        assertEquals("~2E", safeSlug("."))
        assertEquals("~2E~2E", safeSlug(".."))
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
