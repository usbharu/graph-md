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
    fun `yaml comments are stripped only outside quoted scalars`() {
        val bare = compiler.parseDocument(
            """
            ---
            id: bare
            kind: Node
            type: Person
            validTime:
              - timeline: T # era
            ---
            """.trimIndent(),
            "/tmp/bare.md",
        ).document as NodeDocument
        val quoted = compiler.parseDocument(
            """
            ---
            id: quoted
            kind: Node
            type: Person
            validTime:
              - timeline: "T # era" # outside
            ---
            """.trimIndent(),
            "/tmp/quoted.md",
        ).document as NodeDocument

        assertEquals("T", bare.validTime.single().timeline)
        assertEquals("T # era", quoted.validTime.single().timeline)
    }

    @Test
    fun `inline lists ignore empty comma-separated items`() {
        val parsed = compiler.parseDocument(
            """
            ---
            id: Child
            kind: NodeType
            extends: [, Base,, "Other",]
            ---
            """.trimIndent(),
            "/tmp/child.md",
        )

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.joinToString())
        assertEquals(listOf("Base", "Other"), (parsed.document as NodeTypeDocument).extends)
    }

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
                Alice is friends with @link{}[Bob](bob friendOf)
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
        assertTrue(document.body.contains("@link{}[Bob](bob friendOf)"))
        assertTrue("type" in document.topLevelFields)
    }

    @Test
    fun `comments and flow lists use quote aware shared lexing`() {
        val parsed = compiler.parseDocument(
            text = """
                ---
                "id": "child#type" # outside a quote is a comment
                'kind': NodeType # comment
                extends: ["A\",B", 'C'',D', "Hash # inside", "Colon: value", [Ignored, Nested], Plain] # comment
                # a whole line comment
                props:
                ---
            """.trimIndent(),
            sourcePath = "/tmp/child.md",
        )

        val document = parsed.document as? NodeTypeDocument
        assertNotNull(document)
        assertEquals("child#type", document.id)
        assertEquals(listOf("A\",B", "C',D", "Hash # inside", "Colon: value", "Plain"), document.extends)
        assertTrue(parsed.diagnostics.any { it.message == "extends items MUST be strings" })
        assertTrue(parsed.diagnostics.none { "Invalid YAML mapping entry: # a whole line comment" in it.message })
    }

    @Test
    fun `flow lists ignore blank items consistently with the previous parser`() {
        val variants = listOf(
            "[A,]",
            "[A, ]",
            "[, A]",
            "[A,, B]",
            """["A,B", ['Ignored', Nested], C,]""",
        )

        variants.forEachIndexed { index, flow ->
            val parsed = compiler.parseDocument(
                "---\nid: Type$index\nkind: NodeType\nextends: $flow\n---",
                "/tmp/type-$index.md",
            )
            val document = parsed.document as? NodeTypeDocument
            assertNotNull(document)
            val expected = when (index) {
                3 -> listOf("A", "B")
                4 -> listOf("A,B", "C")
                else -> listOf("A")
            }
            assertEquals(expected, document.extends, flow)
            if (index == 4) {
                assertTrue(parsed.diagnostics.any { it.message == "extends items MUST be strings" })
            } else {
                assertTrue(parsed.diagnostics.none { it.message == "extends items MUST be strings" }, flow)
            }
        }
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
                mappings:
                  - kind: offset
                    to: Other
                    offset: 1.5
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
        assertEquals(1, timeline.mappings.size)
        assertEquals(1.5, (timeline.mappings.single() as OffsetTimelineMapping).offset)
    }

    @Test
    fun `parses timeline selector string and legacy mapped forms`() {
        val nodeType = compiler.parseDocument(
            """
                ---
                id: Event
                kind: NodeType
                props:
                  byId:
                    type: instant
                    timeline: CommonEra
                  mapped:
                    type: instant
                    timeline:
                      - id: CommonEra
                        mapped: true
                  singularCanonical:
                    type: instant
                    timeline:
                      id: Singular
                      mapped: true
                  singularLegacy:
                    type: instant
                    timeline:
                      Legacy.Single:
                        mapped: false
                  overlappingLegacy:
                    type: instant
                    timeline:
                      Nested:
                        id: Spurious
                        mapped: true
                  overlappingLegacyList:
                    type: instant
                    timeline:
                      - Listed:
                          id: AlsoSpurious
                          mapped: false
                  canonicalExtra:
                    type: instant
                    timeline:
                      id: Canonical
                      mapped: true
                      extra: ignored
                  canonicalListExtra:
                    type: instant
                    timeline:
                      - id: CanonicalList
                        mapped: false
                        extra: ignored
                  mixed:
                    type: instant
                    timeline:
                      - id: ThirdAge
                        mapped: false
                      - CommonEra:
                          mapped: true
                      - Third.Age:
                          mapped: true
                      - _Leading:
                          mapped: false
                ---
            """.trimIndent(),
            "/tmp/event.md",
        ).document as? NodeTypeDocument

        assertNotNull(nodeType)
        assertEquals(TimelineSelector.Id("CommonEra"), nodeType.props.getValue("byId").timeline)
        assertEquals(listOf(TimelineSelector.Mapped("CommonEra")), nodeType.props.getValue("mapped").timelines)
        assertEquals(TimelineSelector.Mapped("Singular"), nodeType.props.getValue("singularCanonical").timeline)
        assertEquals(TimelineSelector.Id("Legacy.Single"), nodeType.props.getValue("singularLegacy").timeline)
        assertEquals(TimelineSelector.Mapped("Nested"), nodeType.props.getValue("overlappingLegacy").timeline)
        assertEquals(
            listOf(TimelineSelector.Id("Listed")),
            nodeType.props.getValue("overlappingLegacyList").timelines,
        )
        assertEquals(TimelineSelector.Mapped("Canonical"), nodeType.props.getValue("canonicalExtra").timeline)
        assertEquals(
            listOf(TimelineSelector.Id("CanonicalList")),
            nodeType.props.getValue("canonicalListExtra").timelines,
        )
        assertEquals(
            listOf(
                TimelineSelector.Id("ThirdAge"),
                TimelineSelector.Mapped("CommonEra"),
                TimelineSelector.Mapped("Third.Age"),
                TimelineSelector.Id("_Leading"),
            ),
            nodeType.props.getValue("mixed").timelines,
        )
    }

    @Test
    fun `rejects mapped selectors with missing or non-boolean mapped fields`() {
        val parsed = compiler.parseDocument(
            """
            ---
            id: Invalid
            kind: NodeType
            props:
              missing:
                type: instant
                timeline:
                  - id: Missing
              nonBoolean:
                type: instant
                timeline:
                  - Legacy:
                      mapped: nope
              deepCanonical:
                type: instant
                timeline:
                  - id: DeepCanonical
                    nested:
                      mapped: true
              deepLegacy:
                type: instant
                timeline:
                  - DeepLegacy:
                      nested:
                        mapped: false
              neighbor:
                type: instant
                timeline:
                  - id: Neighbor
                  - id: Other
                    mapped: true
            ---
            """.trimIndent(),
            "/tmp/invalid.md",
        )

        assertTrue(parsed.diagnostics.count { "selector MUST" in it.message } == 5, parsed.diagnostics.joinToString())
        val document = parsed.document as NodeTypeDocument
        assertEquals(emptyList(), document.props.getValue("missing").timelines)
        assertEquals(emptyList(), document.props.getValue("nonBoolean").timelines)
        assertEquals(emptyList(), document.props.getValue("deepCanonical").timelines)
        assertEquals(emptyList(), document.props.getValue("deepLegacy").timelines)
        assertEquals(listOf(TimelineSelector.Mapped("Other")), document.props.getValue("neighbor").timelines)
    }

    @Test
    fun `rejects non-number timecode and non-offset mapping`() {
        val result = compiler.parseDocument(
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
        )

        assertTrue(result.diagnostics.any { "Unknown timecode type: tuple" in it.message })
        assertTrue(result.diagnostics.any { "Unknown mapping kind: table" in it.message })
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
                            timecode: 978307200
                        ---
                        Alice knows @link{}[Bob](bob "friendOf")
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
                        Missing @link{}[Bob](bob mentions)
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
                        type: array
                        items: string
                ---
            """.trimIndent(),
            "/tmp/person-deep-indent.md",
        )

        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.joinToString("\n") { it.message })
        val document = parsed.document as? NodeTypeDocument
        assertNotNull(document)
        assertEquals(PropType.array, document.props.getValue("profile").type)
        assertEquals(PropType.string, document.props.getValue("profile").items?.type)
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
    fun `parses decimal offset timeline mapping`() {
        val timeline = compiler.parseDocument(
            """
                ---
                id: ThirdAge
                kind: Timeline
                mappings:
                  - kind: offset
                    to: CommonEra
                    offset: 645.5
                ---
            """.trimIndent(),
            "/tmp/third-age.md",
        ).document as? TimelineDocument

        assertNotNull(timeline)
        val mapping = timeline.mappings.single() as? OffsetTimelineMapping
        assertNotNull(mapping)
        assertEquals("CommonEra", mapping.to)
        assertEquals(645.5, mapping.offset)
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
    fun `reports unknown prop type and rejects removed index field`() {
        val parsed = compiler.parseDocument(
            """
                ---
                id: P
                kind: NodeType
                props:
                  name:
                    type: unknownType
                    index: fulltext
                ---
            """.trimIndent(),
            "/tmp/p.md",
        )

        assertTrue(parsed.diagnostics.any { "Unknown prop type: unknownType" in it.message })
        assertTrue(parsed.diagnostics.any { "Unknown property schema field: props.name.index" in it.message })
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
        assertTrue(unknownType.diagnostics.any { "Unknown timecode type: unknown" in it.message })

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
        assertTrue(unknownDirection.diagnostics.any { "Unknown timecode field: direction" in it.message })
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
        assertTrue(offsetMissingTo.diagnostics.any { "exactly one of from or to" in it.message })

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
        assertTrue(tableEntriesNotList.diagnostics.any { "Unknown mapping kind: table" in it.message })

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
        assertTrue(tableEntryFromScalar.diagnostics.any { "Unknown mapping kind: table" in it.message })

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
        assertTrue(entryNotMapping.diagnostics.any { "Unknown mapping kind: table" in it.message })

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
        assertTrue(entryBadTimecode.diagnostics.any { "Unknown mapping kind: table" in it.message })
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
        assertTrue(entryTupleBadItem.diagnostics.any { "Unknown mapping kind: table" in it.message })
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
        assertTrue(entryValueMissing.diagnostics.any { "Unknown mapping kind: table" in it.message })
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
        assertTrue(mappedMissingField.diagnostics.any { "selector MUST be" in it.message })

        val singleTimelines = compiler.parseDocument(
            """
                ---
                id: E
                kind: NodeType
                props:
                  x:
                    type: instant
                    timeline: CommonEra
                ---
            """.trimIndent(),
            "/tmp/e.md",
        )
        val document = singleTimelines.document as? NodeTypeDocument
        assertNotNull(document)
        assertEquals(TimelineSelector.Id("CommonEra"), document.props.getValue("x").timeline)
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

    @Test
    fun `parses Media and node validTime from the canonical schema`() {
        val result = compiler.parseDocument(
            """
                ---
                id: portrait
                kind: Media
                type: Image
                url: https://example.com/alice.png
                validTime:
                  - timeline: CommonEra
                    from:
                      value: start
                      timecode: 1.5
                    to:
                      timecode: 2
                ---
            """.trimIndent(),
            "/tmp/portrait.md",
        )

        val media = result.document as? NodeDocument
        assertNotNull(media)
        assertEquals(DocumentKind.Media, media.kind)
        assertEquals("https://example.com/alice.png", media.url)
        assertEquals(1.5, media.validTime.single().from?.timecode)
        assertEquals("start", media.validTime.single().from?.value)
        assertEquals(2.0, media.validTime.single().to?.timecode)
    }

    @Test
    fun `requires Media url and rejects validTime on type definitions`() {
        val media = compiler.parseDocument(
            """
                ---
                id: portrait
                kind: Media
                type: Image
                ---
            """.trimIndent(),
            "/tmp/portrait.md",
        )
        assertTrue(media.diagnostics.any { "requires url" in it.message })

        val nodeType = compiler.parseDocument(
            """
                ---
                id: Person
                kind: NodeType
                validTime:
                  - timeline: CommonEra
                ---
            """.trimIndent(),
            "/tmp/person.md",
        )
        assertTrue(nodeType.diagnostics.any { "Unknown top-level field: validTime" in it.message })
    }

    @Test
    fun `validates Timeline mapping and localized props constraints`() {
        val result = compiler.parseDocument(
            """
                ---
                id: ProjectEra
                kind: Timeline
                mappings:
                  - from: CommonEra
                    kind: offset
                    offset: 1
                props:
                  label:
                    ja: プロジェクト紀元
                  note: 1
                ---
            """.trimIndent(),
            "/tmp/project-era.md",
        )

        assertTrue(result.diagnostics.any { "requires timecode" in it.message })
        assertTrue(result.diagnostics.any { "label.default" in it.message })
        assertTrue(result.diagnostics.any { "props.note MUST be a string" in it.message })
    }

    @Test
    fun `rejects empty ids and invalid identifier lists`() {
        val emptyId = compiler.parseDocument(
            """
                ---
                id: ""
                kind: NodeType
                ---
            """.trimIndent(),
            "/tmp/empty.md",
        )
        assertTrue(emptyId.diagnostics.any { "id MUST be non-empty" in it.message })

        val invalidExtends = compiler.parseDocument(
            """
                ---
                id: Child
                kind: NodeType
                extends:
                  - Parent
                  - Parent
                ---
            """.trimIndent(),
            "/tmp/child.md",
        )
        assertTrue(invalidExtends.diagnostics.any { "extends items MUST be unique" in it.message })
    }

    @Test
    fun `accepts ids matching the identifier grammar`() {
        listOf("alice", "_private", "org:example.test-1", "A0").forEach { id ->
            val result = compiler.parseDocument(
                """
                    ---
                    id: $id
                    kind: NodeType
                    ---
                """.trimIndent(),
                "/tmp/$id.md",
            )

            assertNotNull(result.document)
            assertTrue(
                result.diagnostics.none { it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*" },
                "Expected '$id' to be a valid id",
            )
        }
    }

    @Test
    fun `warns for ids outside the identifier grammar without rejecting the document`() {
        listOf("1alice", "alice smith", "alice/bob", "アリス").forEachIndexed { index, id ->
            val result = compiler.parseDocument(
                """
                    ---
                    id: "$id"
                    kind: NodeType
                    ---
                """.trimIndent(),
                "/tmp/invalid-$index.md",
            )

            assertNotNull(result.document)
            val diagnostic = result.diagnostics.single {
                it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*"
            }
            assertEquals(DiagnosticCategory.SchemaError, diagnostic.category)
            assertEquals(Severity.Warning, diagnostic.severity)
            assertEquals(id, diagnostic.source?.documentId)
        }
    }

    @Test
    fun `rejects rel type ids containing decoded Unicode whitespace`() {
        val cases = listOf(
            "friend Of" to "friend Of",
            "'friend Of'" to "friend Of",
            "\"friend Of\"" to "friend Of",
            "\"friend\\tOf\"" to "friend\tOf",
            "\"friend\u00a0Of\"" to "friend\u00a0Of",
            "\"friend\\nOf\"" to "friend\nOf",
        )

        cases.forEachIndexed { index, (encoded, id) ->
            val result = compiler.parseDocument(
                "---\nid: $encoded\nkind: RelType\n---",
                "/tmp/invalid-rel-type-$index.md",
            )

            assertNull(result.document, "RelType '$id' must not be retained")
            val diagnostic = result.diagnostics.single {
                it.message == "RelType id MUST NOT contain whitespace"
            }
            assertEquals(DiagnosticCategory.SchemaError, diagnostic.category)
            assertEquals(Severity.Error, diagnostic.severity)
            assertEquals(id, diagnostic.source?.documentId)
            assertTrue(result.diagnostics.none { it.severity == Severity.Warning })
        }
    }

    @Test
    fun `warns but retains noncanonical rel type ids without whitespace`() {
        val result = compiler.parseDocument(
            """
                ---
                id: "friend/of"
                kind: RelType
                ---
            """.trimIndent(),
            "/tmp/noncanonical-rel-type.md",
        )

        assertEquals("friend/of", (result.document as? RelTypeDocument)?.id)
        assertTrue(result.diagnostics.any {
            it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*" &&
                it.severity == Severity.Warning
        })
    }

    @Test
    fun `compiler resolves retained noncanonical rel type and excludes whitespace definition`() {
        val result = compiler.compileSources(
            listOf(
                SourceDocument(
                    "---\nid: \"friend/of\"\nkind: RelType\n---",
                    "/tmp/friend-slash.md",
                ),
                SourceDocument(
                    "---\nid: \"friend Of\"\nkind: RelType\n---",
                    "/tmp/friend-space.md",
                ),
                SourceDocument(
                    "---\nid: child\nkind: RelType\nextends: [\"friend Of\"]\n---",
                    "/tmp/child.md",
                ),
                SourceDocument(
                    "---\nid: alice\nkind: Node\ntype: Person\n---\n@link[Bob](bob friend/of)",
                    "/tmp/alice.md",
                ),
                SourceDocument(
                    "---\nid: bob\nkind: Node\ntype: Person\n---",
                    "/tmp/bob.md",
                ),
            ),
        )

        assertTrue(result.relTypes.any { it.id == "friend/of" })
        assertTrue(result.relTypes.none { it.id == "friend Of" })
        assertEquals("friend/of", result.relations.single().type)
        assertTrue(result.diagnostics.any { it.message == "Unknown parent RelType: friend Of" })
        assertTrue(result.diagnostics.none { it.message == "Unknown RelType: friend/of" })
    }
}
