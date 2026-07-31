package dev.usbharu.graphmd.query.ir

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.core.model.StringValue
import dev.usbharu.graphmd.query.model.NodeId
import dev.usbharu.graphmd.query.model.PropertyPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryableGraphBuilderTest {
    @Test
    fun `builds temporal node relation property and text assertions without changing compiler output`() {
        val sources = fixture()
        val compilation = GraphCompiler().compileSources(sources)

        val graph = QueryableGraphBuilder(sources).build(compilation)

        assertEquals(listOf(NodeId("alice"), NodeId("bob")), graph.nodes.map { it.id })
        val ages = graph.propertyAssertions.filter { it.path == PropertyPath("age") }
        assertEquals(2, ages.size)
        assertEquals(listOf(15.0, 20.0), ages.map { (it.value as dev.usbharu.graphmd.core.model.NumberValue).value })
        assertTrue(ages.all { !it.validTime.isUniversal })

        val relation = graph.relationAssertions.single()
        assertEquals(NodeId("alice"), relation.sourceNodeId)
        assertEquals(NodeId("bob"), relation.targetNodeId)
        assertEquals("friendOf", relation.relTypeId.value)
        assertEquals(0.9, (relation.properties.single().value as dev.usbharu.graphmd.core.model.NumberValue).value)

        val title = graph.textAssertions.single { it.kind == TextKind.TITLE && it.text == "Alice" }
        assertEquals("Alice", title.text)
        val paragraph = graph.textAssertions.single {
            it.kind == TextKind.PARAGRAPH && "勇者" in it.text
        }
        assertIs<AssertionOwner.Node>(paragraph.owner)
        assertTrue("Bob" in paragraph.text)
        assertFalse("@link" in paragraph.text)
        assertFalse("bob" in paragraph.text)
        assertFalse("friendOf" in paragraph.text)
        assertTrue(
            paragraph.sourceRange?.let { range ->
                sources.single { it.sourcePath == "/graph/alice.md" }.text.substring(range.start, range.end)
            }?.contains("@link") == true,
        )
        assertTrue(graph.textAssertions.any { it.kind == TextKind.PROPERTY_VALUE && it.text == "Alice" })
        assertTrue(graph.textAssertions.any { it.kind == TextKind.RELATION_LABEL && it.text == "Bob" })

        val ids = (
            graph.propertyAssertions.map { it.id } +
                graph.relationAssertions.map { it.id } +
                graph.textAssertions.map { it.id }
            )
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(compilation.nodes.size, 2)
    }

    @Test
    fun `recursively expands object and array property paths`() {
        val sources = fixture()
        val graph = QueryableGraphBuilder(sources).build(GraphCompiler().compileSources(sources))

        assertTrue(graph.propertyAssertions.any { it.path == PropertyPath("profile", "city") })
        assertTrue(graph.propertyAssertions.any { it.path == PropertyPath("tags", "0") })
        val city = graph.propertyAssertions.single { it.path == PropertyPath("profile", "city") }
        assertEquals("Tokyo", assertIs<StringValue>(city.value).value)
    }

    private fun fixture(): List<SourceDocument> = listOf(
        SourceDocument(
            sourcePath = "/graph/timeline.md",
            text = """
                ---
                id: TimelineA
                kind: Timeline
                timecode:
                  type: number
                ---
            """.trimIndent(),
        ),
        SourceDocument(
            sourcePath = "/graph/person.md",
            text = """
                ---
                id: Person
                kind: NodeType
                props:
                  name:
                    type: string
                  age:
                    type: number
                  tags:
                    type: array
                    items: string
                ---
            """.trimIndent(),
        ),
        SourceDocument(
            sourcePath = "/graph/friend.md",
            text = """
                ---
                id: friendOf
                kind: RelType
                from: [Person]
                to: [Person]
                props:
                  weight:
                    type: number
                ---
            """.trimIndent(),
        ),
        SourceDocument(
            sourcePath = "/graph/alice.md",
            text = """
                ---
                id: alice
                kind: Node
                type: Person
                validTime:
                  - timeline: TimelineA
                    from: 100
                    to: 200
                props:
                  name: Alice
                  age:
                    - value: 15
                      validTime:
                        - timeline: TimelineA
                          from: 100
                          to: 159
                    - value: 20
                      validTime:
                        - timeline: TimelineA
                          from: 160
                          to: 200
                  profile:
                    city: Tokyo
                  tags:
                    - hero
                ---
                # Alice

                Aliceは勇者として活動していた。
                @link(validTime=TimelineA(from=100,to=140)){weight=0.9}[Bob](bob friendOf)
            """.trimIndent(),
        ),
        SourceDocument(
            sourcePath = "/graph/bob.md",
            text = """
                ---
                id: bob
                kind: Node
                type: Person
                validTime:
                  - timeline: TimelineA
                    from: 100
                    to: 200
                props:
                  name: Bob
                ---
                # Bob
            """.trimIndent(),
        ),
    )
}
