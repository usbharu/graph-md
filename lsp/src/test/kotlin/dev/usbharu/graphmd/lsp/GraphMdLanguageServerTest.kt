package dev.usbharu.graphmd.lsp

import dev.usbharu.graphmd.core.GraphDocumentAnalyzer
import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.ReferenceTargetKind
import dev.usbharu.graphmd.core.model.DocumentKind
import dev.usbharu.graphmd.core.model.NodeDocument
import dev.usbharu.graphmd.core.model.NodeTypeDocument
import dev.usbharu.graphmd.core.model.PropIndex
import dev.usbharu.graphmd.core.model.PropType
import dev.usbharu.graphmd.core.model.ResolvedPropSchema
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.core.model.TimelineSelector
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphMdLanguageServerTest {
    private val analyzer = GraphDocumentAnalyzer()

    @Test
    fun `Node type and relation references are discovered`() {
        val analysis = analyzer.analyze(
            """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            Hello @link{}[Bob](bob "friendOf")
            """.trimIndent(),
            "/workspace/alice.md",
        )

        assertEquals("alice", analysis.definitions.single().id)
        assertEquals(3, analysis.references.size)
        assertEquals(ReferenceTargetKind.NodeType, analysis.references.first().kind)
        assertEquals("Person", analysis.references.first().targetId)
        assertEquals("bob", analysis.references[1].targetId)
        assertEquals("friendOf", analysis.references[2].targetId)
    }

    @Test
    fun `completion context is inferred from front matter and relation body`() {
        val analysis = analyzer.analyze(
            """
            ---
            id: alice
            kind: Node
            type: Per
            ---
            @link{}[Bob](bob fri)
            """.trimIndent(),
            "/workspace/alice.md",
        )

        val typeOffset = analysis.text.indexOf("Per") + 2
        val relationOffset = analysis.text.indexOf("fri") + 2
        assertEquals(ReferenceTargetKind.NodeType, analyzer.inferCompletionKind(analysis, typeOffset))
        assertEquals(ReferenceTargetKind.RelType, analyzer.inferCompletionKind(analysis, relationOffset))
        assertNotNull(analyzer.findReferenceAt(analysis, analysis.text.indexOf("fri") + 1))
    }

    @Test
    fun `strict props completion suggests schema keys and timeline ids`() {
        val schema = mapOf(
            "name" to ResolvedPropSchema(type = PropType.string),
            "birthDate" to ResolvedPropSchema(type = PropType.instant, index = PropIndex.range, timeline = TimelineSelector.Id("CommonEra")),
        )
        val keyResolver = PropsCompletionContextResolver(
            text = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{na
            """.trimIndent(),
            offset = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{na
            """.trimIndent().length,
            rootSchema = schema,
            timelineIds = listOf("CommonEra"),
        )
        val keyItems = keyResolver.resolve()?.items?.map { it.label }.orEmpty()
        assertTrue("name" in keyItems)

        val duplicateResolver = PropsCompletionContextResolver(
            text = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{name = "Alice", 
            """.trimIndent(),
            offset = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{name = "Alice", 
            """.trimIndent().length,
            rootSchema = schema,
            timelineIds = listOf("CommonEra"),
        )
        val duplicateItems = duplicateResolver.resolve()?.items?.map { it.label }.orEmpty()
        assertTrue("name" !in duplicateItems)
        assertTrue("birthDate" in duplicateItems)

        val timelineResolver = PropsCompletionContextResolver(
            text = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{birthDate = { timeline = Com
            """.trimIndent(),
            offset = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{birthDate = { timeline = Com
            """.trimIndent().length,
            rootSchema = schema,
            timelineIds = listOf("CommonEra"),
        )
        val timelineItems = timelineResolver.resolve()?.items?.map { it.label }.orEmpty()
        assertEquals(listOf("CommonEra"), timelineItems)

        val instantShortcutResolver = PropsCompletionContextResolver(
            text = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{birthDate = 
            """.trimIndent(),
            offset = """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @props{birthDate = 
            """.trimIndent().length,
            rootSchema = schema,
            timelineIds = listOf("CommonEra"),
        )
        val instantShortcutItems = instantShortcutResolver.resolve()?.items?.map { it.label }.orEmpty()
        assertTrue("0" in instantShortcutItems)
        assertTrue("{" in instantShortcutItems)
    }

    @Test
    fun `relation props completion resolves rel type schema`() {
        val relationText = "@link{since = { timeline = CommonEra }}[Bob](bob \"friendOf\")"
        val relationContext = RelationPropsCompletionContextResolver(
            text = relationText,
            offset = relationText.indexOf("CommonEra") + 3,
        ).resolve()

        assertNotNull(relationContext)
        assertEquals("friendOf", relationContext.relType)
    }

    @Test
    fun `relation props completion resolves canonical link syntax`() {
        val text = "@link(validTime=CommonEra){since = 1}[Bob](bob \"friendOf\")"
        val context = RelationPropsCompletionContextResolver(
            text = text,
            offset = text.indexOf("since") + "since".length,
        ).resolve()

        assertNotNull(context)
        assertEquals("friendOf", context.relType)
        assertEquals(text.indexOf('{'), context.braceStart)
    }

    @Test
    fun `server completion returns node props from workspace node type`() {
        val nodeTypeText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
              birthDate:
                type: instant
                timeline: CommonEra
            ---
            """.trimIndent()
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @props{
            """.trimIndent()
        val compiled = GraphCompiler().compileSources(
            listOf(
                SourceDocument(nodeTypeText, "/workspace/types/Person.md"),
                SourceDocument(nodeText, "/workspace/alice.md"),
            ),
        )
        assertTrue(compiled.nodeTypes.any { it.id == "Person" })

        val server = GraphMdLanguageServer()
        server.initialize(InitializeParams()).get()
        val textService = server.textDocumentService

        textService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/types/Person.md",
                    "markdown",
                    1,
                    nodeTypeText,
                ),
            ),
        )
        textService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/alice.md",
                    "markdown",
                    1,
                    nodeText,
                ),
            ),
        )

        val items = textService.completion(
            CompletionParams(
                TextDocumentIdentifier("file:///workspace/alice.md"),
                Position(5, 7),
            ),
        ).get().left.orEmpty().map { it.label }

        assertTrue("name" in items, items.joinToString())
        assertTrue("birthDate" in items, items.joinToString())
    }

    @Test
    fun `reference diagnostics are highlighted at referenced token`() {
        val server = GraphMdLanguageServer()
        val published = mutableMapOf<String, List<org.eclipse.lsp4j.Diagnostic>>()
        server.connect(
            object : LanguageClient {
                override fun publishDiagnostics(params: PublishDiagnosticsParams) {
                    published[params.uri] = params.diagnostics
                }

                override fun telemetryEvent(p0: Any) = Unit

                override fun showMessage(p0: MessageParams) = Unit

                override fun showMessageRequest(p0: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
                    CompletableFuture.completedFuture(MessageActionItem())

                override fun logMessage(p0: MessageParams) = Unit
            },
        )
        server.initialize(InitializeParams()).get()
        val textService = server.textDocumentService

        textService.didOpen(
            DidOpenTextDocumentParams(
                TextDocumentItem(
                    "file:///workspace/alice.md",
                    "markdown",
                    1,
                    """
                    ---
                    id: alice
                    kind: Node
                    type: MissingPerson
                    ---
                    Hello @link{}[Bob](bob missingRel)
                    """.trimIndent(),
                ),
            ),
        )

        val diagnostics = published.getValue("file:///workspace/alice.md")
        val unknownType = diagnostics.first { it.message == "Unknown NodeType: MissingPerson" }
        assertEquals(3, unknownType.range.start.line)
        assertEquals(6, unknownType.range.start.character)
        assertEquals(19, unknownType.range.end.character)

        val unknownRel = diagnostics.first { it.message == "Unknown RelType: missingRel" }
        assertEquals(5, unknownRel.range.start.line)
        assertEquals(23, unknownRel.range.start.character)
        assertEquals(33, unknownRel.range.end.character)
    }

    @Test
    fun `front matter completion suggests node props keys and timeline values`() {
        val schema = mapOf(
            "name" to ResolvedPropSchema(type = PropType.string),
            "birthDate" to ResolvedPropSchema(type = PropType.instant, index = PropIndex.range, timeline = TimelineSelector.Id("CommonEra")),
        )
        val propKeyText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              
            ---
        """.trimIndent()
        val propKeyItems = FrontMatterCompletionResolver(
            text = propKeyText,
            offset = propKeyText.indexOf("\n  \n") + 3,
            parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
            nodePropsSchema = schema,
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("name" in propKeyItems)
        assertTrue("birthDate" in propKeyItems)

        val timelineText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              birthDate:
                timeline: Com
            ---
        """.trimIndent()
        val timelineItems = FrontMatterCompletionResolver(
            text = timelineText,
            offset = timelineText.indexOf("Com") + 3,
            parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
            nodePropsSchema = schema,
        ).resolve()?.map { it.label }.orEmpty()
        assertEquals(listOf("CommonEra"), timelineItems)

        val intervalSchema = mapOf(
            "activeDuring" to ResolvedPropSchema(type = PropType.duration, index = PropIndex.range, timeline = TimelineSelector.Id("CommonEra")),
        )
        val intervalKeyText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              activeDuring:
                
            ---
        """.trimIndent()
        val intervalKeyItems = FrontMatterCompletionResolver(
            text = intervalKeyText,
            offset = intervalKeyText.indexOf("\n    \n") + 5,
            parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
            nodePropsSchema = intervalSchema,
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("fromTimecode" !in intervalKeyItems)
        assertTrue("fromPrecision" !in intervalKeyItems)
    }

    @Test
    fun `front matter completion offers canonical timeline selector ids`() {
        val timelineText = """
            ---
            id: Event
            kind: NodeType
            props:
              happenedAt:
                type: instant
                timeline: 
            ---
        """.trimIndent()
        val labels = FrontMatterCompletionResolver(
            text = timelineText,
            offset = timelineText.indexOf("timeline: ") + "timeline: ".length,
            parsedDocument = NodeTypeDocument(id = "Event", sourcePath = "/tmp/event.md"),
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra", "ThirdAge"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("CommonEra" in labels)
        assertTrue("ThirdAge" in labels)
        assertTrue("any" !in labels)
        assertTrue(labels.none { it.startsWith("mapped:") })
    }

    @Test
    fun `front matter completion suggests yaml keys and enum values`() {
        val kindText = """
            ---
            id: alice
            kind: No
            ---
        """.trimIndent()
        val kindItems = FrontMatterCompletionResolver(
            text = kindText,
            offset = kindText.indexOf("No") + 2,
            parsedDocument = null,
            nodeTypeIds = listOf("Person"),
            relTypeIds = listOf("friendOf"),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("Node" in kindItems)

        val topLevelText = """
            ---
            id: t
            kind: Timeline
            tim
            ---
        """.trimIndent()
        val topLevelItems = FrontMatterCompletionResolver(
            text = topLevelText,
            offset = topLevelText.indexOf("tim") + 3,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("timecode" in topLevelItems)

        val timecodeTopLevelText = """
            ---
            id: t
            kind: Timeline
            tim
            ---
        """.trimIndent()
        val timecodeTopLevelItems = FrontMatterCompletionResolver(
            text = timecodeTopLevelText,
            offset = timecodeTopLevelText.indexOf("tim") + 3,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("timecode" in timecodeTopLevelItems)

        val timelineBlankTopLevelText = """
            ---
            id: t
            kind: Timeline
            
            ---
        """.trimIndent()
        val timelineBlankTopLevelItems = FrontMatterCompletionResolver(
            text = timelineBlankTopLevelText,
            offset = timelineBlankTopLevelText.indexOf("\n\n") + 1,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("mapping" !in timelineBlankTopLevelItems)

        val timecodeNestedText = """
            ---
            id: t
            kind: Timeline
            timecode:
              type: nu
            ---
        """.trimIndent()
        val timecodeNestedItems = FrontMatterCompletionResolver(
            text = timecodeNestedText,
            offset = timecodeNestedText.indexOf("nu") + 2,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("number" in timecodeNestedItems)

        val timecodeKeyText = """
            ---
            id: t
            kind: Timeline
            timecode:
              type: number
              
            ---
        """.trimIndent()
        val timecodeKeyItems = FrontMatterCompletionResolver(
            text = timecodeKeyText,
            offset = timecodeKeyText.indexOf("\n    \n") + 5,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("direction" !in timecodeKeyItems)

        val mappingKeyText = """
            ---
            id: t
            kind: Timeline
            mappings:
              - kind: offset
                off
            ---
        """.trimIndent()
        val mappingKeyItems = FrontMatterCompletionResolver(
            text = mappingKeyText,
            offset = mappingKeyText.lastIndexOf("off") + 3,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("offset" in mappingKeyItems)
        assertTrue("unit" !in mappingKeyItems)
        assertTrue("entries" !in mappingKeyItems)

        val propKeyText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                req
            ---
        """.trimIndent()
        val propKeyItems = FrontMatterCompletionResolver(
            text = propKeyText,
            offset = propKeyText.indexOf("req") + 3,
            parsedDocument = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md"),
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("required" in propKeyItems)

        val validTimeText = """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - tim
            ---
        """.trimIndent()
        val validTimeItems = FrontMatterCompletionResolver(
            text = validTimeText,
            offset = validTimeText.indexOf("tim") + 3,
            parsedDocument = NodeDocument("alice", "Person", sourcePath = "/tmp/alice.md"),
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("timeline" in validTimeItems)

        val propTypeText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: st
            ---
        """.trimIndent()
        val propTypeItems = FrontMatterCompletionResolver(
            text = propTypeText,
            offset = propTypeText.indexOf("st") + 2,
            parsedDocument = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md"),
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("string" in propTypeItems)

        val nextKeyText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
                ind
            ---
        """.trimIndent()
        val nextKeyItems = FrontMatterCompletionResolver(
            text = nextKeyText,
            offset = nextKeyText.lastIndexOf("ind") + 3,
            parsedDocument = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md"),
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("index" in nextKeyItems)
        assertTrue("string" !in nextKeyItems)

        val blankNextKeyText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                required: true
                
            ---
        """.trimIndent()
        val blankNextKeyOffset = blankNextKeyText.indexOf("\n    \n") + 5
        val blankNextKeyItems = FrontMatterCompletionResolver(
            text = blankNextKeyText,
            offset = blankNextKeyOffset,
            parsedDocument = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md"),
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("index" in blankNextKeyItems)
        assertTrue("true" !in blankNextKeyItems)

        val listText = """
            ---
            id: Person
            kind: NodeType
            extends:
              - En
            ---
        """.trimIndent()
        val listItems = FrontMatterCompletionResolver(
            text = listText,
            offset = listText.indexOf("En") + 2,
            parsedDocument = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md"),
            nodeTypeIds = listOf("Entity"),
            relTypeIds = emptyList(),
            timelineIds = emptyList(),
        ).resolve()?.map { it.label }.orEmpty()
        assertEquals(listOf("Entity"), listItems)
    }
}
