package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `returns empty analysis when front matter is never closed`() {
        val text = "---\nid: alice\nkind: Node"
        val analysis = analyzer.analyze(text, "/tmp/open.md")

        assertEquals(0, analysis.frontMatterEndOffset)
        assertTrue(analysis.definitions.isEmpty())
        assertTrue(analysis.references.isEmpty())
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
    fun `timeline id and extends are Timeline symbols`() {
        val text = """
            ---
            id: CommonEra
            kind: Timeline
            extends: Other
            ---
        """.trimIndent()

        val analysis = analyzer.analyze(text, "/tmp/timeline.md")
        assertEquals(ReferenceTargetKind.Timeline, analysis.definitions.single().kind)
        assertEquals(ReferenceTargetKind.Timeline, analysis.references.single { it.field == "extends" }.kind)
        assertEquals("Other", analysis.references.single { it.field == "extends" }.targetId)
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
