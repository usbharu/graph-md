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
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
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
    fun `server advertises quick fix code actions`() {
        val capabilities = GraphMdLanguageServer().initialize(InitializeParams()).get().capabilities

        assertNotNull(capabilities.codeActionProvider)
        assertTrue(CodeActionKind.QuickFix in capabilities.codeActionProvider.right.codeActionKinds)
    }

    @Test
    fun `quick fixes replace or create unknown references`() {
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/alice.md" to "---\nid: alice\nkind: Node\ntype: Persno\n---",
            ),
        )
        val actions = fixture.actions("file:///workspace/alice.md", "Unknown NodeType: Persno")

        assertTrue(actions.any { it.title == "Change NodeType to 'Person'" })
        assertTrue(actions.any { it.title == "Create NodeType 'Persno'" })
        val replacement = actions.first { it.title == "Change NodeType to 'Person'" }
        assertEquals("Person", replacement.edit.changes.getValue("file:///workspace/alice.md").single().newText)
        assertTrue(replacement.isPreferred)
    }

    @Test
    fun `quick fixes repair front matter fields and invalid enums`() {
        val missingIdUri = "file:///workspace/missing-id.md"
        val invalidKindUri = "file:///workspace/invalid-kind.md"
        val unknownFieldUri = "file:///workspace/unknown-field.md"
        val fixture = serverFixture(
            mapOf(
                missingIdUri to "---\nkind: NodeType\n---",
                invalidKindUri to "---\nid: invalid\nkind: Ndoe\n---",
                unknownFieldUri to "---\nid: Example\nkind: NodeType\nbogus: true\n---",
            ),
        )

        val addId = fixture.actions(missingIdUri, "id is required").first { it.title.startsWith("Add 'id:") }
        assertTrue(addId.edit.changes.getValue(missingIdUri).single().newText.contains("id: missing-id"))
        val kindActions = fixture.actions(invalidKindUri, "Unknown document kind: Ndoe")
        assertTrue(kindActions.any { it.title == "Change kind to 'Node'" })
        val removeField = fixture.actions(unknownFieldUri, "Unknown top-level field: bogus").single()
        assertEquals("Remove unknown field 'bogus'", removeField.title)
        assertEquals("", removeField.edit.changes.getValue(unknownFieldUri).single().newText)
    }

    @Test
    fun `quick fixes add required props and declare unknown props in schemas`() {
        val typeUri = "file:///workspace/types/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                typeUri to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      name:
                        type: string
                        required: true
                    ---
                """.trimIndent(),
                nodeUri to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    props:
                      nickname: Ally
                    ---
                """.trimIndent(),
            ),
        )

        val required = fixture.actions(nodeUri, "Required property missing after normalization: name")
            .first { it.title == "Add required property 'name'" }
        assertTrue(required.edit.changes.getValue(nodeUri).single().newText.contains("name: \"\""))

        val declaration = fixture.actions(nodeUri, "Unknown property nickname on Node alice")
            .first { it.title == "Declare 'nickname' in Person" }
        assertTrue(declaration.edit.changes.getValue(typeUri).single().newText.contains("nickname:"))
    }

    @Test
    fun `quick fixes close incomplete inline syntax`() {
        val uri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(uri to "---\nid: alice\nkind: Node\ntype: Person\n---\n@props{name = \"Alice\""),
        )

        val close = fixture.actions(uri, "Unclosed @props block").first { it.title == "Close @props block" }
        assertEquals("}", close.edit.changes.getValue(uri).single().newText)
    }

    @Test
    fun `quick fixes normalize inline arguments and relation layout`() {
        val argumentsUri = "file:///workspace/arguments.md"
        val whitespaceUri = "file:///workspace/whitespace.md"
        val relationUri = "file:///workspace/relation.md"
        val header = "---\nid: alice\nkind: Node\ntype: Person\n---\n"
        val fixture = serverFixture(
            mapOf(
                argumentsUri to header + "@props(foo=bar){name=Alice}",
                whitespaceUri to header + "@link [Bob](bob friendOf)",
                relationUri to header + "@link{}[Bob]",
            ),
        )

        val args = fixture.actions(argumentsUri, "@props only accepts validTime=...").single()
        assertTrue(args.edit.changes.getValue(argumentsUri).single().newText.startsWith("validTime="))
        val whitespace = fixture.actions(whitespaceUri, "@link must be followed immediately by a link").single()
        assertEquals("", whitespace.edit.changes.getValue(whitespaceUri).single().newText)
        val relation = fixture.actions(relationUri, "Relation must be followed by (...)").single()
        assertTrue(relation.edit.changes.getValue(relationUri).single().newText.startsWith("("))
    }

    @Test
    fun `quick fixes repair yaml value types and duplicate ids`() {
        val invalidTypeUri = "file:///workspace/types/Broken.md"
        val duplicateUri = "file:///workspace/bob.md"
        val fixture = serverFixture(
            mapOf(
                invalidTypeUri to "---\nid: Broken\nkind: NodeType\nprops:\n  name:\n    type: string\n    required: nope\n---",
                "file:///workspace/alice.md" to "---\nid: duplicate\nkind: Node\ntype: Broken\n---",
                duplicateUri to "---\nid: duplicate\nkind: Node\ntype: Broken\n---",
            ),
        )

        val boolean = fixture.actions(invalidTypeUri, "required MUST be a boolean").single()
        assertEquals("false", boolean.edit.changes.getValue(invalidTypeUri).single().newText)
        val duplicate = fixture.actions(duplicateUri, "Node id must be unique: duplicate").single()
        assertEquals("duplicate2", duplicate.edit.changes.getValue(duplicateUri).single().newText)
    }

    @Test
    fun `quick fixes repair temporal bounds and relation constraints`() {
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/types/Company.md" to "---\nid: Company\nkind: NodeType\n---",
                "file:///workspace/types/knows.md" to "---\nid: knows\nkind: RelType\nfrom: [Person]\nto: [Person]\n---",
                "file:///workspace/types/worksAt.md" to "---\nid: worksAt\nkind: RelType\nfrom: [Person]\nto: [Company]\n---",
                "file:///workspace/timelines/CommonEra.md" to "---\nid: CommonEra\nkind: Timeline\ntimecode:\n  type: number\n---",
                "file:///workspace/acme.md" to "---\nid: acme\nkind: Node\ntype: Company\n---",
                nodeUri to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: CommonEra
                        from:
                          timecode: 10
                        to:
                          timecode: 1
                    ---
                    @link[Acme](acme knows)
                """.trimIndent(),
            ),
        )

        val swap = fixture.actions(nodeUri, "validTime.from is after validTime.to on CommonEra")
            .first { it.title == "Swap validTime from/to" }
        assertEquals(setOf("1", "10"), swap.edit.changes.getValue(nodeUri).map { it.newText }.toSet())
        val relation = fixture.actions(nodeUri, "Relation target type Company is not allowed for knows")
        assertTrue(relation.any { it.title == "Change relation type to 'worksAt'" })
    }

    @Test
    fun `representative workspace exposes a quick fix for every published diagnostic`() {
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      count:
                        type: number
                        required: true
                        bogus: value
                      period:
                        type: duration
                    ---
                """.trimIndent(),
                "file:///workspace/timelines/CommonEra.md" to "---\nid: CommonEra\nkind: Timeline\ntimecode:\n  type: number\n---",
                "file:///workspace/timelines/Broken.md" to """
                    ---
                    id: Broken
                    kind: Timeline
                    mappings:
                      - kind: offset
                        from: CommonEra
                        to: CommonEra
                        offset: nope
                    ---
                """.trimIndent(),
                "file:///workspace/alice.md" to """
                    ---
                    id: duplicate
                    kind: Node
                    type: Person
                    strange: true
                    validTime:
                      - timeline: CommonEra
                        from:
                          timecode: 5
                        to:
                          timecode: 1
                    props:
                      count: nope
                      period: {}
                      extra: value
                    ---
                    @props(foo=bar){count=nope}
                """.trimIndent(),
                "file:///workspace/bob.md" to "---\nid: duplicate\nkind: Node\ntype: MissingType\n---\n@link{}[Nobody](missing missingRel)",
                "file:///workspace/invalid-kind.md" to "---\nid: invalid\nkind: Ndoe\n---",
            ),
        )

        val allDiagnostics = fixture.diagnostics.flatMap { (uri, diagnostics) -> diagnostics.map { uri to it } }
        assertTrue(allDiagnostics.isNotEmpty())
        val missing = allDiagnostics.filter { (uri, diagnostic) -> fixture.actions(uri, diagnostic).isEmpty() }
        assertTrue(missing.isEmpty(), missing.joinToString { (uri, diagnostic) -> "$uri: ${diagnostic.message}" })
    }

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
    fun `property-less relation and timeline ids are discovered`() {
        val analysis = analyzer.analyze(
            """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: CommonEra
            ---
            @props{createdAt={timeline=CommonEra,timecode=1}}
            @link[Bob](bob friendOf)
            """.trimIndent(),
            "/workspace/alice.md",
        )

        assertTrue(analysis.references.any { it.kind == ReferenceTargetKind.Node && it.targetId == "bob" })
        assertTrue(analysis.references.any { it.kind == ReferenceTargetKind.RelType && it.targetId == "friendOf" })
        assertEquals(2, analysis.references.count { it.kind == ReferenceTargetKind.Timeline && it.targetId == "CommonEra" })
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
    fun `inline props completion follows value types annotations and timeline constraints`() {
        val schema = mapOf(
            "name" to ResolvedPropSchema(type = PropType.string),
            "timeline" to ResolvedPropSchema(type = PropType.string),
            "score" to ResolvedPropSchema(type = PropType.number),
            "labels" to ResolvedPropSchema(type = PropType.array, items = ResolvedPropSchema(type = PropType.string)),
            "description" to ResolvedPropSchema(type = PropType.text),
            "activeDuring" to ResolvedPropSchema(type = PropType.duration, timeline = TimelineSelector.Id("ThirdAge")),
        )
        fun resolve(body: String) = PropsCompletionContextResolver(
            text = "@props{$body",
            offset = "@props{$body".length,
            rootSchema = schema,
            timelineIds = listOf("CommonEra", "ThirdAge"),
        ).resolve()?.items.orEmpty()

        val string = resolve("name = ").single { it.label == "string" }
        assertEquals("\"\${1:value}\"", string.insertText)
        assertEquals(InsertTextFormat.Snippet, string.insertTextFormat)
        assertEquals(listOf("string"), resolve("timeline = ").map { it.label })
        assertEquals(listOf("0"), resolve("score = ").map { it.label })
        assertEquals("[ \"\${1:value}\" ]", resolve("labels = ").single().insertText)
        assertTrue(resolve("description = ").map { it.label }.containsAll(listOf("text", "localized text")))

        val timelineLabels = resolve("activeDuring = { timeline = ").map { it.label }
        assertEquals(listOf("ThirdAge"), timelineLabels)
        assertTrue("key" in resolve("description(").map { it.label })
        assertEquals(listOf("ThirdAge"), resolve("activeDuring(validTime=Th").map { it.label })
        assertEquals(listOf("from", "to"), resolve("activeDuring(validTime=ThirdAge(").map { it.label })
        assertEquals(listOf("0"), resolve("activeDuring(validTime=ThirdAge(from=").map { it.label })
    }

    @Test
    fun `front matter completion infers unfinished document kind and omits existing keys`() {
        val text = """
            ---
            id: alice
            kind: Node
            type: Person

            ---
        """.trimIndent()
        val items = FrontMatterCompletionResolver(
            text = text,
            offset = text.indexOf("\n\n") + 1,
            parsedDocument = null,
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = emptyList(),
        ).resolve()?.map { it.label }.orEmpty()

        assertTrue("props" in items)
        assertTrue("validTime" in items)
        assertTrue("from" !in items)
        assertTrue("id" !in items)
        assertTrue("kind" !in items)
        assertTrue("type" !in items)
    }

    @Test
    fun `relation completion filters targets and relation types by endpoint types`() {
        val server = GraphMdLanguageServer()
        server.initialize(InitializeParams()).get()
        val documents = mapOf(
            "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
            "file:///workspace/types/Company.md" to "---\nid: Company\nkind: NodeType\n---",
            "file:///workspace/types/worksAt.md" to "---\nid: worksAt\nkind: RelType\nfrom: [Person]\nto: [Company]\n---",
            "file:///workspace/types/knows.md" to "---\nid: knows\nkind: RelType\nfrom: [Person]\nto: [Person]\n---",
            "file:///workspace/bob.md" to "---\nid: bob\nkind: Node\ntype: Person\n---",
            "file:///workspace/acme.md" to "---\nid: acme\nkind: Node\ntype: Company\n---",
        )
        documents.forEach { (uri, text) ->
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "markdown", 1, text)))
        }

        val targetText = "---\nid: alice\nkind: Node\ntype: Person\n---\n@link[Company](a worksAt)"
        val targetUri = "file:///workspace/alice.md"
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(targetUri, "markdown", 1, targetText)))
        val targetItems = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(targetUri), Position(5, targetText.substringAfterLast('\n').indexOf("(a") + 2)),
        ).get().left.map { it.label }
        assertTrue("acme" in targetItems, targetItems.joinToString())
        assertTrue("bob" !in targetItems, targetItems.joinToString())

        val relationText = targetText.replace("a worksAt", "acme ")
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(targetUri, "markdown", 2, relationText)))
        val relationItems = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(targetUri), Position(5, relationText.substringAfterLast('\n').indexOf("acme ") + 5)),
        ).get().left.map { it.label }
        assertEquals(listOf("worksAt"), relationItems)
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
        assertTrue("timeline" in intervalKeyItems)
        assertTrue("from" in intervalKeyItems)
        assertTrue("to" in intervalKeyItems)
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

    private fun serverFixture(documents: Map<String, String>): ServerFixture {
        val published = mutableMapOf<String, List<org.eclipse.lsp4j.Diagnostic>>()
        val server = GraphMdLanguageServer()
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
        documents.forEach { (uri, text) ->
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "markdown", 1, text)),
            )
        }
        return ServerFixture(server, published)
    }

    private data class ServerFixture(
        val server: GraphMdLanguageServer,
        val diagnostics: Map<String, List<org.eclipse.lsp4j.Diagnostic>>,
    ) {
        fun actions(uri: String, message: String): List<CodeAction> {
            val diagnostic = diagnostics.getValue(uri).first { it.message == message }
            return actions(uri, diagnostic)
        }

        fun actions(uri: String, diagnostic: org.eclipse.lsp4j.Diagnostic): List<CodeAction> {
            return server.textDocumentService.codeAction(
                CodeActionParams(
                    TextDocumentIdentifier(uri),
                    diagnostic.range ?: Range(Position(0, 0), Position(0, 0)),
                    CodeActionContext(listOf(diagnostic)),
                ),
            ).get().mapNotNull { it.right }
        }
    }
}
