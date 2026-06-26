package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphDocumentParserTest {
    private val compiler = GraphCompiler()

    @Test
    fun `parses node document from markdown front matter`() {
        val parsed = compiler.parseDocument(
            text = """
                ---
                id: alice
                kind: Node
                type: Person
                props:
                  name: Alice
                  aliases:
                    - Al
                  birthDate:
                    timeline: CommonEra
                    value: "AD 2001-04-12"
                ---
                Alice is friends with @[Bob](bob friendOf)
            """.trimIndent(),
            sourcePath = "/tmp/alice.md",
        )

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.joinToString("\n") { it.message })
        val document = parsed.document as? NodeDocument
        assertNotNull(document)
        assertEquals("alice", document.id)
        assertEquals("Person", document.type)
        assertEquals("Alice", (document.props.getValue("name") as RawString).value)
        assertEquals("Al", ((document.props.getValue("aliases") as RawArray).values.single() as RawString).value)
        assertTrue(document.body.contains("@[Bob](bob friendOf)"))
        assertTrue("type" in document.topLevelFields)
    }

    @Test
    fun `parses node type rel type and timeline documents`() {
        val nodeType = compiler.parseDocument(
            """
                ---
                id: Person
                kind: NodeType
                extends:
                  - Entity
                props:
                  name:
                    type: text
                    required: true
                    index: fulltext
                ---
            """.trimIndent(),
            "/tmp/person.md",
        ).document as? NodeTypeDocument
        val relType = compiler.parseDocument(
            """
                ---
                id: worksAt
                kind: RelType
                from:
                  - Person
                to:
                  - Organization
                props:
                  since:
                    type: instant
                    timeline: CommonEra
                ---
            """.trimIndent(),
            "/tmp/works-at.md",
        ).document as? RelTypeDocument
        val timeline = compiler.parseDocument(
            """
                ---
                id: CommonEra
                kind: Timeline
                timecode:
                  type: number
                  direction: ascending
                mappings:
                  - kind: none
                ---
            """.trimIndent(),
            "/tmp/timeline.md",
        ).document as? TimelineDocument

        assertNotNull(nodeType)
        assertEquals(listOf("Entity"), nodeType.extends)
        assertEquals(PropType.text, nodeType.props.getValue("name").type)
        assertNotNull(relType)
        assertEquals(listOf("Person"), relType.from)
        assertEquals(TimelineSelector.Id("CommonEra"), relType.props.getValue("since").timeline)
        assertNotNull(timeline)
        assertEquals(TimecodeType.number, timeline.timecode?.type)
        assertEquals(TimecodeDirection.ascending, timeline.timecode?.direction)
        assertEquals(1, timeline.mappings.size)
        assertTrue(timeline.mappings.single() is NoTimelineMapping)
    }

    @Test
    fun `parses timeline selector forms including mapped`() {
        val nodeType = compiler.parseDocument(
            """
                ---
                id: Event
                kind: NodeType
                props:
                  byId:
                    type: instant
                    timeline: CommonEra
                  any:
                    type: instant
                    timeline: any
                  mapped:
                    type: instant
                    timeline:
                      mapped: CommonEra
                  mixed:
                    type: instant
                    timelines:
                      - ThirdAge
                      - any
                      - mapped: CommonEra
                ---
            """.trimIndent(),
            "/tmp/event.md",
        ).document as? NodeTypeDocument

        assertNotNull(nodeType)
        assertEquals(TimelineSelector.Id("CommonEra"), nodeType.props.getValue("byId").timeline)
        assertEquals(TimelineSelector.Any, nodeType.props.getValue("any").timeline)
        assertEquals(TimelineSelector.Mapped("CommonEra"), nodeType.props.getValue("mapped").timeline)
        assertEquals(
            listOf(TimelineSelector.Id("ThirdAge"), TimelineSelector.Any, TimelineSelector.Mapped("CommonEra")),
            nodeType.props.getValue("mixed").timelines,
        )
    }

    @Test
    fun `parses tuple timecode structures`() {
        val timeline = compiler.parseDocument(
            """
                ---
                id: ThirdAge
                kind: Timeline
                timecode:
                  type: tuple
                mappings:
                  - kind: table
                    to: CommonEra
                    entries:
                      - from:
                          value: "TA 3018-09-23"
                          timecode: [3018, 9, 23]
                        to:
                          value: "AD 2000-09-23"
                          timecode: 2000.73
                ---
            """.trimIndent(),
            "/tmp/third-age.md",
        ).document as? TimelineDocument

        assertNotNull(timeline)
        assertEquals(TimecodeType.tuple, timeline.timecode?.type)
        val mapping = timeline.mappings.singleOrNull() as? TableTimelineMapping
        assertNotNull(mapping)
        val entry = mapping.entries.single()
        assertEquals("TA 3018-09-23", entry.from)
        assertEquals(listOf(3018.0, 9.0, 23.0), (entry.fromTimecode as TupleTimecode).values)
        assertEquals(2000.73, (entry.toTimecode as NumberTimecode).value)
    }

    @Test
    fun `treats empty optional mappings as omitted`() {
        val timeline = compiler.parseDocument(
            """
                ---
                id: ThirdAge
                kind: Timeline
                mappings:
                ---
            """.trimIndent(),
            "/tmp/third-age.md",
        )

        assertTrue(timeline.diagnostics.isEmpty(), timeline.diagnostics.joinToString("\n") { it.message })
        assertTrue((timeline.document as? TimelineDocument)?.mappings?.isEmpty() == true)
    }

    @Test
    fun `legacy timeline fields are reported as unknown`() {
        val timeline = compiler.parseDocument(
            """
                ---
                id: ThirdAge
                kind: Timeline
                calendar:
                mapping:
                  kind: none
                ---
            """.trimIndent(),
            "/tmp/third-age.md",
        )

        assertTrue(timeline.diagnostics.any { it.message == "Unknown top-level field: calendar" })
        assertTrue(timeline.diagnostics.any { it.message == "Unknown top-level field: mapping" })
    }

    @Test
    fun `missing timecode metadata fields are allowed`() {
        val timeline = compiler.parseDocument(
            """
                ---
                id: CommonEra
                kind: Timeline
                timecode:
                  type: number
                ---
            """.trimIndent(),
            "/tmp/common-era.md",
        )

        assertTrue(timeline.diagnostics.isEmpty(), timeline.diagnostics.joinToString("\n") { it.message })
    }

    @Test
    fun `reports missing front matter and invalid kind`() {
        val missingFrontMatter = compiler.parseDocument(
            text = "id: alice\nkind: Node",
            sourcePath = "/tmp/no-frontmatter.md",
        )
        val invalidKind = compiler.parseDocument(
            text = """
                ---
                id: alice
                kind: Unknown
                ---
            """.trimIndent(),
            sourcePath = "/tmp/unknown.md",
        )

        assertNull(missingFrontMatter.document)
        assertTrue(missingFrontMatter.diagnostics.any { "YAML front matter" in it.message })
        assertNull(invalidKind.document)
        assertTrue(invalidKind.diagnostics.any { "Unknown document kind" in it.message })
    }

    @Test
    fun `compile sources resolves references across documents`() {
        val result = compiler.compileSources(
            listOf(
                SourceDocument(
                    text = """
                        ---
                        id: CommonEra
                        kind: Timeline
                        timecode:
                          type: number
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/timeline.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: Person
                        kind: NodeType
                        props:
                          name:
                            type: text
                            required: true
                          birthDate:
                            type: instant
                            timeline: CommonEra
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/person.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: friendOf
                        kind: RelType
                        from:
                          - Person
                        to:
                          - Person
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/friend.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: bob
                        kind: Node
                        type: Person
                        props:
                          name: Bob
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/bob.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: alice
                        kind: Node
                        type: Person
                        props:
                          name: Alice
                          birthDate:
                            timeline: CommonEra
                            value: "AD 2001-04-12"
                        ---
                        Alice knows @[Bob](bob "friendOf")
                    """.trimIndent(),
                    sourcePath = "/tmp/alice.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals(2, result.nodes.size)
        assertEquals(1, result.relations.size)
        assertEquals("bob", result.relations.single().to)
    }

    @Test
    fun `compile sources respects document set boundaries`() {
        val result = compiler.compileSources(
            listOf(
                SourceDocument(
                    text = """
                        ---
                        id: Person
                        kind: NodeType
                        props:
                          name:
                            type: text
                            required: true
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/person.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: mentions
                        kind: RelType
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/mentions.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: alice
                        kind: Node
                        type: Person
                        props:
                          name: Alice
                        ---
                        Missing @[Bob](bob mentions)
                    """.trimIndent(),
                    sourcePath = "/tmp/alice.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.ReferenceError && "Unknown Node target" in it.message })
    }

    @Test
    fun `parses valid front matter with deeper indentation`() {
        val parsed = compiler.parseDocument(
            """
                ---
                id: Person
                kind: NodeType
                props:
                    profile:
                        type: object
                        properties:
                            displayName:
                                type: string
                ---
            """.trimIndent(),
            "/tmp/person-deep-indent.md",
        )

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.joinToString("\n") { it.message })
        val document = parsed.document as? NodeTypeDocument
        assertNotNull(document)
        assertEquals(PropType.`object`, document.props.getValue("profile").type)
        assertEquals(PropType.string, document.props.getValue("profile").properties.getValue("displayName").type)
    }
}
