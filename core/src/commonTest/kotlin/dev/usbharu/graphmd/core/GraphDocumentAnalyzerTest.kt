package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphDocumentAnalyzerTest {
    private val analyzer = GraphDocumentAnalyzer()

    @Test
    fun `indexes complete noncanonical yaml ids with decoded values and editable ranges`() {
        val text = """
            ---
            id: "HOGE\@FUGA" # definition comment
            kind: Node
            type: 'TYPE@ONE' # reference comment
            props:
              nested:
                id: phantom@id
                timeline: "TIME\/LINE"
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/punctuation.md")
        val definition = analysis.definitions.single()
        assertEquals("HOGE@FUGA", definition.id)
        assertEquals("HOGE\\@FUGA", text.substring(definition.range.start, definition.range.end))
        assertEquals("HOGE@FUGA", analysis.parsed.document?.id)
        assertEquals(listOf("TYPE@ONE", "TIME/LINE"), analysis.references.map { it.targetId })
        assertTrue(analysis.definitions.none { it.id == "phantom@id" })
        assertTrue(analysis.parsed.diagnostics.any { it.message.startsWith("id MUST match ") })
    }

    @Test
    fun `preserves noncanonical list and body reference tokens without fragments`() {
        val text = """
            ---
            id: Child@Type
            kind: NodeType
            extends: ["Base@Type", 'Other/Type', Third+Type] # list comment
            props:
              active:
                timeline: Era@Branch
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/child.md")
        assertEquals(
            listOf("Base@Type", "Other/Type", "Third+Type", "Era@Branch"),
            analysis.references.map { it.targetId },
        )
        assertTrue(analysis.references.none { it.targetId in setOf("Base", "Type", "Era", "Branch") })
        assertEquals(
            listOf("Base@Type", "Other/Type", "Third+Type", "Era@Branch"),
            analysis.references.map { text.substring(it.range.start, it.range.end) },
        )
    }

    @Test
    fun `indexes complete GraphMD target and quoted relation type tokens`() {
        val text = """
            ---
            id: source
            kind: Node
            type: Type
            ---
            @link{}[Target](node@remote "rel\/type")
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/body.md")
        val target = analysis.references.single { it.field == "relation.target" }
        val relType = analysis.references.single { it.field == "relation.type" }
        assertEquals("node@remote", target.targetId)
        assertEquals("rel/type", relType.targetId)
        assertEquals("node@remote", text.substring(target.range.start, target.range.end))
        assertEquals("rel\\/type", text.substring(relType.range.start, relType.range.end))
    }

    @Test
    fun `keeps raw offsets for CRLF and UTF-16 text before noncanonical symbols`() {
        val text = "---\r\n# 😀\r\nid: HOGE@FUGA\r\nkind: NodeType\r\n---\r\n"
        val analysis = analyzer.analyze(text, "/tmp/crlf.md")
        val definition = analysis.definitions.single()

        assertEquals(text.indexOf("HOGE@FUGA"), definition.range.start)
        assertEquals("HOGE@FUGA", text.substring(definition.range.start, definition.range.end))
        assertEquals("HOGE@FUGA", analyzer.findDefinitionAt(analysis, definition.range.start + 5)?.id)
    }

    @Test
    fun `keeps parser and analyzer identities aligned around yaml comments and flow lists`() {
        val blockList = """
            ---
            id: Child
            kind: NodeType
            extends:
              - Parent@id # keep
            ---
        """.trimIndent()
        val blockAnalysis = analyzer.analyze(blockList, "/tmp/block.md")
        assertEquals(listOf("Parent@id"), (blockAnalysis.parsed.document as NodeTypeDocument).extends)
        assertEquals(listOf("Parent@id"), blockAnalysis.references.map { it.targetId })

        val noSeparation = """
            ---
            id: Child
            kind: NodeType
            extends: [Parent@id]#suffix
            ---
        """.trimIndent()
        val noSeparationAnalysis = analyzer.analyze(noSeparation, "/tmp/no-separation.md")
        assertTrue(noSeparationAnalysis.references.none { it.targetId == "Parent@id" })
        assertTrue((noSeparationAnalysis.parsed.document as NodeTypeDocument).extends.isEmpty())

        val quotedSuffix = """
            ---
            id: "A@id"#suffix
            kind: NodeType
            ---
        """.trimIndent()
        val suffixAnalysis = analyzer.analyze(quotedSuffix, "/tmp/suffix.md")
        val suffixDefinition = suffixAnalysis.definitions.single()
        assertEquals("\"A@id\"#suffix", suffixDefinition.id)
        assertEquals("\"A@id\"#suffix", quotedSuffix.substring(suffixDefinition.range.start, suffixDefinition.range.end))
        assertEquals(suffixAnalysis.parsed.document?.id, suffixDefinition.id)
    }

    @Test
    fun `indexes only the parser-selected duplicate root id`() {
        val text = """
            ---
            id: A@one
            id: B@two
            kind: NodeType
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/duplicate-id.md")
        val definition = analysis.definitions.single()
        assertEquals("B@two", definition.id)
        assertEquals(text.lastIndexOf("B@two"), definition.range.start)
        assertEquals("B@two", analysis.parsed.document?.id)
    }

    @Test
    fun `does not index timeline text inside inline quoted property values`() {
        val text = """
            ---
            id: node
            kind: Node
            type: Type
            ---
            @props{note="timeline=Ghost@id", x=1}
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/string.md")
        assertTrue(analysis.references.none { it.targetId == "Ghost@id" })
    }

    @Test
    fun `flow lists track escaped double quotes while retaining punctuation`() {
        val text = """
            ---
            id: Child
            kind: NodeType
            extends: ["A\"B,C", D@two]
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/flow.md")
        assertEquals(listOf("A\"B,C", "D@two"), (analysis.parsed.document as NodeTypeDocument).extends)
        assertEquals(listOf("A\"B,C", "D@two"), analysis.references.map { it.targetId })
        assertEquals(listOf("A\\\"B,C", "D@two"), analysis.references.map { text.substring(it.range.start, it.range.end) })
    }

    @Test
    fun `does not partially index nested or comment-truncated flow lists`() {
        val nested = """
            ---
            id: Child
            kind: NodeType
            extends:
              - [Parent@id]
            ---
        """.trimIndent()
        val nestedAnalysis = analyzer.analyze(nested, "/tmp/nested-flow.md")
        assertTrue((nestedAnalysis.parsed.document as NodeTypeDocument).extends.isEmpty())
        assertTrue(nestedAnalysis.references.none { "Parent@id" in it.targetId })

        val truncated = """
            ---
            id: Child
            kind: NodeType
            extends: [Parent@id, # closing bracket is commented]
            ---
        """.trimIndent()
        val truncatedAnalysis = analyzer.analyze(truncated, "/tmp/truncated-flow.md")
        assertTrue((truncatedAnalysis.parsed.document as NodeTypeDocument).extends.isEmpty())
        assertTrue(truncatedAnalysis.references.none { it.targetId == "Parent@id" })

        val quoted = """
            ---
            id: Child
            kind: NodeType
            extends:
              - "[Parent@id]"
            ---
        """.trimIndent()
        val quotedAnalysis = analyzer.analyze(quoted, "/tmp/quoted-bracket.md")
        assertEquals(listOf("[Parent@id]"), (quotedAnalysis.parsed.document as NodeTypeDocument).extends)
        val quotedReference = quotedAnalysis.references.single()
        assertEquals("[Parent@id]", quotedReference.targetId)
        assertEquals("[Parent@id]", quoted.substring(quotedReference.range.start, quotedReference.range.end))

        val quotedMapping = """
            ---
            id: node
            kind: Node
            type: "[Type@id]"
            ---
        """.trimIndent()
        val mappingAnalysis = analyzer.analyze(quotedMapping, "/tmp/quoted-mapping.md")
        assertEquals("[Type@id]", (mappingAnalysis.parsed.document as NodeDocument).type)
        assertEquals("[Type@id]", mappingAnalysis.references.single { it.field == "type" }.targetId)
    }

    @Test
    fun `unmatched prose quote does not hide timeline references on later lines`() {
        val text = """
            ---
            id: node
            kind: Node
            type: Type
            ---
            Unmatched " prose
            @props(validTime=Era@Branch){age=1}
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/unmatched-quote.md")
        assertEquals(
            listOf("Era@Branch"),
            analysis.references.filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
    }

    @Test
    fun `returns empty analysis when front matter marker is absent`() {
        val text = "no front matter here"
        val analysis = analyzer.analyze(text, "/tmp/raw.md")

        assertEquals(0, analysis.frontMatterEndOffset)
        assertTrue(analysis.definitions.isEmpty())
        assertTrue(analysis.references.isEmpty())
        assertNull(analyzer.findReferenceAt(analysis, 0))
        assertNull(analyzer.findDefinitionAt(analysis, 0))
    }

    @Test
    fun `indexes only the winning validTime from complete body block headers`() {
        val text = """
            ---
            id: node
            kind: Node
            type: Type
            ---
            ::: history validTime=Discarded validTime = Active(from=0 ,to=1)
            prose
            :::
            ```
            ::: code validTime=Hidden
            :::
            ```
            ::::: incomplete validTime=Incomplete
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/block.md")
        val timelines = analysis.references.filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(listOf("Active"), timelines.map { it.targetId })
        assertEquals("Active", text.substring(timelines.single().range.start, timelines.single().range.end))
    }

    @Test
    fun `returns empty analysis when front matter is never closed`() {
        val text = "---\nid: alice\nkind: Node"
        val analysis = analyzer.analyze(text, "/tmp/open.md")

        assertEquals(0, analysis.frontMatterEndOffset)
        assertTrue(analysis.definitions.isEmpty())
        assertTrue(analysis.references.isEmpty())
    }

    @Test
    fun `normalizes CRLF and lone CR without adding logical lines`() {
        val text = "---\r\nkind: NodeType\r\rid: Person\n---"
        val analysis = analyzer.analyze(text, "/tmp/Person.md")

        assertEquals("---\nkind: NodeType\n\nid: Person\n---", analysis.text)
        assertEquals("Person", analysis.definitions.single().id)
        assertEquals(
            "Person",
            analysis.text.substring(
                analysis.definitions.single().range.start,
                analysis.definitions.single().range.end,
            ),
        )
    }

    @Test
    fun `extracts node definitions and type references and body relation references`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link{}[Bob](bob friendOf)
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")
        val document = analysis.parsed.document as NodeDocument

        assertEquals("alice", document.id)
        assertEquals("alice", analysis.definitions.single().id)
        assertEquals(ReferenceTargetKind.Node, analysis.definitions.single().kind)

        val typeRef = analysis.references.single { it.field == "type" }
        assertEquals("Person", typeRef.targetId)
        assertEquals(ReferenceTargetKind.NodeType, typeRef.kind)

        val targetRef = analysis.references.single { it.field == "relation.target" }
        assertEquals("bob", targetRef.targetId)
        assertEquals(ReferenceTargetKind.Node, targetRef.kind)

        val relTypeRef = analysis.references.single { it.field == "relation.type" }
        assertEquals("friendOf", relTypeRef.targetId)
        assertEquals(ReferenceTargetKind.RelType, relTypeRef.kind)

        val defOffset = text.indexOf("alice")
        assertEquals("alice", analyzer.findDefinitionAt(analysis, defOffset)?.id)
        assertNull(analyzer.findDefinitionAt(analysis, 0))

        val refOffset = text.indexOf("Person") + 1
        assertEquals("Person", analyzer.findReferenceAt(analysis, refOffset)?.targetId)
        assertNull(analyzer.findReferenceAt(analysis, 0))

        assertEquals(text, analysis.text)
        assertTrue(analysis.frontMatterEndOffset > text.indexOf("---", 3))
    }

    @Test
    fun `preserves Media as a distinct definition kind and id range`() {
        val text = """
            ---
            id: portrait
            kind: Media
            type: Image
            url: https://example.com/portrait.png
            ---
        """.trimIndent()

        val definition = analyzer.analyze(text, "/tmp/portrait.md").definitions.single()

        assertEquals(ReferenceTargetKind.Media, definition.kind)
        assertEquals("portrait", definition.id)
        assertEquals(text.indexOf("portrait"), definition.range.start)
        assertEquals(text.indexOf("portrait") + "portrait".length, definition.range.end)
        assertTrue(ReferenceTargetKind.Node.acceptsDefinition(definition.kind))
        assertTrue(ReferenceTargetKind.Node.sharesSymbolNamespaceWith(definition.kind))
        assertTrue(!ReferenceTargetKind.Media.acceptsDefinition(ReferenceTargetKind.Node))
    }

    @Test
    fun `extracts quoted and stripped yaml scalars in body and front matter`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: "Person"
            ---
            @link{}[Bob](bob "friendOf")
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        assertEquals("Person", analysis.references.single { it.field == "type" }.targetId)
        assertEquals("friendOf", analysis.references.single { it.field == "relation.type" }.targetId)
        assertEquals("bob", analysis.references.single { it.field == "relation.target" }.targetId)
    }

    @Test
    fun `extracts block list references for nodetype extends`() {
        val text = """
            ---
            id: Person
            kind: NodeType
            extends:
              - Entity
              - 'Actor'
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/person.md")
        val extendsRefs = analysis.references.filter { it.field == "extends" }
        assertEquals(listOf("Entity", "Actor"), extendsRefs.map { it.targetId })
        extendsRefs.forEach { assertEquals(ReferenceTargetKind.NodeType, it.kind) }
    }

    @Test
    fun `extracts block list references for reltype from to and extends`() {
        val text = """
            ---
            id: worksAt
            kind: RelType
            from:
              - Person
            to:
              - Organization
            extends:
              - relatedTo
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/works.md")
        assertEquals(ReferenceTargetKind.NodeType, analysis.references.first { it.field == "from" }.kind)
        assertEquals(ReferenceTargetKind.NodeType, analysis.references.first { it.field == "to" }.kind)
        assertEquals(ReferenceTargetKind.RelType, analysis.references.first { it.field == "extends" }.kind)
    }

    @Test
    fun `extracts inline list references`() {
        val text = """
            ---
            id: worksAt
            kind: RelType
            from: [Person, Organization]
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/works.md")
        val fromRefs = analysis.references.filter { it.field == "from" }
        assertEquals(listOf("Person", "Organization"), fromRefs.map { it.targetId })
    }

    @Test
    fun `indexes only root id and node type across nested maps and sequences`() {
        val text = """
            ---
            props:
              object:
                "id": nested-object
                type: MissingObjectType
              values:
                - id: nested-list
                  type: MissingListType
            type: Person
            kind: Node
            id: alice
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        assertEquals(listOf("alice"), analysis.definitions.map { it.id })
        assertEquals(listOf("Person"), analysis.references.filter { it.kind == ReferenceTargetKind.NodeType }.map { it.targetId })
        assertFalse(analysis.references.any { it.targetId.startsWith("Missing") })
        val definition = analysis.definitions.single()
        assertEquals("alice", text.substring(definition.range.start, definition.range.end))
        val type = analysis.references.single { it.kind == ReferenceTargetKind.NodeType }
        assertEquals("Person", text.substring(type.range.start, type.range.end))
    }

    @Test
    fun `nested schema type is not a nodetype reference for type documents`() {
        val nodeType = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
              metadata:
                type: object-looking-value
                id: nested
            ---
        """.trimIndent()
        val relType = """
            ---
            id: knows
            kind: RelType
            from: [Person]
            to:
              - Person
            props:
              since:
                type: number
                id: nested
            ---
        """.trimIndent()

        val nodeTypeAnalysis = analyzer.analyze(nodeType, "/tmp/Person.md")
        val relTypeAnalysis = analyzer.analyze(relType, "/tmp/knows.md")

        assertEquals(listOf("Person"), nodeTypeAnalysis.definitions.map { it.id })
        assertTrue(nodeTypeAnalysis.references.none { it.kind == ReferenceTargetKind.NodeType })
        assertEquals(listOf("knows"), relTypeAnalysis.definitions.map { it.id })
        assertEquals(listOf("Person", "Person"), relTypeAnalysis.references.map { it.targetId })
    }

    @Test
    fun `preserves nested timeline references without treating selector id as a definition`() {
        val typeText = """
            ---
            id: Event
            kind: NodeType
            props:
              happenedAt:
                type: instant
                timeline: CommonEra
            ---
        """.trimIndent()
        val timelineText = """
            ---
            id: ProjectEra
            kind: Timeline
            mapsTo:
              - timeline: CommonEra
                kind: alignment
                offset: 1000
            props:
              label:
                default: Project
                id: display-only
                type: display-only
            ---
        """.trimIndent()

        val typeAnalysis = analyzer.analyze(typeText, "/tmp/Event.md")
        val timelineAnalysis = analyzer.analyze(timelineText, "/tmp/ProjectEra.md")

        assertEquals(listOf("Event"), typeAnalysis.definitions.map { it.id })
        assertEquals(listOf("CommonEra"), typeAnalysis.references.map { it.targetId })
        assertEquals(listOf("ProjectEra"), timelineAnalysis.definitions.map { it.id })
        assertEquals(listOf("CommonEra"), timelineAnalysis.references.map { it.targetId })
    }

    @Test
    fun `indexes only direct target of each temporal mapping entry`() {
        val text = """
            ---
            id: ProjectEra
            kind: Timeline
            mapsTo:
              - kind: alignment
                timeline: CommonEra
                offset: 1
              - kind: alignment
                timeline: Branch
                offset: 2
              - kind: alignment
                timeline: Other
                nested:
                  timeline: display-only
                offset: 3
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/ProjectEra.md")

        assertEquals(listOf("CommonEra", "Branch", "Other"), analysis.references.map { it.targetId })
        assertTrue(analysis.references.none { "display-only" in it.targetId })
    }

    @Test
    fun `sequence item nested boundary follows its first child indentation`() {
        val text = """
            ---
            id: ProjectEra
            kind: Timeline
            mapsTo:
              - nested:
                timeline: display-indent-2
              - nested:
                  timeline: display-indent-4
                kind: alignment
                timeline: DirectAfter4
                offset: 1
              - nested:
                    timeline: display-indent-6
                kind: alignment
                timeline: DirectAfter6
                offset: 2
              - timeline: DirectBefore
                nested:
                  timeline: display-after-direct
                kind: alignment
                offset: 3
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/ProjectEra.md")

        assertEquals(listOf("DirectAfter4", "DirectAfter6", "DirectBefore"), analysis.references.map { it.targetId })
        assertTrue(analysis.references.none { it.targetId.startsWith("display-") })
    }

    @Test
    fun `same-indent nested list ends before direct sequence item siblings`() {
        val text = """
            ---
            id: ProjectEra
            kind: Timeline
            mapsTo:
              - nested:
                - display-only
                - inner:
                    timeline: display-deep

                # comment before returning to direct mapping fields
                kind: alignment
                timeline: CommonEra
                offset: 1
              - timeline: DirectBefore
                nested:
                  - inner:
                      timeline: display-after-direct
                kind: alignment
                offset: 2
              - nested:
                timeline: same-indent-map
              - kind: alignment
                timeline: Branch
                offset: 3
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/ProjectEra.md")

        assertEquals(listOf("CommonEra", "DirectBefore", "Branch"), analysis.references.map { it.targetId })
        assertTrue(
            analysis.references.none {
                it.targetId in setOf("display-deep", "display-after-direct", "same-indent-map", "same-indent-map-too")
            },
        )
    }

    @Test
    fun `scanner restores direct sequence item paths after deep node props`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              values:
                - nested:
                    id: nested-id
                    deeper:
                      type: nested-type
                  id: direct-id
                  type: direct-type
                - id: second-id
                  type: second-type
            ---
        """.trimIndent()
        val lines = text.split('\n')
        val starts = buildList {
            add(0)
            text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
        }
        val structure = FrontMatterStructureScanner().scan(lines, starts, 1, lines.lastIndex)
        val pathsByValue = structure.scalars.associate { it.value to it.path }

        assertEquals(listOf("props", "values", "nested", "id"), pathsByValue.getValue("nested-id"))
        assertEquals(listOf("props", "values", "nested", "deeper", "type"), pathsByValue.getValue("nested-type"))
        assertEquals(listOf("props", "values", "id"), pathsByValue.getValue("direct-id"))
        assertEquals(listOf("props", "values", "type"), pathsByValue.getValue("direct-type"))
        assertEquals(listOf("props", "values", "id"), pathsByValue.getValue("second-id"))
        assertEquals(listOf("props", "values", "type"), pathsByValue.getValue("second-type"))
    }

    @Test
    fun `indexes timeline only in document schema positions`() {
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              timeline: display-only
              event:
                timeline: CommonEra
                timecode: 1
              nested:
                validTime:
                  - timeline: Branch
            validTime:
              - timeline: RootEra
            ---
        """.trimIndent()
        val typeText = """
            ---
            id: Event
            kind: NodeType
            props:
              timeline:
                type: string
              happenedAt:
                type: array
                items:
                  type: instant
                  timeline: CommonEra
                custom:
                  timeline: display-only
            ---
        """.trimIndent()

        val nodeAnalysis = analyzer.analyze(nodeText, "/tmp/alice.md")
        val typeAnalysis = analyzer.analyze(typeText, "/tmp/Event.md")

        assertEquals(
            listOf("CommonEra", "Branch", "RootEra"),
            nodeAnalysis.references.filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
        assertEquals(
            listOf("CommonEra"),
            typeAnalysis.references.filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
        assertTrue((nodeAnalysis.references + typeAnalysis.references).none { it.targetId == "display-only" })
    }

    @Test
    fun `indexes root from and to only for reltype`() {
        val documents = listOf(
            "Node" to "---\nid: n\nkind: Node\ntype: Person\nfrom: Wrong\n---",
            "Media" to "---\nid: m\nkind: Media\ntype: Person\nurl: media.png\nto: Wrong\n---",
            "NodeType" to "---\nid: N\nkind: NodeType\nfrom: Wrong\n---",
            "Timeline" to "---\nid: T\nkind: Timeline\nto: Wrong\n---",
        )

        documents.forEach { (kind, text) ->
            val analysis = analyzer.analyze(text, "/tmp/$kind.md")
            assertTrue(analysis.references.none { it.field in setOf("from", "to") }, kind)
        }

        val relType = analyzer.analyze(
            "---\nid: r\nkind: RelType\nfrom: [Person]\nto: Organization\n---",
            "/tmp/r.md",
        )
        assertEquals(listOf("Person", "Organization"), relType.references.map { it.targetId })
        assertTrue(relType.references.all { it.kind == ReferenceTargetKind.NodeType })
    }

    @Test
    fun `parser and analyzer share comment and escaped flow scalar semantics`() {
        val text = """
            ---
            id: "works#At" # trailing id comment
            kind: RelType # trailing kind comment
            extends: ["A\",B", 'C'',D', "E#F", [Ignored, Nested], G] # trailing list comment
            from: [Person] # trailing endpoint comment
            to:
              - "Organization: East" # colon inside the quoted scalar
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/works.md")
        val document = analysis.parsed.document as RelTypeDocument

        assertEquals("works#At", document.id)
        assertEquals(listOf("A\",B", "C',D", "E#F", "G"), document.extends)
        assertEquals(listOf("A\",B", "C',D", "E#F", "G", "Person", "Organization: East"), analysis.references.map { it.targetId })
        assertEquals(document.id, analysis.definitions.single().id)
        analysis.references.forEach { reference ->
            assertEquals(
                reference.targetId,
                decodeYamlScalar(analysis.text.substring(reference.range.start, reference.range.end)),
            )
        }
    }

    @Test
    fun `matches parser root indentation and duplicate field semantics`() {
        val text = """
            ---
              id: stale
              type: Missing
              id: alice
              kind: Media
              url: https://example.com/alice.png
              type: "Person"
              props:
                  nested:
                      id: ignored
                      type: AlsoIgnored
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        assertEquals("alice", analysis.definitions.single().id)
        assertEquals("Person", analysis.references.single { it.kind == ReferenceTargetKind.NodeType }.targetId)
        assertEquals("\"Person\"", text.substring(
            analysis.references.single().range.start,
            analysis.references.single().range.end,
        ))
    }

    @Test
    fun `tracks quoted flow values comments tabs and crlf without flattening paths`() {
        val text = (
            "---\r\n" +
                "\t# root fields use the parser's first-entry indentation\r\n" +
                "\tid: worksAt\r\n" +
                "\tkind: RelType\r\n" +
                "\tfrom: [\"Person\", 'Organization']\r\n" +
                "\tto:\r\n" +
                "\t\t- Person\r\n" +
                "\tprops:\r\n" +
                "\t\tmetadata:\r\n" +
                "\t\t\t\"id\": nested\r\n" +
                "\t\t\ttype: string\r\n" +
                "---"
            )

        val analysis = analyzer.analyze(text, "/tmp/worksAt.md")

        assertEquals("worksAt", analysis.definitions.single().id)
        assertEquals(listOf("Person", "Organization", "Person"), analysis.references.map { it.targetId })
        assertTrue(analysis.references.none { it.targetId in setOf("nested", "string") })
        assertEquals(
            listOf("\"Person\"", "'Organization'", "Person"),
            analysis.references.map { analysis.text.substring(it.range.start, it.range.end) },
        )
    }

    @Test
    fun `ignores comments and blank lines and code regions in body`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            # a comment
            ---
            ```lang
            @link{}[Ignored](ignored friendOf)
            ```
            `[Also](ignored friendOf)`
            @link{}[Bob](bob friendOf)
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        val targets = analysis.references.filter { it.field == "relation.target" }.map { it.targetId }
        assertEquals(listOf("bob"), targets)
    }

    @Test
    fun `does not index GraphMD syntax in CommonMark code regions`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
              ~~~
            @link[Tilde](tilde friendOf)
            @props{codedProperty = "ignored"}
              ~~~~
            > ````
            > @link[Quote](quote friendOf)
            > ```
            > @link[StillQuote](still-quote friendOf)
            > `````
            - ```
              @link[List](list friendOf)
              ```
            # top-level code boundaries
                @link[Spaces](spaces friendOf)
            ${'\t'}@link[Tab](tab friendOf)
            ``@link[Span](span friendOf) ` inner``` ``
            @link(validTime=VisibleTimeline){visibleProperty=1}[Visible](visible friendOf)
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        assertEquals(
            listOf("visible"),
            analysis.references.filter { it.field == "relation.target" }.map { it.targetId },
        )
        assertEquals(
            listOf("friendOf"),
            analysis.references.filter { it.field == "relation.type" }.map { it.targetId },
        )
        assertTrue(analysis.references.none { it.targetId in setOf("tilde", "quote", "still-quote", "list", "spaces", "tab", "span") })
        assertEquals(
            text.lastIndexOf("visible"),
            analysis.references.single { it.field == "relation.target" }.range.start,
        )
        assertEquals(
            text.indexOf("visibleProperty"),
            analysis.propertyReferences.single { it.name == "visibleProperty" }.range.start,
        )
    }

    @Test
    fun `indexes syntax around unmatched and escaped backticks but not unclosed fences`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            \` @link[Escaped](escaped friendOf)
            ` unmatched @link[Unmatched](unmatched friendOf)
            > ```
            > @link[QuoteCode](quote-code friendOf)
            @link[AfterQuote](after-quote friendOf)
            - ```
              @link[ListCode](list-code friendOf)
            @link[AfterList](after-list friendOf)
            ~~~
            @link[Hidden](hidden friendOf)
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        assertEquals(
            listOf("escaped", "unmatched", "after-quote", "after-list"),
            analysis.references.filter { it.field == "relation.target" }.map { it.targetId },
        )
    }

    @Test
    fun `skips malformed body relations gracefully`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link{[Bob
            @link{}[Bob]notparen
            @link{}[Bob](bob
            @link{}[Bob](bob friend of)
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")
        assertTrue(analysis.references.none { it.field == "relation.target" })
    }

    @Test
    fun `timeline id and sameAxisAs are Timeline symbols`() {
        val text = """
            ---
            id: CommonEra
            kind: Timeline
            sameAxisAs: Other
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/timeline.md")
        assertEquals(ReferenceTargetKind.Timeline, analysis.definitions.single().kind)
        assertEquals(ReferenceTargetKind.Timeline, analysis.references.single { it.field == "sameAxisAs" }.kind)
        assertEquals("Other", analysis.references.single { it.field == "sameAxisAs" }.targetId)
    }

    @Test
    fun `extracts references from canonical link syntax`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link(validTime=CommonEra){weight=0.2}[Bob](bob "friendOf")
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")
        assertEquals("bob", analysis.references.single { it.field == "relation.target" }.targetId)
        assertEquals("friendOf", analysis.references.single { it.field == "relation.type" }.targetId)
        val targetOffset = text.lastIndexOf("bob") + 1
        assertEquals(ReferenceTargetKind.Node, analyzer.findReferenceAt(analysis, targetOffset)?.kind)
    }

    @Test
    fun `extracts Timeline references from yaml and inline validTime`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: CommonEra
            props:
              born:
                timeline: Branch
                timecode: 1
            ---
            @props(validTime=[CommonEra,Branch(from=1)]){age=1}
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")
        val timelines = analysis.references.filter { it.kind == ReferenceTargetKind.Timeline }
        assertTrue(timelines.count { it.targetId == "CommonEra" } >= 2)
        assertTrue(timelines.count { it.targetId == "Branch" } >= 2)
        val inlineOffset = text.lastIndexOf("Branch") + 1
        assertEquals(ReferenceTargetKind.Timeline, analyzer.findReferenceAt(analysis, inlineOffset)?.kind)
    }

    @Test
    fun `extracts only timeline value tokens from inline GraphMD syntax`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @props(validTime=[
              CommonEra,
              Branch(from={value="today",timecode=1},to={timeline=Endpoint,timecode=2})
            ]){
              born={timeline="QuotedEra",timecode=1},
              active={from={timeline=Past,timecode=1},to={timeline=Future,timecode=2}},
              note="validTime=today and timeline=Fake",
              escaped="say \"timeline=AlsoFake\"",
              invalidSingleQuoted='timeline=SingleFake'
            }
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/alice.md")
        val timelines = analysis.references.filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(
            setOf("CommonEra", "Branch", "Endpoint", "QuotedEra", "Past", "Future"),
            timelines.map { it.targetId }.toSet(),
        )
        assertEquals(timelines.size, timelines.map { it.range }.distinct().size)
        assertTrue(timelines.none { it.targetId in setOf("today", "Fake", "AlsoFake", "SingleFake", "from", "to", "timecode") })
        timelines.forEach { reference ->
            assertEquals(reference.targetId, text.substring(reference.range.start, reference.range.end))
        }
    }

    @Test
    fun `keeps parsed timeline references before incomplete inline syntax`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @props{first={timeline=CommonEra,timecode=1}, second={timeline=}}
        """.trimIndent()

        val timelines = analyzer.analyze(text, "/tmp/alice.md").references
            .filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(listOf("CommonEra"), timelines.map { it.targetId })
    }

    @Test
    fun `recovers timeline tokens from unclosed directives without consuming following prose`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @props(validTime=[First, Second(
            ordinary prose timeline=Fake
            @props(validTime=Real){age=1}
            @props{born={timeline=ObjectEra,timecode=1}
            more prose timeline=AlsoFake
            @link(validTime=LinkEra
            final prose timeline=LastFake
            @link(validTime=SameLine @props(validTime=AfterSame){x=1}
        """.trimIndent()

        val timelines = analyzer.analyze(text, "/tmp/alice.md").references
            .filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(
            listOf("First", "Real", "ObjectEra", "LinkEra", "SameLine", "AfterSame"),
            timelines.map { it.targetId },
        )
    }

    @Test
    fun `ignores timeline-looking syntax in indented fenced and inline code`() {
        val body = listOf(
            "    @props(validTime=SpaceFake){x={timeline=SpaceObjectFake}}",
            "\t@link(validTime=TabFake)[Bob](bob friendOf)",
            "`@props(validTime=SpanFake){x=1}`",
            "```",
            "@link(validTime=FenceFake)[Bob](bob friendOf)",
            "```",
            "@props(validTime=Real){x=1}",
        ).joinToString("\n")
        val text = "---\nid: alice\nkind: Node\ntype: Person\n---\n$body"

        val timelines = analyzer.analyze(text, "/tmp/alice.md").references
            .filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(listOf("Real"), timelines.map { it.targetId })
    }

    @Test
    fun `extracts legacy Property Schema timeline selector ids with precise ranges`() {
        val text = """
            ---
            id: Event
            kind: NodeType
            props:
              happenedAt:
                type: instant
                timeline:
                  - CommonEra:
                      mapped: true
                  - id: 'ThirdAge' # explicit selector
                    mapped: false
              history:
                type: array
                items:
                  type: instant
                  timelines:
                    - "Branch":
                        mapped: false
                    - { id: ProjectEra, mapped: true }
              inline:
                type: duration
                timeline: [{ FlowEra: { mapped: false } }, { id: "QuotedEra", mapped: true }]
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/event.md")
        val selectors = analysis.references.filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(
            listOf("CommonEra", "ThirdAge", "Branch", "ProjectEra", "FlowEra", "QuotedEra"),
            selectors.map { it.targetId },
        )
        selectors.forEach { reference ->
            assertEquals(reference.targetId, analysis.text.substring(reference.range.start, reference.range.end))
            assertEquals(reference.targetId, analyzer.findReferenceAt(analysis, reference.range.start + 1)?.targetId)
        }
        assertEquals(listOf("Event"), analysis.definitions.map { it.id })
    }

    @Test
    fun `extracts current Property Schema timeline selector with quotes comments and CRLF`() {
        val text = """
            ---
            id: Event
            kind: RelType
            props:
              happenedAt:
                type: instant
                timeline: "CommonEra" # keep quotes on rename
            ---
        """.trimIndent().replace("\n", "\r\n")

        val analysis = analyzer.analyze(text, "/tmp/event.md")
        val reference = analysis.references.single { it.kind == ReferenceTargetKind.Timeline }

        assertEquals("CommonEra", reference.targetId)
        assertEquals("CommonEra", analysis.text.substring(reference.range.start, reference.range.end))
        assertEquals('"', analysis.text[reference.range.start - 1])
        assertEquals('"', analysis.text[reference.range.end])
        assertEquals("Event", analysis.definitions.single().id)
    }

    @Test
    fun `extracts explicit legacy selector id regardless of sibling order or extra fields`() {
        fun selectorIds(kind: String): Pair<List<String>, List<String>> {
            val text = """
                ---
                id: Event
                kind: $kind
                props:
                  when:
                    type: instant
                    timeline:
                      - id: IdFirst
                        mapped: false
                      - mapped: true
                        id: MappedFirst
                      - extra: ignored
                        mapped: false
                        id: WithExtra
                      - CompactPlusTwo:
                        mapped: true
                      - CompactPlusFour:
                          mapped: true
                      - CompactDeep:
                            mapped: true
                      - BodyOrder:
                        extra: ignored
                        mapped: false
                      - Ambiguous:
                        id: NestedId
                        mapped: true
                      - id:
                        id: NestedExplicitId
                        mapped: false
                      - mapped:
                        id: NestedMappedId
                        mapped: true
                ---
            """.trimIndent()
            val analysis = analyzer.analyze(text, "/tmp/$kind.md")
            return analysis.references.filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId } to
                analysis.definitions.map { it.id }
        }

        val expected = listOf(
            "IdFirst",
            "MappedFirst",
            "WithExtra",
            "CompactPlusTwo",
            "CompactPlusFour",
            "CompactDeep",
            "BodyOrder",
            "Ambiguous",
        ) to listOf("Event")
        assertEquals(expected, selectorIds("NodeType"))
        assertEquals(expected, selectorIds("RelType"))
    }

    @Test
    fun `decodes double quoted selector escapes while preserving raw ranges`() {
        val text = """
            ---
            id: Event
            kind: NodeType
            props:
              current:
                type: instant
                timeline: "Line\nEra"
              explicit:
                type: instant
                timeline:
                  - id: "Quote\"Era"
                    mapped: false
              compact:
                type: instant
                timeline:
                  - "Slash\\Era":
                      mapped: true
              flow:
                type: instant
                timeline: [{ id: "Tab\tEra", mapped: false }]
            ---
        """.trimIndent()

        val references = analyzer.analyze(text, "/tmp/event.md").references
            .filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(listOf("Line\nEra", "Quote\"Era", "Slash\\Era", "Tab\tEra"), references.map { it.targetId })
        assertEquals(
            listOf("""Line\nEra""", """Quote\"Era""", """Slash\\Era""", """Tab\tEra"""),
            references.map { text.substring(it.range.start, it.range.end) },
        )
    }

    @Test
    fun `indexes timeline fields only in direct or items Property Schema contexts`() {
        val text = """
            ---
            id: Event
            kind: NodeType
            props:
              direct:
                type: instant
                timeline: Direct
                meta:
                  timeline: Metadata
              nested:
                type: array
                items:
                  type: array
                  metadata:
                    timeline: ItemMetadata
                  items:
                    type: instant
                    timeline: Nested
              other:
                type: string
                unknown:
                  items:
                    timeline: UnknownItems
            ---
        """.trimIndent()

        val references = analyzer.analyze(text, "/tmp/event.md").references
            .filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(listOf("Direct", "Nested"), references.map { it.targetId })
    }

    @Test
    fun `extracts quoted nested Timeline scalars without quotes or false positives`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: "Quoted" # double
              - timeline: 'Single' # single
              - timeline: Escaped # bare
            props:
              label: "Quoted"
              timeline: "not closed
            ---
        """.trimIndent()

        val timelines = analyzer.analyze(text, "/tmp/alice.md").references
            .filter { it.kind == ReferenceTargetKind.Timeline }

        assertEquals(listOf("Quoted", "Single", "Escaped"), timelines.map { it.targetId })
        timelines.forEach { reference ->
            assertEquals(reference.targetId.length, reference.range.end - reference.range.start)
            assertTrue(text.substring(reference.range.start, reference.range.end).none { it == '"' || it == '\'' })
        }
    }

    @Test
    fun `collects only structurally Timeline-valued yaml fields`() {
        val timelineText = """
            ---
            id: T
            kind: Timeline
            mapsTo:
              - kind: alignment
                timeline: "Base" # mapping target
                offset: 1
            props:
              from: note
              to: other
              timeline: label
            ---
        """.trimIndent()
        val nodeText = """
            ---
            id: event
            kind: Node
            type: Event
            validTime:
              - timeline: Valid
            props:
              instant:
                timeline: Instant
                timecode: 1
              duration:
                from:
                  timeline: Endpoint
                  timecode: 2
              numericDuration:
                timeline: NumericInt
                from: 1
              decimalDuration:
                timeline: NumericDecimal
                to: 1.5
              ordinary:
                timeline: NotAReference
              ordinaryDurationLike:
                timeline: StillNotAReference
                from: note
              ordinaryEndpoint:
                from:
                  timeline: AlsoNotAReference
            ---
        """.trimIndent()
        val schemaText = """
            ---
            id: Event
            kind: NodeType
            props:
              at:
                type: instant
                timeline: "Schema"
              alternatives:
                type: instant
                timeline:
                  - Singular
                  - Legacy.Single
                  - Nested
                  - Listed
                  - Canonical
                  - CanonicalList
                  - First
                  - Mapped
                  - Third.Age
                  - _Leading
              inline:
                type: instant
                timeline: [, Inline,, "QuotedInline",] # choices
            ---
        """.trimIndent()

        assertEquals(
            listOf("Base"),
            analyzer.analyze(timelineText, "/tmp/t.md").references
                .filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
        assertEquals(
            listOf("Valid", "Instant", "Endpoint", "NumericInt", "NumericDecimal"),
            analyzer.analyze(nodeText, "/tmp/node.md").references
                .filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
        val schemaAnalysis = analyzer.analyze(schemaText, "/tmp/schema.md")
        assertTrue(schemaAnalysis.parsed.diagnostics.isEmpty(), schemaAnalysis.parsed.diagnostics.joinToString())
        assertEquals(
            listOf(
                "Schema",
                "Singular",
                "Legacy.Single",
                "Nested",
                "Listed",
                "Canonical",
                "CanonicalList",
                "First",
                "Mapped",
                "Third.Age",
                "_Leading",
                "Inline",
                "QuotedInline",
            ),
            schemaAnalysis.references
                .filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
        schemaAnalysis.references.filter { it.targetId in setOf("Inline", "QuotedInline") }.forEach { reference ->
            assertEquals(reference.targetId, schemaText.substring(reference.range.start, reference.range.end))
        }

        val invalidSelectorText = """
            ---
            id: Invalid
            kind: NodeType
            props:
              missingMapped:
                type: instant
                timeline:
                  - id: MissingMapped
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
        """.trimIndent()
        val invalidAnalysis = analyzer.analyze(invalidSelectorText, "/tmp/invalid-schema.md")
        assertTrue(invalidAnalysis.parsed.diagnostics.isNotEmpty())
        assertEquals(
            listOf("Other"),
            invalidAnalysis.references.filter { it.kind == ReferenceTargetKind.Timeline }.map { it.targetId },
        )
    }

    @Test
    fun `resets current list field when a non indented non mapping line appears`() {
        val text = """
            ---
            id: worksAt
            kind: RelType
            from:
            stray
              - Person
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/rel.md")
        assertTrue(analysis.references.none { it.field == "from" })
    }

    @Test
    fun `infers front matter completion kinds for mapping values`() {
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
        """.trimIndent()
        val nodeAnalysis = analyzer.analyze(nodeText, "/tmp/n.md")
        assertEquals(ReferenceTargetKind.NodeType, analyzer.inferCompletionKind(nodeAnalysis, nodeText.indexOf("Person")))
        assertNull(analyzer.inferCompletionKind(nodeAnalysis, nodeText.indexOf("type")))
        assertNull(analyzer.inferCompletionKind(nodeAnalysis, nodeText.indexOf("id")))

        val relText = """
            ---
            id: worksAt
            kind: RelType
            extends: relatedTo
            from: Person
            to: Organization
            ---
        """.trimIndent()
        val relAnalysis = analyzer.analyze(relText, "/tmp/r.md")
        assertEquals(ReferenceTargetKind.RelType, analyzer.inferCompletionKind(relAnalysis, relText.indexOf("relatedTo")))
        assertEquals(ReferenceTargetKind.NodeType, analyzer.inferCompletionKind(relAnalysis, relText.indexOf("Person")))
        assertEquals(ReferenceTargetKind.NodeType, analyzer.inferCompletionKind(relAnalysis, relText.indexOf("Organization")))
    }

    @Test
    fun `infers completion kind by scanning upward over list items`() {
        val relText = """
            ---
            id: worksAt
            kind: RelType
            from:
              - Person
            extends:

              - relatedTo
            ---
        """.trimIndent()
        val relAnalysis = analyzer.analyze(relText, "/tmp/r.md")
        assertEquals(ReferenceTargetKind.NodeType, analyzer.inferCompletionKind(relAnalysis, relText.indexOf("Person")))
        assertEquals(ReferenceTargetKind.RelType, analyzer.inferCompletionKind(relAnalysis, relText.indexOf("relatedTo")))

        val nodeText = """
            ---
            id: Person
            kind: NodeType
            extends:
              - Entity
            ---
        """.trimIndent()
        val nodeAnalysis = analyzer.analyze(nodeText, "/tmp/n.md")
        assertEquals(ReferenceTargetKind.NodeType, analyzer.inferCompletionKind(nodeAnalysis, nodeText.indexOf("Entity")))
    }

    @Test
    fun `upward scan breaks at non indented non mapping line`() {
        val text = """
            ---
            id: worksAt
            kind: RelType
            notes: value
              - stray
            ---
        """.trimIndent()
        val analysis = analyzer.analyze(text, "/tmp/r.md")
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("stray")))
    }

    @Test
    fun `infers body completion kind inside relation target and reltype regions`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link{}[Bob](bob friendOf) tail
        """.trimIndent()
        val analysis = analyzer.analyze(text, "/tmp/n.md")

        assertEquals(ReferenceTargetKind.Node, analyzer.inferCompletionKind(analysis, text.indexOf("bob")))
        assertEquals(ReferenceTargetKind.RelType, analyzer.inferCompletionKind(analysis, text.indexOf("friendOf")))
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("@link")))
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("tail")))
    }

    @Test
    fun `limits body validTime completion to timeline tokens in GraphMD syntax outside code`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            ::: history validTime=[FirstEra, SecondEra(from=1,to=2)] annotation
            body
            :::
            @props(validTime=DirectiveEra){age=1}
            @props{age(validTime=[PropertyEra, OtherEra])=2}
            @link(validTime=LinkEra)[Bob](bob friendOf)
            prose validTime=NotGraphMd
            ```markdown
            ::: hidden validTime=CodeEra
            @props(validTime=CodeDirective){age=3}
            ```
        """.trimIndent()
        val analysis = analyzer.analyze(text, "/tmp/n.md")

        assertEquals(
            ReferenceTargetKind.Timeline,
            analyzer.inferCompletionKind(analysis, text.indexOf("SecondEra") + 3),
        )
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("from=1") + "from=".length))
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("annotation") + 3))
        assertEquals(
            ReferenceTargetKind.Timeline,
            analyzer.inferCompletionKind(analysis, text.indexOf("DirectiveEra") + 3),
        )
        assertEquals(
            ReferenceTargetKind.Timeline,
            analyzer.inferCompletionKind(analysis, text.indexOf("OtherEra") + 3),
        )
        assertEquals(
            ReferenceTargetKind.Timeline,
            analyzer.inferCompletionKind(analysis, text.indexOf("LinkEra") + 3),
        )
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("NotGraphMd") + 3))
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("CodeEra") + 3))
        assertNull(analyzer.inferCompletionKind(analysis, text.indexOf("CodeDirective") + 3))
    }

    @Test
    fun `body completion returns null for malformed or non graph parentheses`() {
        val unclosed = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link{}[Bob](bob friendOf
        """.trimIndent()
        val unclosedAnalysis = analyzer.analyze(unclosed, "/tmp/n.md")
        assertNull(analyzer.inferCompletionKind(unclosedAnalysis, unclosed.indexOf("bob")))

        val linkText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            see [Bob](bob) here
        """.trimIndent()
        val linkAnalysis = analyzer.analyze(linkText, "/tmp/n.md")
        assertNull(analyzer.inferCompletionKind(linkAnalysis, linkText.indexOf("bob")))
    }

    @Test
    fun `infers null when document is missing`() {
        val text = "no front matter"
        val analysis = analyzer.analyze(text, "/tmp/n.md")
        assertNull(analyzer.inferCompletionKind(analysis, 0))
    }

    @Test
    fun `uses dot dot dot as front matter terminator`() {
        val text = "---\nid: alice\nkind: Node\ntype: Person\n...\nbody"
        val analysis = analyzer.analyze(text, "/tmp/n.md")
        assertEquals("alice", analysis.definitions.single().id)
        assertEquals("Person", analysis.references.single { it.field == "type" }.targetId)
    }

    @Test
    fun `extracts property definitions and yaml node property references`() {
        val typeText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
              age:
                type: number
            ---
        """.trimIndent()
        val typeAnalysis = analyzer.analyze(typeText, "/tmp/Person.md")

        assertEquals(listOf("name", "age"), typeAnalysis.propertyDefinitions.map { it.name })
        assertTrue(typeAnalysis.propertyDefinitions.all { it.ownerId == "Person" })
        assertTrue(typeAnalysis.propertyDefinitions.all { it.ownerKind == PropertyOwnerKind.NodeType })
        assertEquals(
            "name",
            typeText.substring(
                typeAnalysis.propertyDefinitions.first().range.start,
                typeAnalysis.propertyDefinitions.first().range.end,
            ),
        )

        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
                name: Alice
                age:
                  value: 20
                  validTime: []
            ---
        """.trimIndent()
        val nodeAnalysis = analyzer.analyze(nodeText, "/tmp/alice.md")

        assertEquals(listOf("name", "age"), nodeAnalysis.propertyReferences.map { it.name })
        assertTrue(nodeAnalysis.propertyReferences.all { it.ownerId == "Person" })
        assertTrue(nodeAnalysis.propertyReferences.none { it.name in setOf("value", "validTime") })
    }

    @Test
    fun `extracts unicode and non identifier yaml property keys`() {
        val typeText = """
            ---
            id: Person
            kind: NodeType
            props:
              名前:
                type: string
              1st value:
                type: number
            ---
        """.trimIndent()
        val typeAnalysis = analyzer.analyze(typeText, "/tmp/Person.md")

        assertEquals(listOf("名前", "1st value"), typeAnalysis.propertyDefinitions.map { it.name })
        typeAnalysis.propertyDefinitions.forEach { definition ->
            assertEquals(
                definition.name,
                typeText.substring(definition.range.start, definition.range.end),
            )
        }

        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              名前: Alice
              1st value: 1
            ---
        """.trimIndent()
        val nodeAnalysis = analyzer.analyze(nodeText, "/tmp/alice.md")

        assertEquals(listOf("名前", "1st value"), nodeAnalysis.propertyReferences.map { it.name })
    }

    @Test
    fun `extracts yaml property keys containing colons with exact ranges`() {
        val typeText = """
            ---
            id: Person
            kind: NodeType
            props:
              lang:ja:
                type: string
            ---
        """.trimIndent()
        val typeAnalysis = analyzer.analyze(typeText, "/tmp/Person.md")

        assertTrue(typeAnalysis.parsed.diagnostics.isEmpty(), typeAnalysis.parsed.diagnostics.joinToString("\n") { it.message })
        val definition = typeAnalysis.propertyDefinitions.single()
        assertEquals("lang:ja", definition.name)
        assertEquals(definition.name, typeText.substring(definition.range.start, definition.range.end))

        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              lang:ja: あいうえお
            ---
        """.trimIndent()
        val nodeAnalysis = analyzer.analyze(nodeText, "/tmp/alice.md")

        val reference = nodeAnalysis.propertyReferences.single()
        assertEquals("lang:ja", reference.name)
        assertEquals(reference.name, nodeText.substring(reference.range.start, reference.range.end))
    }

    @Test
    fun `extracts top level props and relation keys from body`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @props{
              name = "Alice"
              age(validTime=CommonEra) = 20,
              born = { timeline = CommonEra, timecode = 1 }
              name = "A"
            }
            @link{weight = 0.2, metadata = { value = "x" }}[Bob](bob friendOf)
        """.trimIndent()
        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        val nodeProperties = analysis.propertyReferences.filter { it.ownerKind == PropertyOwnerKind.NodeType }
        assertEquals(listOf("name", "age", "born", "name"), nodeProperties.map { it.name })
        assertTrue(nodeProperties.all { it.ownerId == "Person" })
        assertTrue(nodeProperties.none { it.name in setOf("timeline", "timecode") })

        val relationProperties = analysis.propertyReferences.filter { it.ownerKind == PropertyOwnerKind.RelType }
        assertEquals(listOf("weight", "metadata"), relationProperties.map { it.name })
        assertTrue(relationProperties.all { it.ownerId == "friendOf" })
        assertTrue(relationProperties.none { it.name == "value" })

        val age = nodeProperties.first { it.name == "age" }
        assertEquals("age", text.substring(age.range.start, age.range.end))
        assertEquals(age, analyzer.findPropertyReferenceAt(analysis, age.range.start + 1))
    }

    @Test
    fun `ignores property bindings in code regions`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            `@props{inline = 1}`
            ```
            @props{fenced = 1}
            @link{weight = 1}[Bob](bob friendOf)
            ```
            @props{visible = 1}
        """.trimIndent()
        val analysis = analyzer.analyze(text, "/tmp/alice.md")

        assertEquals(listOf("visible"), analysis.propertyReferences.map { it.name })
    }
}
