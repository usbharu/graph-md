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

    @Test
    fun `reports when front matter root is not a mapping`() {
        val parsed = compiler.parseDocument(
            """
                ---
                - not-a-map
                ---
            """.trimIndent(),
            "/tmp/list.md",
        )

        assertNull(parsed.document)
        assertTrue(parsed.diagnostics.any { "Front matter root MUST be a mapping" in it.message })
    }

    @Test
    fun `parses offset timeline mapping with unit`() {
        val timeline = compiler.parseDocument(
            """
                ---
                id: ThirdAge
                kind: Timeline
                mappings:
                  - kind: offset
                    to: CommonEra
                    unit: year
                    offset: 645
                ---
            """.trimIndent(),
            "/tmp/third-age.md",
        ).document as? TimelineDocument

        assertNotNull(timeline)
        val mapping = timeline.mappings.single() as? OffsetTimelineMapping
        assertNotNull(mapping)
        assertEquals("CommonEra", mapping.to)
        assertEquals("year", mapping.unit)
        assertEquals(645, mapping.offset)
    }

    @Test
    fun `parses single quoted strings and inline bracket lists`() {
        val node = compiler.parseDocument(
            """
                ---
                id: x
                kind: Node
                type: T
                props:
                  name: 'Alice'
                  aliases: [Al, "B\b"]
                ---
            """.trimIndent(),
            "/tmp/node.md",
        ).document as? NodeDocument

        assertNotNull(node)
        assertEquals("Alice", (node.props.getValue("name") as RawString).value)
        val aliases = (node.props.getValue("aliases") as RawArray).values
        assertEquals(2, aliases.size)
        assertEquals("Bb", (aliases[1] as RawString).value)
    }

    @Test
    fun `reports invalid yaml mapping entries and unexpected indentation`() {
        val parsed = compiler.parseDocument(
            """
                ---
                id: x
                kind: Node
                type: T
                garbageline
                props:
                    name: a
                     bad: indent
                ---
            """.trimIndent(),
            "/tmp/bad.md",
        )

        assertTrue(parsed.diagnostics.any { "Invalid YAML mapping entry: garbageline" in it.message })
        assertTrue(parsed.diagnostics.any { "Unexpected indentation" in it.message })
    }

    @Test
    fun `reports unknown prop type and index`() {
        val parsed = compiler.parseDocument(
            """
                ---
                id: P
                kind: NodeType
                props:
                  name:
                    type: unknownType
                    index: unknownIndex
                ---
            """.trimIndent(),
            "/tmp/p.md",
        )

        assertTrue(parsed.diagnostics.any { "Unknown prop type" in it.message && "PropType.unknownType" in it.message })
        assertTrue(parsed.diagnostics.any { "Unknown prop index" in it.message && "PropIndex.unknownIndex" in it.message })
        val document = parsed.document as? NodeTypeDocument
        assertNotNull(document)
        assertEquals(PropType.string, document.props.getValue("name").type)
    }

    @Test
    fun `reports invalid timecode schema shapes`() {
        val notMapping = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                timecode: foo
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(notMapping.diagnostics.any { "timecode MUST be a mapping" in it.message })

        val unknownType = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                timecode:
                  type: unknown
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(unknownType.diagnostics.any { "Unknown timecode type" in it.message && "TimecodeType.unknown" in it.message })

        val unknownDirection = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                timecode:
                  type: number
                  direction: sideways
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(unknownDirection.diagnostics.any { "Unknown timecode direction" in it.message && "TimecodeDirection.sideways" in it.message })
    }

    @Test
    fun `reports invalid timeline mapping shapes`() {
        val notList = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings: foo
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(notList.diagnostics.any { "mappings MUST be a list" in it.message })

        val unknownKind = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: weird
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(unknownKind.diagnostics.any { "Unknown mapping kind: weird" in it.message })

        val offsetMissingTo = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: offset
                    offset: 1
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(offsetMissingTo.diagnostics.any { "to is required" in it.message })

        val tableEntriesNotList = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: table
                    to: CommonEra
                    entries: foo
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(tableEntriesNotList.diagnostics.any { "mapping.entries MUST be a list" in it.message })

        val tableEntryFromScalar = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: table
                    to: CommonEra
                    entries:
                      - from: 5
                        to: 6
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(tableEntryFromScalar.diagnostics.any { "mapping.entries.from MUST be a string or mapping" in it.message })

        val entryNotMapping = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: table
                    to: CommonEra
                    entries:
                      - 5
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(entryNotMapping.diagnostics.any { "mapping.entries items MUST be mappings" in it.message })

        val mappingNotMap = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - 5
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(mappingNotMap.diagnostics.any { "mapping MUST be a mapping" in it.message })

        val entryBadTimecode = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: table
                    to: CommonEra
                    entries:
                      - from:
                          value: x
                          timecode: notnumeric
                        to:
                          value: y
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(entryBadTimecode.diagnostics.any { "mapping.entries.from MUST be a number or number tuple" in it.message })
        val entryTupleBadItem = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: table
                    to: CommonEra
                    entries:
                      - from:
                          value: x
                          timecode:
                            - 1
                            - bad
                        to:
                          value: y
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(entryTupleBadItem.diagnostics.any { "mapping.entries.from MUST contain only numbers" in it.message })
        val entryValueMissing = compiler.parseDocument(
            """
                ---
                id: T
                kind: Timeline
                mappings:
                  - kind: table
                    to: CommonEra
                    entries:
                      - from:
                          note: no-value-here
                        to:
                          value: y
                ---
            """.trimIndent(),
            "/tmp/t.md",
        )
        assertTrue(entryValueMissing.diagnostics.any { "value is required" in it.message })
    }

    @Test
    fun `reports invalid timeline selector shapes`() {
        val scalarSelector = compiler.parseDocument(
            """
                ---
                id: E
                kind: NodeType
                props:
                  x:
                    type: instant
                    timeline: 5
                ---
            """.trimIndent(),
            "/tmp/e.md",
        )
        assertTrue(scalarSelector.diagnostics.any { "MUST be a Timeline identifier" in it.message })

        val mappedMissingField = compiler.parseDocument(
            """
                ---
                id: E
                kind: NodeType
                props:
                  x:
                    type: instant
                    timeline:
                      mapped: 5
                ---
            """.trimIndent(),
            "/tmp/e.md",
        )
        assertTrue(mappedMissingField.diagnostics.any { "selector mapping MUST have a string 'mapped' field" in it.message })

        val singleTimelines = compiler.parseDocument(
            """
                ---
                id: E
                kind: NodeType
                props:
                  x:
                    type: instant
                    timelines: CommonEra
                ---
            """.trimIndent(),
            "/tmp/e.md",
        )
        val document = singleTimelines.document as? NodeTypeDocument
        assertNotNull(document)
        assertEquals(listOf(TimelineSelector.Id("CommonEra")), document.props.getValue("x").timelines)
    }

    @Test
    fun `reports invalid string list and props map shapes`() {
        val stringListScalar = compiler.parseDocument(
            """
                ---
                id: P
                kind: NodeType
                extends: 5
                ---
            """.trimIndent(),
            "/tmp/p.md",
        )
        assertTrue(stringListScalar.diagnostics.any { "extends MUST be a list of strings" in it.message })

        val stringListBadItem = compiler.parseDocument(
            """
                ---
                id: P
                kind: NodeType
                extends:
                  - 5
                ---
            """.trimIndent(),
            "/tmp/p.md",
        )
        assertTrue(stringListBadItem.diagnostics.any { "extends items MUST be strings" in it.message })

        val propsNotMapping = compiler.parseDocument(
            """
                ---
                id: P
                kind: NodeType
                props: foo
                ---
            """.trimIndent(),
            "/tmp/p.md",
        )
        assertTrue(propsNotMapping.diagnostics.any { "props MUST be a mapping" in it.message })
    }

    @Test
    fun `parses offset mapping and raw value variants`() {
        val node = compiler.parseDocument(
            """
                ---
                id: x
                kind: Node
                type: T
                props:
                  a: 1
                  b: 1.5
                  c: true
                  d: null
                  e:
                    - 1
                    - 2.5
                  f:
                    nested: value
                ---
            """.trimIndent(),
            "/tmp/node.md",
        ).document as? NodeDocument

        assertNotNull(node)
        assertTrue(node.props.getValue("a") is RawInteger)
        assertTrue(node.props.getValue("b") is RawNumber)
        assertTrue(node.props.getValue("c") is RawBoolean)
        assertEquals(RawNull, node.props.getValue("d"))
        assertTrue(node.props.getValue("e") is RawArray)
        assertTrue(node.props.getValue("f") is RawObject)
    }
}
