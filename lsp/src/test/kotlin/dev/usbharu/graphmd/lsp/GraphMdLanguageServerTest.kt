package dev.usbharu.graphmd.lsp

import dev.usbharu.graphmd.core.GraphDocumentAnalyzer
import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.ReferenceTargetKind
import dev.usbharu.graphmd.core.model.DocumentKind
import dev.usbharu.graphmd.core.model.NodeDocument
import dev.usbharu.graphmd.core.model.NodeTypeDocument
import dev.usbharu.graphmd.core.model.PropType
import dev.usbharu.graphmd.core.model.ResolvedPropSchema
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.core.model.TimelineSelector
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `server registers GraphMD search requests`() {
        val methods = ServiceEndpoints.getSupportedMethods(GraphMdLanguageServer::class.java)

        assertTrue("graphmd/search" in methods)
        assertTrue("graphmd/searchMetadata" in methods)
    }

    @Test
    fun `search exposes metadata parameters results and document locations`() {
        val root = Files.createTempDirectory("graphmd-search")
        try {
            val type = root.resolve("Person.md")
            val relationType = root.resolve("friendOf.md")
            val node = root.resolve("alice.md")
            val targetNode = root.resolve("bob.md")
            Files.writeString(
                type,
                """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      age:
                        type: number
                    ---
                """.trimIndent(),
            )
            Files.writeString(
                relationType,
                """
                    ---
                    id: friendOf
                    kind: RelType
                    from: [Person]
                    to: [Person]
                    props:
                      since:
                        type: number
                    ---
                """.trimIndent(),
            )
            val relationText = "@link{since = 2021}[Bob](bob friendOf)"
            val nodeText = """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    props:
                      age: 21
                    ---
                    Alice is a brave adventurer.
                    $relationText
                """.trimIndent().replace("\n", "\r\n")
            Files.writeString(node, nodeText)
            Files.writeString(
                targetNode,
                """
                    ---
                    id: bob
                    kind: Node
                    type: Person
                    props:
                      age: 22
                    ---
                    Bob is Alice's friend.
                """.trimIndent(),
            )
            val server = GraphMdLanguageServer()
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "search"))
                },
            ).get()

            val metadata = server.searchMetadata().get()
            val person = metadata.nodeTypes.single { it.id == "Person" }
            assertEquals("number", person.properties.single { it.name == "age" }.type)
            val friendOf = metadata.relationTypes.single { it.id == "friendOf" }
            assertEquals(listOf("Person"), friendOf.sourceTypes)
            assertEquals(listOf("Person"), friendOf.targetTypes)
            assertEquals("number", friendOf.properties.single { it.name == "since" }.type)

            val result = server.search(
                GraphMdSearchParams(
                    """
                        MATCH (node:Person)
                        WHERE FULLTEXT(node, ${'$'}keyword)
                          AND node.age >= ${'$'}minimum
                        VALID ANYTIME
                        RETURN ID(node) AS id, TYPE(node) AS type, SCORE() AS score, VALIDITY() AS validity
                        ORDER BY score DESC, id ASC
                        LIMIT 100
                    """.trimIndent(),
                    mapOf("keyword" to "\"brave\"", "minimum" to "18"),
                ),
            ).get()

            assertTrue(result.diagnostics.isEmpty())
            assertEquals(listOf("id", "type", "score", "validity"), result.columns.map { it.name })
            assertEquals("alice", result.rows.single().values.first())
            assertEquals(node.toUri().toString(), result.rows.single().location?.uri)

            val links = server.search(
                GraphMdSearchParams(
                    """
                        MATCH (source:Person)-[link:friendOf]->(target:Person)
                        WHERE ID(source) = ${'$'}sourceId
                          AND ID(target) = ${'$'}targetId
                          AND link.since >= ${'$'}since
                          AND FULLTEXT(link, ${'$'}keyword)
                        VALID ANYTIME
                        RETURN ID(link) AS id, TYPE(link) AS type, ID(source) AS source, ID(target) AS target, SCORE() AS score, VALIDITY() AS validity
                        ORDER BY score DESC, id ASC
                    """.trimIndent(),
                    mapOf(
                        "sourceId" to "\"alice\"",
                        "targetId" to "\"bob\"",
                        "since" to "2020",
                        "keyword" to "\"Bob\"",
                    ),
                ),
            ).get()

            assertTrue(links.diagnostics.isEmpty())
            assertEquals(listOf("id", "type", "source", "target", "score", "validity"), links.columns.map { it.name })
            assertEquals("alice", links.rows.single().values[2])
            assertEquals("bob", links.rows.single().values[3])
            assertEquals(node.toUri().toString(), links.rows.single().location?.uri)
            assertEquals(
                Range(Position(8, 0), Position(8, relationText.length)),
                links.rows.single().location?.range,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `search reports query errors and observes unsaved document changes`() {
        val root = Files.createTempDirectory("graphmd-search-change")
        try {
            val node = root.resolve("alice.md")
            val original = "---\nid: alice\nkind: Node\ntype: Person\n---\nOriginal body"
            Files.writeString(root.resolve("Person.md"), "---\nid: Person\nkind: NodeType\n---")
            Files.writeString(node, original)
            val server = GraphMdLanguageServer()
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "search"))
                },
            ).get()
            val invalid = server.search(GraphMdSearchParams("MATCH broken")).get()
            assertTrue(invalid.diagnostics.any { it.code.startsWith("GMQL") })

            val uri = node.toUri().toString()
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "markdown", 1, original)),
            )
            server.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, 2),
                    listOf(TextDocumentContentChangeEvent(original.replace("Original", "Updated"))),
                ),
            )
            val updated = server.search(
                GraphMdSearchParams(
                    """MATCH (node) WHERE FULLTEXT(node, "Updated") RETURN ID(node) AS id""",
                ),
            ).get()

            assertEquals("alice", updated.rows.single().values.single())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `document change while search compiles cannot populate stale caches`() {
        val compilationStarted = CountDownLatch(1)
        val releaseCompilation = CountDownLatch(1)
        val compilationCount = AtomicInteger()
        val compiler = GraphCompiler()
        val index = GraphMdWorkspaceIndex { sources ->
            if (compilationCount.incrementAndGet() == 1) {
                compilationStarted.countDown()
                check(releaseCompilation.await(5, TimeUnit.SECONDS))
            }
            compiler.compileSources(sources)
        }
        val typeUri = "file:///workspace/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        index.upsert(typeUri, "---\nid: Person\nkind: NodeType\n---")
        index.upsert(nodeUri, "---\nid: alice\nkind: Node\ntype: Person\n---\nOriginal body")

        val concurrentSearch = CompletableFuture.supplyAsync {
            index.search(
                GraphMdSearchParams(
                    """MATCH (node) WHERE FULLTEXT(node, "Original") RETURN ID(node) AS id""",
                ),
            )
        }
        try {
            assertTrue(compilationStarted.await(5, TimeUnit.SECONDS))
            index.upsert(nodeUri, "---\nid: alice\nkind: Node\ntype: Person\n---\nUpdated body")
        } finally {
            releaseCompilation.countDown()
        }
        val concurrentResult = concurrentSearch.get(5, TimeUnit.SECONDS)

        val updated = index.search(
            GraphMdSearchParams(
                """MATCH (node) WHERE FULLTEXT(node, "Updated") RETURN ID(node) AS id""",
            ),
        )
        val original = index.search(
            GraphMdSearchParams(
                """MATCH (node) WHERE FULLTEXT(node, "Original") RETURN ID(node) AS id""",
            ),
        )

        assertTrue(concurrentResult.rows.isEmpty())
        assertEquals("alice", updated.rows.single().values.single())
        assertTrue(original.rows.isEmpty())
        assertTrue(compilationCount.get() >= 2)
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
    fun `required property diagnostics highlight props unless inline props satisfy the schema`() {
        val typeUri = "file:///workspace/types/Person.md"
        val missingUri = "file:///workspace/missing.md"
        val inlineUri = "file:///workspace/inline.md"
        val bodyPropsUri = "file:///workspace/body-props.md"
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
                missingUri to """
                    ---
                    id: missing
                    kind: Node
                    type: Person
                    props:
                      nickname: Missing name
                    ---
                """.trimIndent(),
                inlineUri to """
                    ---
                    id: inline
                    kind: Node
                    type: Person
                    props:
                      nickname: Bound in body
                    ---
                    @props{name = "Inline name"}
                """.trimIndent(),
                bodyPropsUri to """
                    ---
                    id: body-props
                    kind: Node
                    type: Person
                    ---
                    props:
                """.trimIndent(),
            ),
        )

        val missing = fixture.diagnostics.getValue(missingUri)
            .single { it.message == "Required property missing after normalization: name" }
        assertEquals(Range(Position(4, 0), Position(4, 5)), missing.range)
        assertTrue(
            fixture.diagnostics.getValue(inlineUri)
                .none { it.message == "Required property missing after normalization: name" },
        )
        val bodyProps = fixture.diagnostics.getValue(bodyPropsUri)
            .single { it.message == "Required property missing after normalization: name" }
        assertEquals(Range(Position(4, 0), Position(4, 0)), bodyProps.range)
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
    fun `duplicate inline props diagnostic points to the second key`() {
        val uri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                uri to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    props:
                      name: Front matter value
                    ---
                    @props{
                      name = "Alice",
                      name = "Bob"
                    }
                """.trimIndent(),
            ),
        )

        val diagnostic = fixture.diagnostics.getValue(uri)
            .single { it.message.startsWith("Duplicate key: name at index") }

        assertEquals(Range(Position(9, 2), Position(9, 6)), diagnostic.range)
    }

    @Test
    fun `duplicate required inline prop reports only the duplicate at the second key`() {
        val typeUri = "file:///workspace/types/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                typeUri to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      age:
                        type: number
                        required: true
                    ---
                """.trimIndent(),
                nodeUri to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    ---
                    @props{age = 14,age = 14}
                """.trimIndent(),
            ),
        )

        val diagnostics = fixture.diagnostics.getValue(nodeUri)
        val duplicate = diagnostics.single { it.message.startsWith("Duplicate key: age at index") }

        assertTrue(diagnostics.none { it.message == "Required property missing after normalization: age" })
        assertEquals(Range(Position(5, 16), Position(5, 19)), duplicate.range)
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
    fun `Media definitions retain their kind across hover navigation references rename and completion`() {
        val mediaUri = "file:///workspace/portrait.md"
        val sourceUri = "file:///workspace/alice.md"
        val nodeUri = "file:///workspace/bob.md"
        val mediaText = """
            ---
            id: portrait
            kind: Media
            type: Image
            url: https://example.com/portrait.png
            ---
        """.trimIndent()
        val sourceText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link{}[Portrait](portrait shows)
        """.trimIndent()
        val nodeText = """
            ---
            id: bob
            kind: Node
            type: Person
            ---
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(mediaUri to mediaText, sourceUri to sourceText, nodeUri to nodeText),
        )
        val mediaId = mediaText.indexOf("portrait")
        val relationTarget = sourceText.lastIndexOf("portrait")

        assertEquals(mediaUri, fixture.definitions(sourceUri, relationTarget + 1).single().uri)
        assertEquals(mediaUri, fixture.definitions(mediaUri, mediaId + 1).single().uri)
        assertTrue(fixture.hover(mediaUri, mediaId + 1).startsWith("**Media** `portrait`"))
        assertTrue(fixture.hover(sourceUri, relationTarget + 1).startsWith("**Media** `portrait`"))
        assertTrue(fixture.hover(nodeUri, nodeText.indexOf("bob") + 1).startsWith("**Node** `bob`"))

        val references = fixture.references(mediaUri, mediaId + 1)
        assertEquals(setOf(mediaUri, sourceUri), references.map { it.uri }.toSet())
        val rename = fixture.rename(mediaUri, mediaId + 1, "newPortrait")
        assertEquals(setOf(mediaUri, sourceUri), rename.changes.keys)
        assertEquals(2, rename.changes.values.sumOf { it.size })

        assertTrue("portrait" in fixture.completions(sourceUri, relationTarget + 2).map { it.label })
    }

    @Test
    fun `Node and Media with the same id remain ambiguous in their shared namespace`() {
        val nodeUri = "file:///workspace/shared-node.md"
        val mediaUri = "file:///workspace/shared-media.md"
        val sourceUri = "file:///workspace/source.md"
        val sourceText = """
            ---
            id: source
            kind: Node
            type: Person
            ---
            @link{}[Shared](shared relatesTo)
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                nodeUri to "---\nid: shared\nkind: Node\ntype: Person\n---",
                mediaUri to "---\nid: shared\nkind: Media\ntype: Image\nurl: https://example.com/shared.png\n---",
                sourceUri to sourceText,
            ),
        )
        val targetOffset = sourceText.lastIndexOf("shared") + 1

        assertEquals(setOf(nodeUri, mediaUri), fixture.definitions(sourceUri, targetOffset).map { it.uri }.toSet())
        val hover = fixture.hover(sourceUri, targetOffset)
        assertTrue(hover.startsWith("**Node or Media** `shared`"))
        assertTrue("Ambiguous: 2 definitions" in hover)
        assertTrue(fixture.diagnostics.getValue(nodeUri).any { it.message == "Node id must be unique: shared" })
        assertTrue(fixture.diagnostics.getValue(mediaUri).any { it.message == "Node id must be unique: shared" })
        assertEquals(
            setOf(nodeUri, mediaUri, sourceUri),
            fixture.rename(sourceUri, targetOffset, "renamed").changes.keys,
        )
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
    fun `definition resolves yaml inline and relation property keys`() {
        val nodeTypeUri = "file:///workspace/types/Person.md"
        val relTypeUri = "file:///workspace/types/friendOf.md"
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              name: Alice
            ---
            @props{name = "Alice"}
            @link{weight = 0.5}[Bob](bob friendOf)
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                nodeTypeUri to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      name:
                        type: string
                    ---
                """.trimIndent(),
                relTypeUri to """
                    ---
                    id: friendOf
                    kind: RelType
                    props:
                      weight:
                        type: number
                    ---
                """.trimIndent(),
                nodeUri to nodeText,
            ),
        )

        val yamlDefinition = fixture.definitions(nodeUri, nodeText.indexOf("name:") + 1).single()
        val inlineDefinition = fixture.definitions(nodeUri, nodeText.indexOf("name =") + 1).single()
        val relationDefinition = fixture.definitions(nodeUri, nodeText.indexOf("weight") + 1).single()

        assertEquals(nodeTypeUri, yamlDefinition.uri)
        assertEquals(Position(4, 2), yamlDefinition.range.start)
        assertEquals(yamlDefinition, inlineDefinition)
        assertEquals(
            yamlDefinition,
            fixture.definitions(nodeTypeUri, fixture.documents.getValue(nodeTypeUri).indexOf("name:") + 1).single(),
        )
        assertEquals(relTypeUri, relationDefinition.uri)
        assertEquals(Position(4, 2), relationDefinition.range.start)
    }

    @Test
    fun `property definition follows inheritance and prefers refinements`() {
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Employee
            props:
              name: Alice
              age: 20
              unknown: value
            ---
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Entity.md" to """
                    ---
                    id: Entity
                    kind: NodeType
                    props:
                      name:
                        type: string
                      age:
                        type: number
                    ---
                """.trimIndent(),
                "file:///workspace/types/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    extends: [Entity]
                    props:
                      age:
                        type: number
                    ---
                """.trimIndent(),
                "file:///workspace/types/Employee.md" to """
                    ---
                    id: Employee
                    kind: NodeType
                    extends: [Person]
                    ---
                """.trimIndent(),
                nodeUri to nodeText,
            ),
        )

        assertEquals(
            "file:///workspace/types/Entity.md",
            fixture.definitions(nodeUri, nodeText.indexOf("name:") + 1).single().uri,
        )
        assertEquals(
            "file:///workspace/types/Person.md",
            fixture.definitions(nodeUri, nodeText.indexOf("age:") + 1).single().uri,
        )
        assertTrue(fixture.definitions(nodeUri, nodeText.indexOf("unknown:") + 1).isEmpty())
    }

    @Test
    fun `property definition returns declarations from multiple inheritance branches`() {
        val nodeUri = "file:///workspace/item.md"
        val nodeText = "---\nid: item\nkind: Node\ntype: Combined\nprops:\n  label: value\n---"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Left.md" to "---\nid: Left\nkind: NodeType\nprops:\n  label:\n    type: string\n---",
                "file:///workspace/types/Right.md" to "---\nid: Right\nkind: NodeType\nprops:\n  label:\n    type: string\n---",
                "file:///workspace/types/Combined.md" to "---\nid: Combined\nkind: NodeType\nextends: [Left, Right]\n---",
                nodeUri to nodeText,
            ),
        )

        assertEquals(
            setOf("file:///workspace/types/Left.md", "file:///workspace/types/Right.md"),
            fixture.definitions(nodeUri, nodeText.indexOf("label") + 1).map { it.uri }.toSet(),
        )
    }

    @Test
    fun `definition resolves unicode and non identifier yaml property keys`() {
        val nodeTypeUri = "file:///workspace/types/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              名前: Alice
              1st value: 1
            ---
            @props{名前 = "Alice"}
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                nodeTypeUri to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      名前:
                        type: string
                      1st value:
                        type: number
                    ---
                """.trimIndent(),
                nodeUri to nodeText,
            ),
        )

        val unicodeDefinitions = listOf(
            nodeText.indexOf("名前:") + 1,
            nodeText.indexOf("名前 =") + 1,
        ).map { fixture.definitions(nodeUri, it).single() }
        val numericDefinition = fixture.definitions(nodeUri, nodeText.indexOf("1st value") + 1).single()

        assertTrue(unicodeDefinitions.all { it.uri == nodeTypeUri && it.range.start == Position(4, 2) })
        assertEquals(nodeTypeUri, numericDefinition.uri)
        assertEquals(Position(6, 2), numericDefinition.range.start)
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
            "birthDate" to ResolvedPropSchema(type = PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
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
    fun `invalid id warning highlights only the id value`() {
        val uri = "file:///workspace/invalid-id.md"
        val escapedUri = "file:///workspace/escaped-invalid-id.md"
        val hashUri = "file:///workspace/hash-invalid-id.md"
        val fixture = serverFixture(
            mapOf(
                uri to """
                    ---
                    id: "bad/id"
                    kind: NodeType
                    ---
                """.trimIndent(),
                escapedUri to """
                    ---
                    id: "bad\/id"
                    kind: NodeType
                    ---
                """.trimIndent(),
                hashUri to """
                    ---
                    id: "bad#id"
                    kind: NodeType
                    ---
                """.trimIndent(),
            ),
        )

        val diagnostic = fixture.diagnostics.getValue(uri).single {
            it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*"
        }
        assertEquals(DiagnosticSeverity.Warning, diagnostic.severity)
        assertEquals(Position(1, 5), diagnostic.range.start)
        assertEquals(Position(1, 11), diagnostic.range.end)

        val escapedDiagnostic = fixture.diagnostics.getValue(escapedUri).single {
            it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*"
        }
        assertEquals(DiagnosticSeverity.Warning, escapedDiagnostic.severity)
        assertEquals(Position(1, 5), escapedDiagnostic.range.start)
        assertEquals(Position(1, 12), escapedDiagnostic.range.end)

        val hashDiagnostic = fixture.diagnostics.getValue(hashUri).single {
            it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*"
        }
        assertEquals(DiagnosticSeverity.Warning, hashDiagnostic.severity)
        assertEquals(Position(1, 5), hashDiagnostic.range.start)
        assertEquals(Position(1, 11), hashDiagnostic.range.end)
    }

    @Test
    fun `initial diagnostics are published after initialized notification`() {
        val root = Files.createTempDirectory("graphmd-lsp-initialized")
        try {
            val file = root.resolve("timeline.md")
            val uri = file.toUri().toString()
            Files.writeString(file, graphDocument("INVALID ID@", "Timeline"))
            val client = RecordingLanguageClient()
            val server = GraphMdLanguageServer()
            server.connect(client)

            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
                },
            ).get()

            assertTrue(client.notifications.isEmpty())
            server.initialized(InitializedParams())
            assertTrue(client.latest(uri).any { it.message == invalidIdWarning })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `watched file events do not replace open document diagnostics`() {
        val root = Files.createTempDirectory("graphmd-lsp-open-documents")
        try {
            val documents = listOf(
                root.resolve("timeline.md") to "Timeline",
                root.resolve("node-type.md") to "NodeType",
            )
            documents.forEach { (file, kind) ->
                Files.writeString(file, graphDocument(file.fileName.toString().substringBefore('.'), kind))
            }

            val client = RecordingLanguageClient()
            val server = GraphMdLanguageServer()
            server.connect(client)
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
                },
            ).get()
            server.initialized(InitializedParams())

            documents.forEachIndexed { index, (file, kind) ->
                val uri = file.toUri().toString()
                val diskText = Files.readString(file)
                val invalidText = graphDocument("INVALID ID@", kind)
                server.textDocumentService.didOpen(
                    DidOpenTextDocumentParams(TextDocumentItem(uri, "markdown", index + 1, diskText)),
                )
                server.textDocumentService.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri, index + 2),
                        listOf(TextDocumentContentChangeEvent(invalidText)),
                    ),
                )
                assertTrue(client.latest(uri).any { it.message == invalidIdWarning })

                listOf(FileChangeType.Created, FileChangeType.Changed, FileChangeType.Deleted).forEach { changeType ->
                    server.workspaceService.didChangeWatchedFiles(
                        DidChangeWatchedFilesParams(listOf(FileEvent(uri, changeType))),
                    )
                    assertTrue(client.latest(uri).any { it.message == invalidIdWarning })
                }

                server.textDocumentService.didClose(
                    DidCloseTextDocumentParams(TextDocumentIdentifier(uri)),
                )
                assertTrue(client.latest(uri).none { it.message == invalidIdWarning })

                Files.writeString(file, invalidText)
                server.workspaceService.didChangeWatchedFiles(
                    DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Changed))),
                )
                assertTrue(client.latest(uri).any { it.message == invalidIdWarning })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `encoded client uri does not duplicate diagnostics for the same file`() {
        val root = Files.createTempDirectory("graphmd-lsp-encoded-uri")
        try {
            val file = root.resolve("THE IDOLM@STER2.md")
            val diskUri = file.toUri().toString()
            val clientUri = diskUri.replace("@", "%40")
            Files.writeString(file, graphDocument("THE_IDOLMASTER2", "Timeline"))
            assertTrue(clientUri != diskUri)

            val client = RecordingLanguageClient()
            val server = GraphMdLanguageServer()
            server.connect(client)
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
                },
            ).get()
            server.initialized(InitializedParams())
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(clientUri, "markdown", 1, Files.readString(file)),
                ),
            )

            client.notifications.clear()
            server.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(clientUri, 2),
                    listOf(TextDocumentContentChangeEvent(graphDocument("THE_IDOLM@STER2", "Timeline"))),
                ),
            )

            val notifications = client.notifications.filter {
                Path.of(URI.create(it.uri)) == file
            }
            assertEquals(1, notifications.size)
            assertEquals(diskUri, notifications.single().uri)
            assertTrue(notifications.single().diagnostics.any { it.message == invalidIdWarning })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `front matter completion suggests node props keys and timeline values`() {
        val schema = mapOf(
            "name" to ResolvedPropSchema(type = PropType.string),
            "birthDate" to ResolvedPropSchema(type = PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
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
            "activeDuring" to ResolvedPropSchema(type = PropType.duration, timeline = TimelineSelector.Id("CommonEra")),
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
    fun `validTime list completion produces natural yaml and timeline values`() {
        fun completionFor(line: String, timelineIds: List<String> = listOf("CommonEra", "ThirdAge")): List<CompletionEntry> {
            val text = "---\nid: alice\nkind: Node\ntype: Person\nvalidTime:\n$line\n---"
            return FrontMatterCompletionResolver(
                text = text,
                offset = text.indexOf(line) + line.length,
                parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
                nodeTypeIds = listOf("Person"),
                relTypeIds = emptyList(),
                timelineIds = timelineIds,
            ).resolve().orEmpty()
        }

        val blank = completionFor("  ").single { it.label == "timeline" }
        assertEquals("- timeline: ", blank.insertText)
        assertEquals("  - timeline: ", "  " + blank.insertText)

        val dash = completionFor("  -").single { it.label == "timeline" }
        assertEquals(" timeline: ", dash.insertText)
        assertEquals("  - timeline: ", "  -" + dash.insertText)

        val spacedDash = completionFor("  - ").single { it.label == "timeline" }
        assertEquals("timeline: ", spacedDash.insertText)
        assertEquals("  - timeline: ", "  - " + spacedDash.insertText)

        val prefixedDash = completionFor("  -tim").single { it.label == "timeline" }
        assertEquals(" timeline: ", prefixedDash.insertText)
        assertEquals("  - timeline: ", "  -tim".removeSuffix("tim") + prefixedDash.insertText)

        val spacedPrefixedDash = completionFor("  - tim").single { it.label == "timeline" }
        assertEquals("timeline: ", spacedPrefixedDash.insertText)
        assertEquals("  - timeline: ", "  - tim".removeSuffix("tim") + spacedPrefixedDash.insertText)

        val timelineValues = completionFor("  - timeline: ").map { it.label }
        assertEquals(listOf("CommonEra", "ThirdAge"), timelineValues)

        val filteredTimelineValues = completionFor("  - timeline: Com").map { it.label }
        assertEquals(listOf("CommonEra"), filteredTimelineValues)

        val commentedParentText = """
            ---
            id: alice
            kind: Node
            type: Person
            validTime: # validity period
              <cursor>
            ---
        """.trimIndent()
        val marker = "<cursor>"
        val commentedParentOffset = commentedParentText.indexOf(marker)
        val commentedParentItems = FrontMatterCompletionResolver(
            text = commentedParentText.replace(marker, ""),
            offset = commentedParentOffset,
            parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve().orEmpty()
        assertEquals("- timeline: ", commentedParentItems.single { it.label == "timeline" }.insertText)
    }

    @Test
    fun `validTime mapping completion excludes keys already used in the current list item`() {
        fun completionAt(markedText: String): List<String> {
            val marker = "<cursor>"
            val offset = markedText.indexOf(marker)
            val text = markedText.replace(marker, "")
            return FrontMatterCompletionResolver(
                text = text,
                offset = offset,
                parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
                nodeTypeIds = listOf("Person"),
                relTypeIds = emptyList(),
                timelineIds = listOf("TimelineA"),
            ).resolve().orEmpty().map { it.label }
        }

        val afterTimeline = completionAt(
            """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: TimelineA
                <cursor>
            ---
            """.trimIndent(),
        )
        assertEquals(listOf("from", "to"), afterTimeline)

        val afterTimelineAndFrom = completionAt(
            """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: TimelineA
                from:
                  timecode: 1
                <cursor>
            ---
            """.trimIndent(),
        )
        assertEquals(listOf("to"), afterTimelineAndFrom)

        val nextListItem = completionAt(
            """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: TimelineA
              - <cursor>
            ---
            """.trimIndent(),
        )
        assertEquals(listOf("timeline"), nextListItem)
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
        assertTrue("index" !in nextKeyItems)
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
        assertTrue("index" !in blankNextKeyItems)
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
        return ServerFixture(server, published, documents)
    }

    private fun graphDocument(id: String, kind: String): String = """
        ---
        id: $id
        kind: $kind
        ---
    """.trimIndent()

    private class RecordingLanguageClient : LanguageClient {
        val notifications = mutableListOf<PublishDiagnosticsParams>()

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            notifications += params
        }

        fun latest(uri: String): List<org.eclipse.lsp4j.Diagnostic> =
            notifications.last { it.uri == uri }.diagnostics

        override fun telemetryEvent(p0: Any) = Unit

        override fun showMessage(p0: MessageParams) = Unit

        override fun showMessageRequest(p0: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(MessageActionItem())

        override fun logMessage(p0: MessageParams) = Unit
    }

    private companion object {
        const val invalidIdWarning = "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*"
    }

    private data class ServerFixture(
        val server: GraphMdLanguageServer,
        val diagnostics: Map<String, List<org.eclipse.lsp4j.Diagnostic>>,
        val documents: Map<String, String>,
    ) {
        fun definitions(uri: String, offset: Int): List<org.eclipse.lsp4j.Location> {
            return server.textDocumentService.definition(
                DefinitionParams(TextDocumentIdentifier(uri), position(uri, offset)),
            ).get().left.orEmpty()
        }

        fun hover(uri: String, offset: Int): String =
            server.textDocumentService.hover(
                HoverParams(TextDocumentIdentifier(uri), position(uri, offset)),
            ).get().contents.right.value

        fun references(uri: String, offset: Int): List<org.eclipse.lsp4j.Location> =
            server.textDocumentService.references(
                ReferenceParams(TextDocumentIdentifier(uri), position(uri, offset), ReferenceContext(true)),
            ).get()

        fun rename(uri: String, offset: Int, newName: String) =
            server.textDocumentService.rename(
                RenameParams(TextDocumentIdentifier(uri), position(uri, offset), newName),
            ).get()

        fun completions(uri: String, offset: Int) =
            server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), position(uri, offset)),
            ).get().left.orEmpty()

        private fun position(uri: String, offset: Int): Position {
            val text = documents.getValue(uri)
            val line = text.substring(0, offset).count { it == '\n' }
            val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
            return Position(line, offset - lineStart)
        }

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
