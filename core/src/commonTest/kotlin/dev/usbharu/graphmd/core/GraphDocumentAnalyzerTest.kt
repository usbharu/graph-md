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
    fun `extracts node definitions, type references and body relation references`() {
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
    fun `ignores comments, blank lines and code regions in body`() {
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
}
