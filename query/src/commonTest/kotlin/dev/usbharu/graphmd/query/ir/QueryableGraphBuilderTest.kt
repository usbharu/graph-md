package dev.usbharu.graphmd.query.ir

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.core.model.StringValue
import dev.usbharu.graphmd.query.model.NodeId
import dev.usbharu.graphmd.query.model.PropertyPath
import dev.usbharu.graphmd.query.model.TimelineId
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

    @Test
    fun `builds a partial graph when source references unknown IDs`() {
        val sources = listOf(
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
                      age:
                        type: number
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
                      - timeline: MissingTimeline
                      - timeline: TimelineA
                    props:
                      age:
                        - value: 1
                          validTime:
                            - timeline: MissingTimeline
                        - value: 2
                          validTime:
                            - timeline: TimelineA
                    ---
                    @link(validTime=MissingTimeline)[Bob](bob friendOf)
                """.trimIndent(),
            ),
            SourceDocument(
                sourcePath = "/graph/bob.md",
                text = """
                    ---
                    id: bob
                    kind: Node
                    type: Person
                    ---
                """.trimIndent(),
            ),
        )
        val compilation = GraphCompiler().compileSources(sources)

        val graph = QueryableGraphBuilder(sources).build(compilation)

        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.relationAssertions.size)
        assertTrue(compilation.diagnostics.any { "Unknown Timeline: MissingTimeline" in it.message })
        assertTrue(graph.nodes.single { it.id == NodeId("alice") }.validTime.intervals.all {
            it.timelineId == TimelineId("TimelineA")
        })
    }

    @Test
    fun `splits markdown text at body blocks and assigns nearest validTime`() {
        val sources = listOf(
            SourceDocument(
                """
                ---
                id: A
                kind: Timeline
                ---
                """.trimIndent(),
                "/graph/a.md",
            ),
            SourceDocument(
                """
                ---
                id: B
                kind: Timeline
                ---
                """.trimIndent(),
                "/graph/b.md",
            ),
            SourceDocument(
                """
                ---
                id: Person
                kind: NodeType
                ---
                """.trimIndent(),
                "/graph/person.md",
            ),
            SourceDocument(
                """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                outside
                ::: history validTime=A(from=0,to=10)
                outer
                ::::: inherited
                inherited prose
                :::::
                ::::: branch validTime=B(from=20,to=30)
                inner
                :::::
                :::
                after
                """.trimIndent(),
                "/graph/alice.md",
            ),
        )
        val graph = QueryableGraphBuilder(sources).build(GraphCompiler().compileSources(sources))

        val body = graph.textAssertions.filter { it.kind == TextKind.PARAGRAPH }
        assertTrue(body.none { ":::" in it.text })
        val outer = body.single { it.text == "outer" }
        val inherited = body.single { it.text == "inherited prose" }
        val inner = body.single { it.text == "inner" }
        val outside = body.single { it.text == "outside" }
        assertTrue(outer.validTime.contains(TimelineId("A"), 5.0))
        assertTrue(inherited.validTime.contains(TimelineId("A"), 5.0))
        assertFalse(inherited.validTime.contains(TimelineId("B"), 25.0))
        assertTrue(inner.validTime.contains(TimelineId("B"), 25.0))
        assertTrue(outside.validTime.isUniversal)
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
