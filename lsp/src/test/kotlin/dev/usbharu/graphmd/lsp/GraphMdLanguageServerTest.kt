package dev.usbharu.graphmd.lsp

import dev.usbharu.graphmd.core.GraphDocumentAnalyzer
import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.ReferenceTargetKind
import dev.usbharu.graphmd.core.model.DocumentKind
import dev.usbharu.graphmd.core.model.GraphDocument
import dev.usbharu.graphmd.core.model.NodeDocument
import dev.usbharu.graphmd.core.model.NodeTypeDocument
import dev.usbharu.graphmd.core.model.PropType
import dev.usbharu.graphmd.core.model.RawString
import dev.usbharu.graphmd.core.model.RelTypeDocument
import dev.usbharu.graphmd.core.model.ResolvedPropSchema
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.core.model.TimelineSelector
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.CompletionItemKind
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
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.RenameOptions
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphMdLanguageServerTest {
    private val analyzer = GraphDocumentAnalyzer()

    @Test
    fun `noncanonical decoded ids support navigation hover prepare rename and rename`() {
        val definitionUri = "file:///workspace/noncanonical-type.md"
        val referenceUri = "file:///workspace/noncanonical-node.md"
        val definitionText = "---\r\nid: \"HOGE\\@FUGA\" # keep\r\nkind: NodeType\r\n---\r\n"
        val referenceText = """
            ---
            id: alice
            kind: Node
            type: "HOGE\@FUGA" # keep
            ---
        """.trimIndent()
        val fixture = serverFixture(mapOf(definitionUri to definitionText, referenceUri to referenceText))
        val referenceOffset = referenceText.indexOf("HOGE") + 6
        val referencePosition = Position(3, 12)
        val identifier = TextDocumentIdentifier(referenceUri)

        val definition = fixture.definitions(referenceUri, referenceOffset).single()
        assertEquals(definitionUri, definition.uri)
        assertEquals(Range(Position(1, 5), Position(1, 15)), definition.range)

        val references = fixture.server.textDocumentService.references(
            ReferenceParams(identifier, referencePosition, ReferenceContext(true)),
        ).get()
        assertEquals(setOf(definitionUri, referenceUri), references.map { it.uri }.toSet())

        val hover = fixture.server.textDocumentService.hover(HoverParams(identifier, referencePosition)).get()
        assertTrue(hover?.contents?.right?.value?.contains("HOGE@FUGA") == true)

        val prepared = fixture.server.textDocumentService.prepareRename(
            PrepareRenameParams(identifier, referencePosition),
        ).get()?.second
        assertEquals("HOGE@FUGA", prepared?.placeholder)
        assertEquals(Range(Position(3, 7), Position(3, 17)), prepared?.range)

        val rename = fixture.server.textDocumentService.rename(
            RenameParams(identifier, referencePosition, "ValidType"),
        ).get()
        assertEquals("ValidType", rename?.changes?.getValue(definitionUri)?.single()?.newText)
        assertEquals(Range(Position(1, 5), Position(1, 15)), rename?.changes?.getValue(definitionUri)?.single()?.range)
        assertEquals("ValidType", rename?.changes?.getValue(referenceUri)?.single()?.newText)
        assertTrue(
            fixture.diagnostics.getValue(definitionUri).any {
                it.severity == DiagnosticSeverity.Warning &&
                    it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*"
            },
        )
    }

    @Test
    fun `rename uses only parser-selected duplicate id and full no-separation scalar`() {
        val duplicateUri = "file:///workspace/duplicate.md"
        val referenceUri = "file:///workspace/duplicate-reference.md"
        val suffixUri = "file:///workspace/no-separation.md"
        val duplicateText = "---\nid: A@one\nid: B@two\nkind: NodeType\n---"
        val referenceText = "---\nid: node\nkind: Node\ntype: B@two\n---"
        val suffixText = "---\nid: \"A@id\"#suffix\nkind: NodeType\n---"
        val fixture = serverFixture(
            mapOf(
                duplicateUri to duplicateText,
                referenceUri to referenceText,
                suffixUri to suffixText,
            ),
        )

        val duplicateRename = fixture.server.textDocumentService.rename(
            RenameParams(TextDocumentIdentifier(referenceUri), Position(3, 9), "ValidType"),
        ).get()
        val definitionEdit = duplicateRename?.changes?.getValue(duplicateUri)?.single()
        assertEquals(Range(Position(2, 4), Position(2, 9)), definitionEdit?.range)
        assertEquals("ValidType", definitionEdit?.newText)

        val suffixPrepared = fixture.server.textDocumentService.prepareRename(
            PrepareRenameParams(TextDocumentIdentifier(suffixUri), Position(1, 8)),
        ).get()?.second
        assertEquals("\"A@id\"#suffix", suffixPrepared?.placeholder)
        assertEquals(Range(Position(1, 4), Position(1, 17)), suffixPrepared?.range)
    }

    @Test
    fun `rename preserves quotes around bracket-prefixed ids`() {
        val definitionUri = "file:///workspace/bracket-type.md"
        val referenceUri = "file:///workspace/bracket-node.md"
        val definitionText = "---\nid: \"[Type@id]\"\nkind: NodeType\n---"
        val referenceText = "---\nid: node\nkind: Node\ntype: \"[Type@id]\"\n---"
        val fixture = serverFixture(mapOf(definitionUri to definitionText, referenceUri to referenceText))

        val edit = fixture.server.textDocumentService.rename(
            RenameParams(TextDocumentIdentifier(referenceUri), Position(3, 10), "ValidType"),
        ).get()

        assertEquals(Range(Position(1, 5), Position(1, 14)), edit?.changes?.getValue(definitionUri)?.single()?.range)
        assertEquals(Range(Position(3, 7), Position(3, 16)), edit?.changes?.getValue(referenceUri)?.single()?.range)
        assertEquals("ValidType", edit?.changes?.getValue(definitionUri)?.single()?.newText)
    }

    @Test
    fun `server advertises quick fix code actions`() {
        val capabilities = GraphMdLanguageServer().initialize(InitializeParams()).get().capabilities

        assertNotNull(capabilities.codeActionProvider)
        assertTrue(CodeActionKind.QuickFix in capabilities.codeActionProvider.right.codeActionKinds)
        assertTrue("@" in capabilities.completionProvider.triggerCharacters)
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
    fun `search keeps working when a document references an unknown validTime timeline`() {
        val root = Files.createTempDirectory("graphmd-search-invalid-timeline")
        try {
            Files.writeString(root.resolve("Person.md"), "---\nid: Person\nkind: NodeType\n---")
            Files.writeString(
                root.resolve("broken.md"),
                """
                    ---
                    id: broken
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: MissingTimeline
                    ---
                """.trimIndent(),
            )
            Files.writeString(
                root.resolve("good.md"),
                "---\nid: good\nkind: Node\ntype: Person\n---",
            )
            val server = GraphMdLanguageServer()
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "search"))
                },
            ).get()

            val result = server.search(
                GraphMdSearchParams("MATCH (node:Person) RETURN ID(node) AS id ORDER BY id"),
            ).get()

            assertEquals("good", result.rows.single().values.single())
            assertTrue(result.diagnostics.any {
                it.code == "GRAPHMD_COMPILE" && it.message.contains("Unknown Timeline: MissingTimeline")
            })
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
    fun `node creation quick fix delegates NodeType selection to VS Code`() {
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      name:
                        type: string
                        required: true
                      age:
                        type: number
                        required: true
                      nickname:
                        type: string
                    ---
                """.trimIndent(),
                "file:///workspace/types/Company.md" to "---\nid: Company\nkind: NodeType\n---",
                nodeUri to "---\nid: alice\nkind: Node\ntype: Person\n---\n@link[Bob](missing knows)",
            ),
        )

        val action = fixture.actions(nodeUri, "Unknown Node target: missing")
            .single { it.title == "Create Node 'missing'" }

        assertNull(action.edit)
        val command = assertNotNull(action.command)
        assertEquals("graphmd.createDefinition", command.command)
        val payload = command.arguments.single() as Map<*, *>
        assertEquals("Node", payload["kind"])
        assertEquals("missing", payload["id"])
        assertTrue((payload["uri"] as String).endsWith("/missing.md"))

        val choices = payload["choices"] as List<*>
        assertEquals(listOf("Company", "Person"), choices.map { (it as Map<*, *>)["label"] })
        assertTrue(choices.all { (it as Map<*, *>)["content"].toString().contains("kind: Node") })
        assertTrue(
            choices.any {
                (it as Map<*, *>)["content"] == """
                    ---
                    id: missing
                    kind: Node
                    type: Person
                    props:
                      name: ""
                      age: 0
                    ---
                """.trimIndent() + "\n"
            },
        )
    }

    @Test
    fun `node creation quick fix is unavailable without an existing NodeType`() {
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(nodeUri to "---\nid: alice\nkind: Node\ntype: Person\n---\n@link[Bob](missing knows)"),
        )

        assertTrue(fixture.actions(nodeUri, "Unknown Node target: missing").none { it.title == "Create Node 'missing'" })
    }

    @Test
    fun `node creation quick fix excludes ambiguous NodeTypes`() {
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/person-a.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/types/person-b.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/types/Company.md" to "---\nid: Company\nkind: NodeType\n---",
                nodeUri to "---\nid: alice\nkind: Node\ntype: Company\n---\n@link[Bob](missing knows)",
            ),
        )

        val action = fixture.actions(nodeUri, "Unknown Node target: missing")
            .single { it.title == "Create Node 'missing'" }
        val payload = action.command!!.arguments.single() as Map<*, *>
        val choices = payload["choices"] as List<*>

        assertEquals(listOf("Company"), choices.map { (it as Map<*, *>)["label"] })
    }

    @Test
    fun `type definition quick fixes keep their diagnostic kind`() {
        val nodeUri = "file:///workspace/alice.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                nodeUri to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: MissingEra
                    ---
                    @link[Bob](missing missingRel)
                """.trimIndent(),
            ),
        )

        val relTypeAction = fixture.actions(nodeUri, "Unknown RelType: missingRel")
            .single { it.title == "Create RelType 'missingRel'" }
        val timelineAction = fixture.actions(nodeUri, "Unknown Timeline: MissingEra")
            .single { it.title == "Create Timeline 'MissingEra'" }

        assertTrue(assertNotNull(relTypeAction.edit).documentChanges[1].left.edits.single().newText.contains("kind: RelType"))
        assertTrue(assertNotNull(timelineAction.edit).documentChanges[1].left.edits.single().newText.contains("kind: Timeline"))
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
    fun `duplicate id diagnostics are stable exact and clear when the id becomes unique`() {
        val firstUri = "file:///workspace/alice.md"
        val secondUri = "file:///workspace/bob.md"
        val duplicateText = "---\nid: duplicate\nkind: Node\ntype: Person\n---"
        val fixture = serverFixture(
            mapOf(
                firstUri to duplicateText,
                secondUri to duplicateText,
            ),
        )

        for (uri in listOf(firstUri, secondUri)) {
            val diagnostic = fixture.diagnostics.getValue(uri).single {
                it.message == "Node id must be unique: duplicate"
            }
            assertEquals(Range(Position(1, 4), Position(1, 13)), diagnostic.range)
            assertEquals(DiagnosticSeverity.Error, diagnostic.severity)
            assertEquals("graphmd", diagnostic.source)
            assertEquals("SchemaError", diagnostic.code.left)
        }

        fixture.server.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(secondUri, 2),
                listOf(TextDocumentContentChangeEvent("---\nid: unique\nkind: Node\ntype: Person\n---")),
            ),
        )

        assertTrue(fixture.diagnostics.getValue(firstUri).none {
            it.message == "Node id must be unique: duplicate"
        })
        assertTrue(fixture.diagnostics.getValue(secondUri).none {
            it.message == "Node id must be unique: duplicate"
        })
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
                "file:///workspace/timelines/CommonEra.md" to "---\nid: CommonEra\nkind: Timeline\n---",
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
                "file:///workspace/timelines/CommonEra.md" to "---\nid: CommonEra\nkind: Timeline\n---",
                "file:///workspace/timelines/Broken.md" to """
                    ---
                    id: Broken
                    kind: Timeline
                    mapsTo: MissingTimeline
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
                        from: 5
                        to: 1
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
    fun `definition resolves each document kind from its own id`() {
        val documents = linkedMapOf(
            "file:///workspace/node.md" to "---\nid: node\nkind: Node\ntype: Person\n---",
            "file:///workspace/media.md" to "---\nid: \"media\"\nkind: Media\ntype: Person\nurl: https://example.com/image.png\n---",
            "file:///workspace/Person.md" to "---\nid: Person\nkind: NodeType\n---",
            "file:///workspace/friendOf.md" to "---\nid: friendOf\nkind: RelType\n---",
            "file:///workspace/CommonEra.md" to "---\nid: CommonEra\nkind: Timeline\ntimecode:\n  type: number\n---",
        )
        val fixture = serverFixture(documents)

        documents.forEach { (uri, text) ->
            val idLineStart = text.indexOf("id:")
            val colon = text.indexOf(':', idLineStart)
            val valueStart = (colon + 1 until text.length).first { !text[it].isWhitespace() }
            val valueEnd = text.indexOf('\n', valueStart)
            val definitions = fixture.definitions(uri, valueStart)
            assertEquals(1, definitions.size, uri)
            val definition = definitions.single()

            assertEquals(uri, definition.uri)
            assertEquals(Position(1, valueStart - idLineStart), definition.range.start)
            assertEquals(Position(1, valueEnd - idLineStart), definition.range.end)
            assertEquals(definition, fixture.definitions(uri, valueEnd).single())
        }

        val nodeText = documents.getValue("file:///workspace/node.md")
        assertEquals(
            "file:///workspace/Person.md",
            fixture.definitions("file:///workspace/node.md", nodeText.lastIndexOf("Person")).single().uri,
        )
    }

    @Test
    fun `definition id range follows existing scalar boundary semantics`() {
        val uri = "file:///workspace/quoted.md"
        val text = "---\nid: \"quoted\"\nkind: NodeType\n---"
        val fixture = serverFixture(mapOf(uri to text))
        val quoteStart = text.indexOf('"')
        val afterClosingQuote = text.indexOf('"', quoteStart + 1) + 1

        assertTrue(fixture.definitions(uri, text.indexOf("id")).isEmpty())
        assertTrue(fixture.definitions(uri, text.indexOf(':') + 1).isEmpty())
        assertEquals(uri, fixture.definitions(uri, quoteStart).single().uri)
        assertEquals(uri, fixture.definitions(uri, afterClosingQuote - 1).single().uri)
        assertEquals(uri, fixture.definitions(uri, afterClosingQuote).single().uri)
        assertTrue(fixture.definitions(uri, text.indexOf("kind")).isEmpty())
    }

    @Test
    fun `definition from duplicate id returns every candidate including current document`() {
        val currentUri = "file:///workspace/z-current.md"
        val firstUri = "file:///workspace/a-first.md"
        val secondUri = "file:///workspace/b-second.md"
        val text = "---\nid: duplicate\nkind: NodeType\n---"
        val fixture = serverFixture(
            linkedMapOf(
                secondUri to text,
                currentUri to text,
                firstUri to text,
            ),
        )

        assertEquals(
            listOf(currentUri, firstUri, secondUri),
            fixture.definitions(currentUri, text.indexOf("duplicate")).map { it.uri },
        )
        val locations = fixture.definitions(currentUri, text.indexOf("duplicate"))
        assertEquals(locations.distinct(), locations)
    }

    @Test
    fun `definition uses unsaved content and normalizes request uri`() {
        val canonicalUri = "file:///workspace/current.md"
        val requestUri = "file:///workspace/folder/../current.md"
        val index = GraphMdWorkspaceIndex()
        val original = "---\nid: original\nkind: NodeType\n---"
        val unsaved = "---\nid: unsaved\nkind: NodeType\n---"
        index.open(canonicalUri, original)
        index.upsert(canonicalUri, unsaved)

        val locations = index.definitions(requestUri, Position(1, 5))

        assertEquals(canonicalUri, locations.single().uri)
        assertEquals(Range(Position(1, 4), Position(1, 11)), locations.single().range)
    }

    @Test
    fun `definitions properties and diagnostics use LSP positions in mixed CRLF documents`() {
        val typeUri = "file:///workspace/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        val typeText = "---\r\nkind: NodeType\nprops:\r  名前:\r\n    type: string\nid: Person\r---"
        val nodeText =
            "---\r\nkind: Node\rid: alice\ntype: Person\r\nprops:\r  名前: Alice\r\n---\n" +
                "@link{}[😀](missing friendOf)"
        val fixture = serverFixture(linkedMapOf(typeUri to typeText, nodeUri to nodeText))

        val selfDefinition = fixture.definitions(typeUri, typeText.indexOf("Person")).single()
        assertEquals(typeUri, selfDefinition.uri)
        assertEquals(Range(Position(5, 4), Position(5, 10)), selfDefinition.range)

        val referenceDefinition = fixture.definitions(nodeUri, nodeText.indexOf("Person")).single()
        assertEquals(selfDefinition, referenceDefinition)

        val propertyDefinition = fixture.definitions(nodeUri, nodeText.indexOf("名前") + 1).single()
        assertEquals(typeUri, propertyDefinition.uri)
        assertEquals(Range(Position(3, 2), Position(3, 4)), propertyDefinition.range)

        val unresolved = fixture.diagnostics.getValue(nodeUri).first { it.message == "Unknown Node target: missing" }
        val missingCharacter = nodeText.substringAfterLast('\n').indexOf("missing")
        assertEquals(
            Range(Position(7, missingCharacter), Position(7, missingCharacter + "missing".length)),
            unresolved.range,
        )
    }

    @Test
    fun `definition handles lone CR empty lines and closing marker at EOF`() {
        val uri = "file:///workspace/person.md"
        val text = "---\rkind: NodeType\r\rid: person\r---"
        val fixture = serverFixture(mapOf(uri to text))

        val definition = fixture.definitions(uri, text.indexOf("person")).single()

        assertEquals(uri, definition.uri)
        assertEquals(Range(Position(3, 4), Position(3, 10)), definition.range)
    }

    @Test
    fun `relation constraint quick fix maps analyzer range in CR document`() {
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = "---\rkind: Node\rid: alice\rtype: Person\r---\r@link[😀](acme knows)"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/Company.md" to "---\nid: Company\nkind: NodeType\n---",
                "file:///workspace/knows.md" to "---\nid: knows\nkind: RelType\nfrom: [Person]\nto: [Person]\n---",
                "file:///workspace/worksAt.md" to "---\nid: worksAt\nkind: RelType\nfrom: [Person]\nto: [Company]\n---",
                "file:///workspace/acme.md" to "---\nid: acme\nkind: Node\ntype: Company\n---",
                nodeUri to nodeText,
            ),
        )

        val edit = fixture.actions(nodeUri, "Relation target type Company is not allowed for knows")
            .first { it.title == "Change relation type to 'worksAt'" }
            .edit.changes.getValue(nodeUri).single()
        val relationLine = nodeText.substringAfterLast('\r')
        val start = relationLine.indexOf("knows")

        assertEquals(Range(Position(5, start), Position(5, start + "knows".length)), edit.range)
        assertEquals("worksAt", edit.newText)

        val completions = fixture.server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(nodeUri), Position(5, start + 2)),
        ).get().left.orEmpty()
        assertTrue(completions.any { it.label == "worksAt" })
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
    fun `inline timeline token supports definition references rename and exact unknown diagnostic ranges`() {
        val timelineUri = "file:///workspace/timelines/CommonEra.md"
        val missingUri = "file:///workspace/missing.md"
        val quotedUri = "file:///workspace/quoted.md"
        val missingText = """
            ---
            id: missing
            kind: Node
            type: Person
            ---
            @props{born={timeline=MissingEra,value=1},note="validTime=today timeline=Fake"}
        """.trimIndent()
        val quotedText = """
            ---
            id: quoted
            kind: Node
            type: Person
            ---
            😀 @props{born={timeline="CommonEra",value=1}}
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      born:
                        type: instant
                        timeline: "*"
                      note:
                        type: string
                    ---
                """.trimIndent(),
                timelineUri to "---\nid: CommonEra\nkind: Timeline\n---",
                missingUri to missingText,
                quotedUri to quotedText,
            ),
        )

        val quotedOffset = quotedText.indexOf("CommonEra") + 1
        assertEquals(timelineUri, fixture.definitions(quotedUri, quotedOffset).single().uri)

        val references = fixture.references(quotedUri, quotedOffset)
        assertEquals(2, references.size)
        assertTrue(references.any { it.uri == timelineUri && it.range.start == Position(1, 4) })
        assertTrue(references.any { it.uri == quotedUri && it.range.start == Position(5, 26) && it.range.end == Position(5, 35) })

        val rename = fixture.rename(quotedUri, quotedOffset, "ModernEra")!!
        assertEquals("ModernEra", rename.changes.getValue(quotedUri).single().newText)
        assertEquals(Range(Position(5, 26), Position(5, 35)), rename.changes.getValue(quotedUri).single().range)

        val unknown = fixture.diagnostics.getValue(missingUri)
            .singleOrNull { it.message == "Unknown Timeline: MissingEra" }
            ?: error(fixture.diagnostics.getValue(missingUri).joinToString { it.message })
        assertEquals(Range(Position(5, 22), Position(5, 32)), unknown.range)
        assertTrue(fixture.diagnostics.getValue(missingUri).none { "today" in it.message || "Fake" in it.message })
    }

    @Test
    fun `new Timeline relationships support navigation rename and inferred hover`() {
        val baseUri = "file:///workspace/timelines/Base.md"
        val aliasUri = "file:///workspace/timelines/Alias.md"
        val forkUri = "file:///workspace/timelines/Fork.md"
        val recordingUri = "file:///workspace/timelines/Recording.md"
        val baseText = "---\nid: Base\nkind: Timeline\n---"
        val aliasText = "---\nid: Alias\nkind: Timeline\nsameAxisAs: Base\n---"
        val forkText = "---\nid: Fork\nkind: Timeline\nderivedFrom:\n  timeline: Base\n  kind: fork\n---"
        val recordingText = "---\nid: Recording\nkind: Timeline\nmapsTo:\n  timeline: Base\n  kind: alignment\n---"
        val fixture = serverFixture(
            mapOf(
                baseUri to baseText,
                aliasUri to aliasText,
                forkUri to forkText,
                recordingUri to recordingText,
            ),
        )

        assertEquals(baseUri, fixture.definitions(aliasUri, aliasText.lastIndexOf("Base") + 1).single().uri)
        assertEquals(baseUri, fixture.definitions(forkUri, forkText.indexOf("Base") + 1).single().uri)
        assertEquals(baseUri, fixture.definitions(recordingUri, recordingText.indexOf("Base") + 1).single().uri)
        val rename = assertNotNull(fixture.rename(baseUri, baseText.indexOf("Base") + 1, "Origin"))
        assertEquals(setOf(baseUri, aliasUri, forkUri, recordingUri), rename.changes.keys)
        val hover = fixture.hover(recordingUri, recordingText.indexOf("Recording") + 1)
        assertTrue("- Domain: `domain:Recording`" in hover, hover)
        assertTrue("- Mapping to `Base`:" in hover, hover)
    }

    @Test
    fun `body block winning timeline supports completion navigation rename and exact diagnostics`() {
        val timelineUri = "file:///workspace/timelines/WinningEra.md"
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            ::: history validTime=IgnoredEra annotation validTime=WinningEra
            inside
            :::
            ::: audit validTime=MissingEra
            missing
            :::
            ```markdown
            ::: hidden validTime=CodeEra
            :::
            ```
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to graphDocument("Person", "NodeType"),
                timelineUri to "---\nid: WinningEra\nkind: Timeline\ntimecode:\n  type: number\n---",
                nodeUri to nodeText,
            ),
        )
        val winningOffset = nodeText.indexOf("WinningEra")

        assertEquals(timelineUri, fixture.definitions(nodeUri, winningOffset + 1).single().uri)
        assertEquals(2, fixture.references(nodeUri, winningOffset + 1).size)
        assertTrue("WinningEra" in fixture.completions(nodeUri, winningOffset + 3).map { it.label })
        assertTrue(fixture.completions(nodeUri, nodeText.indexOf("annotation") + 3).isEmpty())
        assertTrue(fixture.completions(nodeUri, nodeText.indexOf("CodeEra") + 3).isEmpty())

        val rename = assertNotNull(fixture.rename(nodeUri, winningOffset + 1, "RenamedEra"))
        assertEquals(
            Range(positionAt(nodeText, winningOffset), positionAt(nodeText, winningOffset + "WinningEra".length)),
            rename.changes.getValue(nodeUri).single().range,
        )
        assertTrue(rename.changes.getValue(nodeUri).none { "IgnoredEra" in it.newText })

        val missingOffset = nodeText.indexOf("MissingEra")
        val unknown = fixture.diagnostics.getValue(nodeUri).single { it.message == "Unknown Timeline: MissingEra" }
        assertEquals(
            Range(positionAt(nodeText, missingOffset), positionAt(nodeText, missingOffset + "MissingEra".length)),
            unknown.range,
        )
        assertTrue(
            fixture.diagnostics.getValue(nodeUri).none {
                "IgnoredEra" in it.message || "CodeEra" in it.message
            },
        )
    }

    @Test
    fun `legacy Property Schema timeline selectors support definition references rename and diagnostics`() {
        val timelineUri = "file:///workspace/timelines/CommonEra.md"
        val schemaUri = "file:///workspace/types/Event.md"
        val schemaText = """
            ---
            id: Event
            kind: NodeType
            props:
              compact:
                type: instant
                timeline:
                  - CommonEra:
                    mapped: false
              explicit:
                type: instant
                timeline:
                  - mapped: true
                    extra: ignored
                    id: "CommonEra"
              missingCompact:
                type: instant
                timeline:
                  - MissingCompact:
                    mapped: false
              missingExplicit:
                type: instant
                timeline:
                  - mapped: false
                    id: 'MissingExplicit'
            ---
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                timelineUri to "---\r\nid: CommonEra\r\nkind: Timeline\r\n---",
                schemaUri to schemaText,
            ),
        )
        val compactOffset = schemaText.indexOf("CommonEra:")
        val explicitOffset = schemaText.indexOf("\"CommonEra\"") + 1

        assertEquals(timelineUri, fixture.definitions(schemaUri, compactOffset + 1).single().uri)
        assertEquals(timelineUri, fixture.definitions(schemaUri, explicitOffset + 1).single().uri)
        assertEquals(3, fixture.references(schemaUri, compactOffset + 1).size)

        val rename = assertNotNull(fixture.rename(schemaUri, explicitOffset + 1, "SharedEra"))
        assertEquals(3, rename.changes.values.sumOf { it.size })
        val timelineEdit = rename.changes.getValue(timelineUri).single()
        assertEquals(Range(Position(1, 4), Position(1, 13)), timelineEdit.range)
        val schemaEdits = rename.changes.getValue(schemaUri)
        assertEquals(2, schemaEdits.size)
        assertTrue(schemaEdits.all { it.newText == "SharedEra" })
        assertEquals(
            setOf(positionAt(schemaText, compactOffset), positionAt(schemaText, explicitOffset)),
            schemaEdits.map { it.range.start }.toSet(),
        )

        val missingCompactOffset = schemaText.indexOf("MissingCompact:")
        val missingExplicitOffset = schemaText.indexOf("'MissingExplicit'") + 1
        val missingCompact = fixture.diagnostics.getValue(schemaUri).single { it.message == "Unknown Timeline: MissingCompact" }
        val missingExplicit = fixture.diagnostics.getValue(schemaUri).single { it.message == "Unknown Timeline: MissingExplicit" }
        assertEquals(positionAt(schemaText, missingCompactOffset), missingCompact.range.start)
        assertEquals(positionAt(schemaText, missingCompactOffset + "MissingCompact".length), missingCompact.range.end)
        assertEquals(positionAt(schemaText, missingExplicitOffset), missingExplicit.range.start)
        assertEquals(positionAt(schemaText, missingExplicitOffset + "MissingExplicit".length), missingExplicit.range.end)
    }

    @Test
    fun `double quoted selector escapes resolve rename and locate diagnostics by raw range`() {
        val timelineUri = "file:///workspace/timelines/CommonqEra.md"
        val schemaUri = "file:///workspace/types/EscapedEvent.md"
        val timelineText = """
            ---
            id: "Common\qEra"
            kind: Timeline
            ---
        """.trimIndent()
        val schemaText = """
            ---
            id: EscapedEvent
            kind: NodeType
            props:
              known:
                type: instant
                timeline: "Common\qEra"
              missing:
                type: instant
                timeline:
                  - id: "Missing\qEra"
                    mapped: false
            ---
        """.trimIndent()
        val fixture = serverFixture(mapOf(timelineUri to timelineText, schemaUri to schemaText))
        val knownOffset = schemaText.indexOf("""Common\qEra""")

        assertEquals(timelineUri, fixture.definitions(schemaUri, knownOffset + 1).single().uri)
        assertEquals(2, fixture.references(schemaUri, knownOffset + 1).size)
        val rename = assertNotNull(fixture.rename(schemaUri, knownOffset + 1, "RenamedEra"))
        assertEquals(2, rename.changes.values.sumOf { it.size })
        assertEquals(
            Range(positionAt(schemaText, knownOffset), positionAt(schemaText, knownOffset + """Common\qEra""".length)),
            rename.changes.getValue(schemaUri).single().range,
        )

        val missingRaw = """Missing\qEra"""
        val missingOffset = schemaText.indexOf(missingRaw)
        val diagnostic = fixture.diagnostics.getValue(schemaUri).single { it.message == "Unknown Timeline: MissingqEra" }
        assertEquals(
            Range(positionAt(schemaText, missingOffset), positionAt(schemaText, missingOffset + missingRaw.length)),
            diagnostic.range,
        )
    }

    @Test
    fun `nested front matter id and type do not participate in navigation rename or diagnostics`() {
        val typeUri = "file:///workspace/types/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        val typeText = "---\nid: Person\nkind: NodeType\n---"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              metadata:
                id: alice
                type: MissingNestedType
              values:
                - id: nested-list
                  type: Person
            ---
        """.trimIndent()
        val fixture = serverFixture(mapOf(typeUri to typeText, nodeUri to nodeText))

        assertTrue(fixture.definitions(nodeUri, nodeText.indexOf("MissingNestedType") + 1).isEmpty())
        assertTrue(fixture.definitions(nodeUri, nodeText.indexOf("id: alice", nodeText.indexOf("props:")) + 4).isEmpty())
        assertEquals(
            typeUri,
            fixture.definitions(nodeUri, nodeText.indexOf("type: Person") + "type: ".length + 1).single().uri,
        )
        assertTrue(
            fixture.diagnostics.getValue(nodeUri).none { it.message == "Unknown NodeType: MissingNestedType" },
        )

        val definitionPosition = Position(1, 5)
        val references = fixture.server.textDocumentService.references(
            ReferenceParams(TextDocumentIdentifier(typeUri), definitionPosition, ReferenceContext(true)),
        ).get()
        assertEquals(2, references.size)

        val rename = fixture.server.textDocumentService.rename(
            RenameParams(TextDocumentIdentifier(typeUri), definitionPosition, "Human"),
        ).get()
        assertNotNull(rename)
        assertEquals(2, rename.changes.orEmpty().values.sumOf { it.size })
        assertEquals(1, rename.changes.orEmpty().getValue(nodeUri).size)
    }

    @Test
    fun `rename advertises preparation and accepts only canonical GraphMD ids`() {
        val renameProvider = GraphMdLanguageServer().initialize(InitializeParams()).get().capabilities.renameProvider
        assertTrue(renameProvider.isRight)
        assertEquals(true, (renameProvider.right as RenameOptions).prepareProvider)

        val index = GraphMdWorkspaceIndex()
        val uri = "file:///workspace/node.md"
        index.upsert(uri, graphDocument("old", "NodeType"))

        listOf("A", "_", "node_01.part:local-name").forEach { newName ->
            val edit = index.rename(uri, Position(1, 5), newName)
            assertEquals(newName, edit?.changes?.get(uri)?.single()?.newText)
        }
        listOf("", " ", "1node", ".node", ":node", "-node", "node/name", "node%name", "node)", "ノード", "node\nname").forEach { newName ->
            val error = assertFailsWith<ResponseErrorException> {
                index.rename(uri, Position(1, 5), newName)
            }
            assertEquals(ResponseErrorCode.RequestFailed.value, error.responseError.code)
            assertTrue(error.message.orEmpty().contains("[A-Za-z_][A-Za-z0-9_.:-]*"))
        }
    }

    @Test
    fun `rename updates definitions and references for every symbol namespace`() {
        val index = GraphMdWorkspaceIndex()
        val personUri = "file:///workspace/Person.md"
        val relationUri = "file:///workspace/friendOf.md"
        val timelineUri = "file:///workspace/Era.md"
        val aliceUri = "file:///workspace/alice.md"
        val bobUri = "file:///workspace/bob.md"
        index.upsert(personUri, graphDocument("Person", "NodeType"))
        index.upsert(relationUri, graphDocument("friendOf", "RelType"))
        index.upsert(timelineUri, "---\nid: Era\nkind: Timeline\ntimecode:\n  type: number\n---")
        index.upsert(bobUri, "---\nid: bob\nkind: Node\ntype: Person\n---")
        index.upsert(
            aliceUri,
            """
                ---
                id: alice
                kind: Node
                type: Person
                validTime:
                  - timeline: Era
                ---
                @link[Bob](bob friendOf)
            """.trimIndent(),
        )

        listOf(
            Triple(personUri, "Person2", aliceUri),
            Triple(relationUri, "relatedTo", aliceUri),
            Triple(timelineUri, "ModernEra", aliceUri),
            Triple(bobUri, "robert", aliceUri),
        ).forEach { (definitionUri, newName, referenceUri) ->
            val edit = index.rename(definitionUri, Position(1, 5), newName)
            assertEquals(newName, edit?.changes?.get(definitionUri)?.single()?.newText)
            assertTrue(edit?.changes?.get(referenceUri).orEmpty().any { it.newText == newName })
        }
    }

    @Test
    fun `rename rejects same namespace collisions including Media and ambiguous sources`() {
        val index = GraphMdWorkspaceIndex()
        val sourceUri = "file:///workspace/source.md"
        index.upsert(sourceUri, "---\nid: source\nkind: Node\ntype: Asset\n---")
        index.upsert("file:///workspace/media.md", "---\nid: media\nkind: Media\ntype: Asset\nurl: image.png\n---")
        index.upsert("file:///workspace/Asset.md", graphDocument("Asset", "NodeType"))

        val mediaCollision = assertFailsWith<ResponseErrorException> {
            index.rename(sourceUri, Position(1, 5), "media")
        }
        assertTrue(mediaCollision.message.orEmpty().contains("already defined"))

        val differentKind = index.rename(sourceUri, Position(1, 5), "Asset")
        assertEquals("Asset", differentKind?.changes?.get(sourceUri)?.single()?.newText)
        assertEquals("source", index.rename(sourceUri, Position(1, 5), "source")?.changes?.get(sourceUri)?.single()?.newText)
        assertEquals("Media", index.rename(sourceUri, Position(1, 5), "Media")?.changes?.get(sourceUri)?.single()?.newText)

        listOf("NodeType", "RelType", "Timeline").forEach { kind ->
            val kindIndex = GraphMdWorkspaceIndex()
            val firstUri = "file:///workspace/$kind-first.md"
            kindIndex.upsert(firstUri, graphDocument("first", kind))
            kindIndex.upsert("file:///workspace/$kind-second.md", graphDocument("second", kind))
            assertFailsWith<ResponseErrorException> {
                kindIndex.rename(firstUri, Position(1, 5), "second")
            }
        }

        val duplicateIndex = GraphMdWorkspaceIndex()
        duplicateIndex.upsert("file:///workspace/duplicate-a.md", graphDocument("duplicate", "NodeType"))
        duplicateIndex.upsert("file:///workspace/duplicate-b.md", graphDocument("duplicate", "NodeType"))
        val ambiguous = assertFailsWith<ResponseErrorException> {
            duplicateIndex.rename("file:///workspace/duplicate-a.md", Position(1, 5), "unique")
        }
        assertTrue(ambiguous.message.orEmpty().contains("ambiguous"))
        assertFailsWith<ResponseErrorException> {
            duplicateIndex.prepareRename("file:///workspace/duplicate-a.md", Position(1, 5))
        }
    }

    @Test
    fun `prepare rename uses unsaved symbols and noncanonical ids can be repaired`() {
        val index = GraphMdWorkspaceIndex()
        val uri = "file:///workspace/unsaved.md"
        index.upsert(uri, graphDocument("disk-name", "NodeType"))
        index.upsert(uri, graphDocument("bad/id", "NodeType"))

        val prepared = assertNotNull(index.prepareRename(uri, Position(1, 6)))
        assertEquals("bad/id", prepared.placeholder)
        assertEquals(Range(Position(1, 4), Position(1, 10)), prepared.range)

        val edit = assertNotNull(index.rename(uri, Position(1, 6), "canonical.id"))
        assertEquals("canonical.id", edit.changes?.get(uri)?.single()?.newText)
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
    fun `node type completion includes definitions outside types directories`() {
        val nodeText = "---\nid: alice\nkind: Node\ntype: Canonical\n---"
        val nodeTypeText = "---\nid: Child\nkind: NodeType\nextends: Canonical\n---"
        val relTypeText = "---\nid: connects\nkind: RelType\nfrom: Canonical\nto: Canonical\n---"
        val nodeUri = "file:///workspace/alice.md"
        val nodeTypeUri = "file:///workspace/models/Child.md"
        val relTypeUri = "file:///workspace/relations/connects.md"
        val server = serverFixture(
            mapOf(
                "file:///workspace/types/Canonical.md" to graphDocument("Canonical", "NodeType"),
                "file:///workspace/models/nested/Alpha.md" to graphDocument("Alpha", "NodeType"),
                "file:///C:/graph/schemas/WindowsNode.md" to graphDocument("WindowsNode", "NodeType"),
                "file:///workspace/duplicates/Alpha.md" to graphDocument("Alpha", "NodeType"),
                "file:///workspace/types/unrelated-rel.md" to graphDocument("unrelatedRel", "RelType"),
                "file:///workspace/unrelated-node.md" to graphDocument("unrelatedNode", "Node"),
                nodeUri to nodeText,
                nodeTypeUri to nodeTypeText,
                relTypeUri to relTypeText,
            ),
        ).server
        val expected = listOf("Alpha", "Canonical", "Child", "WindowsNode")

        fun labelsAtValueStart(uri: String, text: String, field: String): List<String> {
            val line = text.lines().indexOfFirst { it.startsWith("$field:") }
            val character = text.lines()[line].indexOf(':') + 2
            return server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), Position(line, character)),
            ).get().left.map { it.label }
        }

        assertEquals(expected, labelsAtValueStart(nodeUri, nodeText, "type"))
        assertEquals(expected, labelsAtValueStart(nodeTypeUri, nodeTypeText, "extends"))
        assertEquals(expected, labelsAtValueStart(relTypeUri, relTypeText, "from"))

        val items = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(nodeUri), Position(3, "type: ".length)),
        ).get().left
        assertEquals(expected, items.map { it.label })
        assertTrue(items.all { it.kind == CompletionItemKind.Reference })
        assertTrue(items.all { it.detail == "NodeType" })
        assertEquals(expected, items.map { it.insertText })
        assertEquals(expected.map { "1-$it" }, items.map { it.sortText })
    }

    @Test
    fun `relation type completion includes definitions outside types directories`() {
        val relTypeText = "---\nid: childRel\nkind: RelType\nextends: CanonicalRel\n---"
        val nodeText = "---\nid: alice\nkind: Node\ntype: Person\n---\n@link[Bob](bob CanonicalRel)"
        val relTypeUri = "file:///workspace/relations/childRel.md"
        val nodeUri = "file:///workspace/alice.md"
        val server = serverFixture(
            mapOf(
                "file:///workspace/types/CanonicalRel.md" to graphDocument("CanonicalRel", "RelType"),
                "file:///workspace/relations/nested/AlphaRel.md" to graphDocument("AlphaRel", "RelType"),
                "file:///C:/graph/schemas/WindowsRel.md" to graphDocument("WindowsRel", "RelType"),
                "file:///workspace/duplicates/AlphaRel.md" to graphDocument("AlphaRel", "RelType"),
                "file:///workspace/types/Person.md" to graphDocument("Person", "NodeType"),
                "file:///workspace/unrelated-node.md" to graphDocument("unrelatedNode", "Node"),
                relTypeUri to relTypeText,
                nodeUri to nodeText,
            ),
        ).server
        val expected = listOf("AlphaRel", "CanonicalRel", "childRel", "WindowsRel").sorted()

        val extendsItems = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(relTypeUri), Position(3, "extends: ".length)),
        ).get().left
        assertEquals(expected, extendsItems.map { it.label })
        assertEquals(expected.toSet().size, extendsItems.size)
        assertTrue(extendsItems.all { it.kind == CompletionItemKind.Reference })
        assertTrue(extendsItems.all { it.detail == "RelType" })

        val relationServer = serverFixture(
            mapOf(
                "file:///workspace/types/CanonicalRel.md" to graphDocument("CanonicalRel", "RelType"),
                "file:///workspace/relations/nested/AlphaRel.md" to graphDocument("AlphaRel", "RelType"),
                "file:///C:/graph/schemas/WindowsRel.md" to graphDocument("WindowsRel", "RelType"),
                "file:///workspace/types/Person.md" to graphDocument("Person", "NodeType"),
                relTypeUri to relTypeText,
                nodeUri to nodeText,
            ),
        ).server
        val relationItems = relationServer.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(nodeUri), Position(5, "@link[Bob](bob ".length)),
        ).get().left
        assertEquals(expected, relationItems.map { it.label })
        assertTrue(relationItems.all { it.kind == CompletionItemKind.Class })
        assertTrue(relationItems.all { it.detail == "RelType" })
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
            "durations" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(type = PropType.duration, timeline = TimelineSelector.Id("ThirdAge")),
            ),
            "matrix" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(type = PropType.array, items = ResolvedPropSchema(type = PropType.number)),
            ),
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
        assertEquals(
            "name = \"\${1:value}\"",
            resolve("name").single { it.label == "name" }.insertText,
        )
        assertEquals(
            "activeDuring = { timeline = \${1:ThirdAge}, from = \${2:0}, to = \${3:0} }",
            resolve("activeDuring").single { it.label == "activeDuring" }.insertText,
        )
        assertEquals(
            "durations = [ { timeline = \${1:ThirdAge}, from = \${2:0}, to = \${3:0} } ]",
            resolve("durations").single { it.label == "durations" }.insertText,
        )
        assertEquals(
            "[ { timeline = \${1:ThirdAge}, from = \${2:0}, to = \${3:0} } ]",
            resolve("durations = ").single().insertText,
        )
        assertEquals(
            "matrix = [ [ \${1:0} ] ]",
            resolve("matrix").single { it.label == "matrix" }.insertText,
        )
        assertEquals(
            "labels = [ \"\${1:value}\" ]",
            resolve("labels").single { it.label == "labels" }.insertText,
        )
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
    fun `enum props completion offers configured scalar text and array values`() {
        val enumValues = listOf(RawString("draft"), RawString("published"))
        val arrayEnumValues = listOf(RawString("alpha"), RawString("beta"))
        val nestedEnumValues = listOf(RawString("deep"), RawString("deeper"))
        val schema = mapOf(
            "status" to ResolvedPropSchema(type = PropType.string, enumValues = enumValues),
            "labels" to ResolvedPropSchema(type = PropType.text, enumValues = enumValues),
            "tags" to ResolvedPropSchema(type = PropType.array, enumValues = arrayEnumValues),
            "matrix" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(
                    type = PropType.array,
                    items = ResolvedPropSchema(type = PropType.string, enumValues = nestedEnumValues),
                ),
            ),
        )
        fun resolve(body: String) = PropsCompletionContextResolver(
            text = "@props{$body",
            offset = "@props{$body".length,
            rootSchema = schema,
            timelineIds = emptyList(),
        ).resolve()?.items.orEmpty()

        assertEquals(listOf("draft", "published"), resolve("status = ").map { it.label })
        assertEquals(listOf("published"), resolve("status = pub").map { it.label })
        assertTrue(resolve("status = ").all { it.kind == CompletionItemKind.EnumMember && it.detail == "enum" })
        assertEquals(listOf("draft", "published"), resolve("labels(key=\"en\") = ").map { it.label })
        assertEquals(listOf("alpha", "beta"), resolve("tags = [").map { it.label })
        assertEquals(listOf("beta"), resolve("tags = [b").map { it.label })
        assertEquals(listOf("deep", "deeper"), resolve("matrix = [[").map { it.label })
    }

    @Test
    fun `front matter enum completion filters scalar and block array values`() {
        val schema = mapOf(
            "status" to ResolvedPropSchema(
                type = PropType.string,
                enumValues = listOf(RawString("draft"), RawString("published")),
            ),
            "tags" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(
                    type = PropType.string,
                    enumValues = listOf(RawString("alpha"), RawString("beta")),
                ),
            ),
        )
        fun resolve(text: String, cursor: String): List<String> {
            val offset = text.indexOf(cursor) + cursor.length
            return FrontMatterCompletionResolver(
                text = text,
                offset = offset,
                parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
                nodeTypeIds = listOf("Person"),
                relTypeIds = emptyList(),
                timelineIds = emptyList(),
                nodePropsSchema = schema,
            ).resolve().orEmpty().map { it.label }
        }

        assertEquals(
            listOf("published"),
            resolve("---\nid: alice\nkind: Node\ntype: Person\nprops:\n  status: pub\n---", "pub"),
        )
        assertEquals(
            listOf("alpha", "beta"),
            resolve("---\nid: alice\nkind: Node\ntype: Person\nprops:\n  tags:\n    - \n---", "- "),
        )
    }

    @Test
    fun `enum diagnostics highlight the invalid property value`() {
        val typeUri = "file:///workspace/types/Person.md"
        val nodeUri = "file:///workspace/alice.md"
        val inlineUri = "file:///workspace/inline.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            props:
              status: invalid
              tags:
                - invalid
              labels:
                en:
                  value: invalid
            ---
        """.trimIndent()
        val inlineText = """
            ---
            id: inline
            kind: Node
            type: Person
            ---
            @props{status = invalid}
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                typeUri to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      status:
                        type: string
                        enum:
                          - draft
                          - published
                      tags:
                        type: array
                        items:
                          type: string
                          enum:
                            - draft
                            - published
                      labels:
                        type: text
                        enum:
                          - draft
                          - published
                    ---
                """.trimIndent(),
                nodeUri to nodeText,
                inlineUri to inlineText,
            ),
        )

        val diagnostic = fixture.diagnostics.getValue(nodeUri).single {
            it.message == "status value is not in enum"
        }
        assertEquals(Range(Position(5, 10), Position(5, 17)), diagnostic.range)

        val arrayDiagnostic = fixture.diagnostics.getValue(nodeUri).single {
            it.message == "tags[] value is not in enum"
        }
        assertEquals(Range(Position(7, 6), Position(7, 13)), arrayDiagnostic.range)

        val textDiagnostic = fixture.diagnostics.getValue(nodeUri).single {
            it.message == "labels.en value is not in enum"
        }
        assertEquals(Range(Position(10, 13), Position(10, 20)), textDiagnostic.range)

        val inlineDiagnostic = fixture.diagnostics.getValue(inlineUri).single {
            it.message == "status value is not in enum"
        }
        assertEquals(Range(Position(5, 16), Position(5, 23)), inlineDiagnostic.range)
    }

    @Test
    fun `relation props key completion uses the relation schema value shape`() {
        val text = "@link{since}[Bob](bob \"friendOf\")"
        val items = PropsCompletionContextResolver(
            text = text,
            offset = text.indexOf("since") + "since".length,
            rootSchema = mapOf("since" to ResolvedPropSchema(type = PropType.number)),
            timelineIds = emptyList(),
            explicitBraceStart = text.indexOf('{'),
        ).resolve()?.items.orEmpty()

        assertEquals("since = 0", items.single { it.label == "since" }.insertText)
        assertEquals(InsertTextFormat.PlainText, items.single { it.label == "since" }.insertTextFormat)
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
    fun `@link completion inserts a reltype snippet with required property defaults`() {
        val nodeTypeUri = "file:///workspace/types/Person.md"
        val relationUri = "file:///workspace/types/friendOf.md"
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            @link
        """.trimIndent().replace("\n", "\r\n")
        val fixture = serverFixture(
            mapOf(
                nodeTypeUri to """
                    ---
                    id: Person
                    kind: NodeType
                    ---
                """.trimIndent(),
                relationUri to """
                    ---
                    id: friendOf
                    kind: RelType
                    from: [Person]
                    to: [Person]
                    props:
                      weight:
                        type: number
                        required: true
                      note:
                        type: text
                        required: true
                      occurredAt:
                        type: instant
                        required: true
                      interval:
                        type: duration
                        required: true
                      tags:
                        type: array
                        required: true
                        items:
                          type: string
                    ---
                """.trimIndent(),
                nodeUri to nodeText,
            ),
        )

        val items = fixture.completions(nodeUri, nodeText.length)

        assertEquals(listOf("@link (friendOf)"), items.map { it.label })
        val item = items.single()
        assertEquals(CompletionItemKind.Snippet, item.kind)
        assertEquals(InsertTextFormat.Snippet, item.insertTextFormat)
        assertEquals(
            "@link{weight = 0, note = \"\", occurredAt = 0, interval = { from = 0 }, tags = []}[\${1:title}](\${2:id} \${3:friendOf})",
            item.insertText,
        )
        assertEquals(
            Range(Position(5, 0), Position(5, "@link".length)),
            item.textEdit?.left?.range,
        )
    }

    @Test
    fun `@link completion filters reltypes by source endpoint and supports partial keyword`() {
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = "---\nid: alice\nkind: Node\ntype: Person\n---\n@lin"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/types/Company.md" to "---\nid: Company\nkind: NodeType\n---",
                "file:///workspace/types/friendOf.md" to "---\nid: friendOf\nkind: RelType\nfrom: [Person]\n---",
                "file:///workspace/types/worksAt.md" to "---\nid: worksAt\nkind: RelType\nfrom: [Company]\n---",
                nodeUri to nodeText,
            ),
        )

        val items = fixture.completions(nodeUri, nodeText.length)

        assertEquals(listOf("@link (friendOf)"), items.map { it.label })
        assertEquals(
            Range(Position(5, 0), Position(5, "@lin".length)),
            items.single().textEdit?.left?.range,
        )
        assertTrue(items.single().insertText.orEmpty().contains("\${3:friendOf}"))
    }

    @Test
    fun `@link completion falls back to a generic snippet without reltypes`() {
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = "---\nid: alice\nkind: Node\ntype: Person\n---\n@link"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                nodeUri to nodeText,
            ),
        )

        val item = fixture.completions(nodeUri, nodeText.length).single()

        assertEquals("@link", item.label)
        assertEquals("@link[\${1:title}](\${2:id} \${3:reltype})", item.insertText)
    }

    @Test
    fun `@link completion excludes completed links escaped text code and non-node documents`() {
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            `@link`
            \@link
            ```
            @link
            ```
            @link(
            @link{
            @link[title](bob friendOf)
        """.trimIndent()
        val nodeTypeUri = "file:///workspace/types/Person.md"
        val nodeTypeText = """
            ---
            id: Person
            kind: NodeType
            ---
            @link
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/friendOf.md" to "---\nid: friendOf\nkind: RelType\n---",
                nodeUri to nodeText,
                nodeTypeUri to nodeTypeText,
            ),
        )

        val codeSpanOffset = nodeText.indexOf("@link") + "@link".length
        val escapedOffset = nodeText.indexOf("\\@link") + "\\@link".length
        val fenceStart = nodeText.indexOf("```")
        val fencedOffset = nodeText.indexOf("@link", fenceStart + 3) + "@link".length
        val parenthesisOffset = nodeText.indexOf("@link(") + "@link".length
        val braceOffset = nodeText.indexOf("@link{") + "@link".length
        val completedOffset = nodeText.indexOf("@link[") + "@link".length

        assertTrue(fixture.completions(nodeUri, codeSpanOffset).isEmpty())
        assertTrue(fixture.completions(nodeUri, escapedOffset).isEmpty())
        assertTrue(fixture.completions(nodeUri, fencedOffset).isEmpty())
        assertTrue(fixture.completions(nodeUri, parenthesisOffset).isEmpty())
        assertTrue(fixture.completions(nodeUri, braceOffset).isEmpty())
        assertTrue(fixture.completions(nodeUri, completedOffset).isEmpty())
        assertTrue(fixture.completions(nodeTypeUri, nodeTypeText.length).isEmpty())
    }

    @Test
    fun `@link completion follows CommonMark code regions`() {
        val nodeUri = "file:///workspace/alice.md"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            > ```
            > @link
            > ```
            - ```
              @link
              ```
            ```
            ```not-a-closing-fence
            @link
            ```
            escaped \` before @link
            unmatched ` before @link
            visible @link
        """.trimIndent()
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/types/friendOf.md" to "---\nid: friendOf\nkind: RelType\n---",
                nodeUri to nodeText,
            ),
        )

        var searchStart = 0
        fun assertNoCompletion() {
            val linkStart = nodeText.indexOf("@link", searchStart)
            assertTrue(linkStart >= 0)
            assertTrue(fixture.completions(nodeUri, linkStart + "@link".length).isEmpty())
            searchStart = linkStart + "@link".length
        }
        fun assertCompletion() {
            val linkStart = nodeText.indexOf("@link", searchStart)
            assertTrue(linkStart >= 0)
            assertEquals(
                listOf("@link (friendOf)"),
                fixture.completions(nodeUri, linkStart + "@link".length).map { it.label },
            )
            searchStart = linkStart + "@link".length
        }

        repeat(3) { assertNoCompletion() }
        repeat(3) { assertCompletion() }
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
        val timelineText = "---\nid: CommonEra\nkind: Timeline\ntimecode:\n  type: number\n---"
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
                SourceDocument(timelineText, "/workspace/timelines/CommonEra.md"),
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
                    "file:///workspace/timelines/CommonEra.md",
                    "markdown",
                    1,
                    timelineText,
                ),
            ),
        )
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
        ).get().left.orEmpty()

        assertTrue(items.any { it.label == "name" }, items.joinToString())
        assertTrue(items.any { it.label == "birthDate" }, items.joinToString())
        assertEquals("name = \"\${1:value}\"", items.single { it.label == "name" }.insertText)
        assertEquals(
            "birthDate = { timeline = \${1:CommonEra}, value = \${2:0} }",
            items.single { it.label == "birthDate" }.insertText,
        )
        assertEquals(InsertTextFormat.Snippet, items.single { it.label == "birthDate" }.insertTextFormat)
    }

    @Test
    fun `props snippet completion replaces only the current token`() {
        val nodeTypeText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
            ---
        """.trimIndent()
        val propsLine = "@props{na, untouched = \"keep\"}"
        val nodeText = """
            ---
            id: alice
            kind: Node
            type: Person
            ---
            $propsLine
        """.trimIndent()
        val uri = "file:///workspace/alice.md"
        val server = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to nodeTypeText,
                uri to nodeText,
            ),
        ).server
        val line = nodeText.lines().lastIndex
        val cursor = propsLine.indexOf("na") + "na".length

        val item = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(uri), Position(line, cursor)),
        ).get().left.single { it.label == "name" }
        val edit = assertNotNull(item.textEdit?.left)

        assertEquals(Range(Position(line, propsLine.indexOf("na")), Position(line, cursor)), edit.range)
        assertEquals("name = \"\${1:value}\"", edit.newText)
        assertEquals(
            "@props{name = \"\${1:value}\", untouched = \"keep\"}",
            propsLine.replaceRange(edit.range.start.character, edit.range.end.character, edit.newText),
        )
    }

    @Test
    fun `yaml props snippet completion preserves the rest of the line`() {
        val nodeTypeText = """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
            ---
        """.trimIndent()
        val propsLine = "  na # keep"
        val nodeText = """
            ---
            id: bob
            kind: Node
            type: Person
            props:
            $propsLine
            ---
        """.trimIndent()
        val uri = "file:///workspace/bob.md"
        val server = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to nodeTypeText,
                uri to nodeText,
            ),
        ).server
        val line = nodeText.lines().indexOf(propsLine)
        val cursor = propsLine.indexOf("na") + "na".length

        val item = server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(uri), Position(line, cursor)),
        ).get().left.single { it.label == "name" }
        val edit = assertNotNull(item.textEdit?.left)

        assertEquals(Range(Position(line, propsLine.indexOf("na")), Position(line, cursor)), edit.range)
        assertEquals(
            "  name: \"\${1:value}\" # keep",
            propsLine.replaceRange(edit.range.start.character, edit.range.end.character, edit.newText),
        )
    }

    @Test
    fun `front matter props key completion inserts every supported value shape`() {
        val schema = mapOf(
            "name" to ResolvedPropSchema(type = PropType.string),
            "description" to ResolvedPropSchema(type = PropType.text),
            "score" to ResolvedPropSchema(type = PropType.number),
            "happenedAt" to ResolvedPropSchema(
                type = PropType.instant,
                timeline = TimelineSelector.Id("CommonEra"),
            ),
            "activeDuring" to ResolvedPropSchema(
                type = PropType.duration,
                timeline = TimelineSelector.Id("CommonEra"),
            ),
            "labels" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(type = PropType.string),
            ),
            "durations" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(type = PropType.duration, timeline = TimelineSelector.Id("CommonEra")),
            ),
            "matrix" to ResolvedPropSchema(
                type = PropType.array,
                items = ResolvedPropSchema(type = PropType.array, items = ResolvedPropSchema(type = PropType.number)),
            ),
        )
        val text = "---\nid: alice\nkind: Node\ntype: Person\nprops:\n  \n---"
        val items = FrontMatterCompletionResolver(
            text = text,
            offset = text.indexOf("  ") + 2,
            parsedDocument = NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
            nodeTypeIds = listOf("Person"),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
            nodePropsSchema = schema,
        ).resolve().orEmpty().associateBy { it.label }

        assertEquals("name: \"\${1:value}\"", items.getValue("name").insertText)
        assertEquals("description: \"\${1:text}\"", items.getValue("description").insertText)
        assertEquals("score: 0", items.getValue("score").insertText)
        assertEquals(
            "happenedAt: { timeline: \${1:CommonEra}, value: \${2:0} }",
            items.getValue("happenedAt").insertText,
        )
        assertEquals(
            "activeDuring: { timeline: \${1:CommonEra}, from: \${2:0}, to: \${3:0} }",
            items.getValue("activeDuring").insertText,
        )
        assertEquals("labels: [ \"\${1:value}\" ]", items.getValue("labels").insertText)
        assertEquals(
            "durations: [ { timeline: \${1:CommonEra}, from: \${2:0}, to: \${3:0} } ]",
            items.getValue("durations").insertText,
        )
        assertEquals("matrix: [ [ \${1:0} ] ]", items.getValue("matrix").insertText)
        assertEquals(InsertTextFormat.PlainText, items.getValue("score").insertTextFormat)
        assertTrue(items.values.filter { it.label != "score" }.all { it.insertTextFormat == InsertTextFormat.Snippet })

        val mediaText = "---\nid: image\nkind: Media\ntype: Image\nurl: image.png\nprops:\n  \n---"
        val mediaItems = FrontMatterCompletionResolver(
            text = mediaText,
            offset = mediaText.indexOf("  ") + 2,
            parsedDocument = NodeDocument(
                id = "image",
                type = "Image",
                sourcePath = "/tmp/image.md",
                documentKind = DocumentKind.Media,
            ),
            nodeTypeIds = listOf("Image"),
            relTypeIds = emptyList(),
            timelineIds = emptyList(),
            nodePropsSchema = mapOf("caption" to ResolvedPropSchema(type = PropType.text)),
        ).resolve().orEmpty()
        assertEquals("caption: \"\${1:text}\"", mediaItems.single { it.label == "caption" }.insertText)
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
    fun `reference diagnostics distinguish candidate counts and kinds`() {
        fun node(id: String, type: String, body: String = "") = """
            ---
            id: $id
            kind: Node
            type: $type
            ---
            $body
        """.trimIndent()

        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/ambiguous-a.md" to graphDocument("Ambiguous", "NodeType"),
                "file:///workspace/types/ambiguous-b.md" to graphDocument("Ambiguous", "NodeType"),
                "file:///workspace/types/wrong.md" to graphDocument("Wrong", "RelType"),
                "file:///workspace/types/mixed-node-type.md" to graphDocument("Mixed", "NodeType"),
                "file:///workspace/types/mixed-rel-type.md" to graphDocument("Mixed", "RelType"),
                "file:///workspace/types/resolved-mixed-node-type.md" to graphDocument("ResolvedMixed", "NodeType"),
                "file:///workspace/types/resolved-mixed-rel-type.md" to graphDocument("ResolvedMixed", "RelType"),
                "file:///workspace/types/person.md" to graphDocument("Person", "NodeType"),
                "file:///workspace/types/friend-of.md" to graphDocument("friendOf", "RelType"),
                "file:///workspace/unresolved.md" to node("unresolved", "Missing"),
                "file:///workspace/wrong.md" to node("wrong", "Wrong"),
                "file:///workspace/ambiguous.md" to node("ambiguous", "Ambiguous"),
                "file:///workspace/mixed.md" to node("mixed", "Person", "Hello @link{}[Mixed](Mixed friendOf)"),
                "file:///workspace/resolved-mixed.md" to node("resolvedMixed", "ResolvedMixed"),
                "file:///workspace/resolved.md" to node("resolved", "Person"),
            ),
        )

        val unresolved = fixture.diagnostics.getValue("file:///workspace/unresolved.md")
            .single { it.message == "Unknown NodeType: Missing" }
        assertEquals(DiagnosticSeverity.Error, unresolved.severity)
        assertEquals("ReferenceError", unresolved.code.left)
        assertEquals(Range(Position(3, 6), Position(3, 13)), unresolved.range)

        val wrong = fixture.diagnostics.getValue("file:///workspace/wrong.md")
            .single { it.message == "Expected NodeType but found RelType: Wrong" }
        assertEquals(Range(Position(3, 6), Position(3, 11)), wrong.range)

        val ambiguous = fixture.diagnostics.getValue("file:///workspace/ambiguous.md")
            .single { it.message == "Ambiguous NodeType reference: Ambiguous" }
        assertEquals(Range(Position(3, 6), Position(3, 15)), ambiguous.range)

        val mixed = fixture.diagnostics.getValue("file:///workspace/mixed.md")
            .single { it.message == "Expected Node but found NodeType, RelType: Mixed" }
        assertEquals(Range(Position(5, 21), Position(5, 26)), mixed.range)

        assertTrue(
            fixture.diagnostics.getValue("file:///workspace/resolved-mixed.md")
                .none { it.code?.left == "ReferenceError" && "ResolvedMixed" in it.message },
        )
        assertTrue(
            fixture.diagnostics.getValue("file:///workspace/resolved.md")
                .none { it.code?.left == "ReferenceError" && "Person" in it.message },
        )
    }

    @Test
    fun `quoted nested Timeline diagnostics use scalar interior ranges`() {
        val nodeUri = "file:///workspace/quoted-timelines.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/person.md" to graphDocument("Person", "NodeType"),
                "file:///workspace/types/quoted-wrong.md" to graphDocument("QuotedWrong", "NodeType"),
                "file:///workspace/timelines/quoted-ambiguous-a.md" to graphDocument("QuotedAmbiguous", "Timeline"),
                "file:///workspace/timelines/quoted-ambiguous-b.md" to graphDocument("QuotedAmbiguous", "Timeline"),
                nodeUri to """
                    ---
                    id: quoted
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: "QuotedWrong" # wrong kind
                      - timeline: 'QuotedAmbiguous' # duplicate
                    props:
                      label: "QuotedWrong"
                    ---
                """.trimIndent(),
            ),
        )

        val wrong = fixture.diagnostics.getValue(nodeUri)
            .single { it.message == "Expected Timeline but found NodeType: QuotedWrong" }
        assertEquals(Range(Position(5, 15), Position(5, 26)), wrong.range)
        assertEquals(DiagnosticSeverity.Error, wrong.severity)
        assertEquals("ReferenceError", wrong.code.left)

        val ambiguous = fixture.diagnostics.getValue(nodeUri)
            .single { it.message == "Ambiguous Timeline reference: QuotedAmbiguous" }
        assertEquals(Range(Position(6, 15), Position(6, 30)), ambiguous.range)
        assertTrue(fixture.actions(nodeUri, ambiguous).isEmpty())
        assertTrue(
            fixture.diagnostics.getValue(nodeUri)
                .none { it.range.start.line == 8 && it.code?.left == "ReferenceError" },
        )
    }

    @Test
    fun `yaml Timeline comments and structural paths stay consistent with compiler`() {
        val resolvedUri = "file:///workspace/commented.md"
        val timelinePropsUri = "file:///workspace/timeline-props.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/person.md" to graphDocument("Person", "NodeType"),
                "file:///workspace/timelines/t.md" to graphDocument("T", "Timeline"),
                resolvedUri to """
                    ---
                    id: commented
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: T # era
                    ---
                """.trimIndent(),
                timelinePropsUri to """
                    ---
                    id: Labels
                    kind: Timeline
                    props:
                      from: note
                      to: other
                      timeline: label
                    ---
                """.trimIndent(),
            ),
        )

        assertTrue(
            fixture.diagnostics.getValue(resolvedUri).none { it.code?.left == "ReferenceError" },
            fixture.diagnostics.getValue(resolvedUri).joinToString(),
        )
        assertTrue(
            fixture.diagnostics.getValue(timelinePropsUri).none { it.code?.left == "ReferenceError" },
            fixture.diagnostics.getValue(timelinePropsUri).joinToString(),
        )
    }

    @Test
    fun `mapped selector forms report exact candidate diagnostics`() {
        val schemaUri = "file:///workspace/types/mapped-selectors.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/wrong-mapped.md" to graphDocument("WrongMapped", "NodeType"),
                "file:///workspace/timelines/ambiguous-mapped-a.md" to graphDocument("AmbiguousMapped", "Timeline"),
                "file:///workspace/timelines/ambiguous-mapped-b.md" to graphDocument("AmbiguousMapped", "Timeline"),
                schemaUri to """
                    ---
                    id: Event
                    kind: NodeType
                    props:
                      canonical:
                        type: instant
                        timeline:
                          - id: WrongMapped
                            mapped: true
                      legacy:
                        type: instant
                        timeline:
                          - AmbiguousMapped:
                              mapped: true
                    ---
                """.trimIndent(),
            ),
        )

        val diagnostics = fixture.diagnostics.getValue(schemaUri)
        val wrong = diagnostics.single {
            it.message == "Expected Timeline but found NodeType: WrongMapped"
        }
        assertEquals(Range(Position(7, 12), Position(7, 23)), wrong.range)

        val ambiguous = diagnostics.single {
            it.message == "Ambiguous Timeline reference: AmbiguousMapped"
        }
        assertEquals(Range(Position(12, 8), Position(12, 23)), ambiguous.range)
        assertTrue(fixture.actions(schemaUri, ambiguous).isEmpty())
        assertEquals(
            2,
            diagnostics.count { it.code?.left == "SchemaError" && "mapped selectors were removed" in it.message },
            diagnostics.joinToString(),
        )
    }

    @Test
    fun `mapped selectors require boolean flags and preserve punctuated ids`() {
        val schemaUri = "file:///workspace/types/mapped-selector-boundaries.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/third-age.md" to graphDocument("Third.Age", "NodeType"),
                "file:///workspace/types/missing.md" to graphDocument("MissingMapped", "NodeType"),
                "file:///workspace/types/legacy.md" to graphDocument("InvalidLegacy", "NodeType"),
                "file:///workspace/timelines/leading-a.md" to graphDocument("_Leading", "Timeline"),
                "file:///workspace/timelines/leading-b.md" to graphDocument("_Leading", "Timeline"),
                schemaUri to """
                    ---
                    id: Boundary
                    kind: NodeType
                    props:
                      punctuation:
                        type: instant
                        timeline:
                          - Third.Age:
                              mapped: false
                      leading:
                        type: instant
                        timeline:
                          - id: _Leading
                            mapped: true
                      missing:
                        type: instant
                        timeline:
                          - id: MissingMapped
                      nonBoolean:
                        type: instant
                        timeline:
                          - InvalidLegacy:
                              mapped: nope
                    ---
                """.trimIndent(),
            ),
        )

        val diagnostics = fixture.diagnostics.getValue(schemaUri)
        val punctuation = diagnostics.single {
            it.message == "Expected Timeline but found NodeType: Third.Age"
        }
        assertEquals(Range(Position(7, 8), Position(7, 17)), punctuation.range)
        val leading = diagnostics.single {
            it.message == "Ambiguous Timeline reference: _Leading"
        }
        assertEquals(Range(Position(12, 12), Position(12, 20)), leading.range)
        assertEquals(
            4,
            diagnostics.count { it.code?.left == "SchemaError" && "mapped selectors were removed" in it.message },
            diagnostics.joinToString(),
        )
    }

    @Test
    fun `singular mapped selectors require direct sibling flags`() {
        val schemaUri = "file:///workspace/types/singular-mapped-selectors.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/singular-wrong.md" to graphDocument("SingularWrong", "NodeType"),
                "file:///workspace/types/deep-wrong.md" to graphDocument("DeepWrong", "NodeType"),
                "file:///workspace/types/deep-legacy.md" to graphDocument("DeepLegacy", "NodeType"),
                "file:///workspace/timelines/singular-a.md" to graphDocument("Singular.Ambiguous", "Timeline"),
                "file:///workspace/timelines/singular-b.md" to graphDocument("Singular.Ambiguous", "Timeline"),
                schemaUri to """
                    ---
                    id: SingularSelectors
                    kind: NodeType
                    props:
                      canonical:
                        type: instant
                        timeline:
                          id: SingularWrong
                          mapped: true
                      legacy:
                        type: instant
                        timeline:
                          Singular.Ambiguous:
                            mapped: false
                      deepCanonical:
                        type: instant
                        timeline:
                          id: DeepWrong
                          nested:
                            mapped: true
                      deepLegacy:
                        type: instant
                        timeline:
                          DeepLegacy:
                            nested:
                              mapped: false
                    ---
                """.trimIndent(),
            ),
        )

        val diagnostics = fixture.diagnostics.getValue(schemaUri)
        assertEquals(
            Range(Position(7, 10), Position(7, 23)),
            diagnostics.single {
                it.message == "Expected Timeline but found NodeType: SingularWrong"
            }.range,
        )
        val ambiguous = diagnostics.single {
            it.message == "Ambiguous Timeline reference: Singular.Ambiguous"
        }
        assertEquals(Range(Position(12, 6), Position(12, 24)), ambiguous.range)
        assertTrue(fixture.actions(schemaUri, ambiguous).isEmpty())
        assertEquals(
            4,
            diagnostics.count { it.code?.left == "SchemaError" && "mapped selectors were removed" in it.message },
            diagnostics.joinToString(),
        )
    }

    @Test
    fun `legacy selector payload ids do not become canonical references`() {
        val schemaUri = "file:///workspace/types/overlapping-selectors.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/timelines/nested-a.md" to graphDocument("Nested", "Timeline"),
                "file:///workspace/timelines/nested-b.md" to graphDocument("Nested", "Timeline"),
                "file:///workspace/timelines/listed.md" to graphDocument("Listed", "Timeline"),
                "file:///workspace/types/spurious.md" to graphDocument("Spurious", "NodeType"),
                "file:///workspace/types/also-spurious.md" to graphDocument("AlsoSpurious", "NodeType"),
                schemaUri to """
                    ---
                    id: Overlapping
                    kind: NodeType
                    props:
                      singular:
                        type: instant
                        timeline:
                          Nested:
                            id: Spurious
                            mapped: true
                      listed:
                        type: instant
                        timeline:
                          - Listed:
                              id: AlsoSpurious
                              mapped: false
                    ---
                """.trimIndent(),
            ),
        )

        val diagnostics = fixture.diagnostics.getValue(schemaUri)
        val nested = diagnostics.single { it.message == "Ambiguous Timeline reference: Nested" }
        assertEquals(Range(Position(7, 6), Position(7, 12)), nested.range)
        assertTrue(fixture.actions(schemaUri, nested).isEmpty())
        assertTrue(
            diagnostics.none {
                it.code?.left == "ReferenceError" &&
                    (it.message.contains("Spurious") || it.message.contains("AlsoSpurious"))
            },
            diagnostics.joinToString(),
        )
    }

    @Test
    fun `numeric duration endpoints produce exact Timeline diagnostics`() {
        val nodeUri = "file:///workspace/numeric-duration.md"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/wrong-numeric.md" to graphDocument("WrongNumeric", "NodeType"),
                "file:///workspace/types/wrong-nested.md" to graphDocument("WrongNested", "NodeType"),
                "file:///workspace/timelines/numeric-a.md" to graphDocument("AmbiguousNumeric", "Timeline"),
                "file:///workspace/timelines/numeric-b.md" to graphDocument("AmbiguousNumeric", "Timeline"),
                "file:///workspace/timelines/base.md" to graphDocument("Base", "Timeline"),
                "file:///workspace/types/event.md" to """
                    ---
                    id: Event
                    kind: NodeType
                    props:
                      integer:
                        type: duration
                      decimal:
                        type: duration
                      nested:
                        type: duration
                    ---
                """.trimIndent(),
                nodeUri to """
                    ---
                    id: event
                    kind: Node
                    type: Event
                    props:
                      integer:
                        timeline: WrongNumeric
                        from: 1
                      decimal:
                        timeline: AmbiguousNumeric
                        to: 1.5
                      nested:
                        timeline: Base
                        from:
                          timeline: WrongNested
                          timecode: 2
                    ---
                """.trimIndent(),
            ),
        )

        val diagnostics = fixture.diagnostics.getValue(nodeUri)
        assertEquals(
            Range(Position(6, 14), Position(6, 26)),
            diagnostics.single { it.message == "Expected Timeline but found NodeType: WrongNumeric" }.range,
        )
        assertEquals(
            Range(Position(9, 14), Position(9, 30)),
            diagnostics.single { it.message == "Ambiguous Timeline reference: AmbiguousNumeric" }.range,
        )
        assertEquals(
            Range(Position(14, 16), Position(14, 27)),
            diagnostics.single { it.message == "Expected Timeline but found NodeType: WrongNested" }.range,
        )
    }

    @Test
    fun `invalid id warning highlights only the id value`() {
        val uri = "file:///workspace/invalid-id.md"
        val escapedUri = "file:///workspace/escaped-invalid-id.md"
        val hashUri = "file:///workspace/hash-invalid-id.md"
        val whitespaceRelTypeUri = "file:///workspace/whitespace-rel-type-id.md"
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
                whitespaceRelTypeUri to """
                    ---
                    id: "bad id"
                    kind: RelType
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

        val whitespaceRelTypeDiagnostic = fixture.diagnostics.getValue(whitespaceRelTypeUri).single {
            it.message == "RelType id MUST NOT contain whitespace"
        }
        assertEquals(DiagnosticSeverity.Error, whitespaceRelTypeDiagnostic.severity)
        assertEquals(Position(1, 5), whitespaceRelTypeDiagnostic.range.start)
        assertEquals(Position(1, 11), whitespaceRelTypeDiagnostic.range.end)
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
    fun `deleting a watched file clears its published diagnostics`() {
        val root = Files.createTempDirectory("graphmd-lsp-delete-diagnostics")
        try {
            val file = root.resolve("timeline.md")
            val uri = file.toUri().toString()
            Files.writeString(file, graphDocument("INVALID ID@", "Timeline"))
            val client = RecordingLanguageClient()
            val server = initializedServer(root, client)

            assertTrue(client.latest(uri).any { it.message == invalidIdWarning })
            client.notifications.clear()

            Files.delete(file)
            server.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Deleted))),
            )

            assertEquals(1, client.notifications.count { it.uri == uri })
            assertTrue(client.notifications.single { it.uri == uri }.diagnostics.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleting a clean watched file publishes one empty clear`() {
        val root = Files.createTempDirectory("graphmd-lsp-delete-clean")
        try {
            val file = root.resolve("timeline.md")
            val uri = file.toUri().toString()
            Files.writeString(file, graphDocument("timeline", "Timeline"))
            val client = RecordingLanguageClient()
            val server = initializedServer(root, client)

            assertTrue(client.latest(uri).isEmpty())
            client.notifications.clear()

            Files.delete(file)
            server.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Deleted))),
            )
            server.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Deleted))),
            )

            assertEquals(1, client.notifications.count { it.uri == uri })
            assertTrue(client.notifications.single { it.uri == uri }.diagnostics.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `deleting an untracked non-markdown file does not publish diagnostics`() {
        val root = Files.createTempDirectory("graphmd-lsp-delete-non-markdown")
        try {
            val file = root.resolve("notes.txt")
            val uri = file.toUri().toString()
            Files.writeString(file, "not markdown")
            val client = RecordingLanguageClient()
            val server = initializedServer(root, client)
            client.notifications.clear()

            Files.delete(file)
            server.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Deleted))),
            )

            assertTrue(client.notifications.none { it.uri == uri })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `disk deletion keeps open overlay diagnostics and recreate publishes diagnostics again`() {
        val root = Files.createTempDirectory("graphmd-lsp-delete-open-recreate")
        try {
            val file = root.resolve("timeline.md")
            val uri = file.toUri().toString()
            Files.writeString(file, graphDocument("timeline", "Timeline"))
            val client = RecordingLanguageClient()
            val server = initializedServer(root, client)
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(
                    TextDocumentItem(uri, "markdown", 1, graphDocument("INVALID ID@", "Timeline")),
                ),
            )
            assertTrue(client.latest(uri).any { it.message == invalidIdWarning })
            client.notifications.clear()

            Files.delete(file)
            server.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Deleted))),
            )
            assertTrue(client.notifications.single { it.uri == uri }.diagnostics.any {
                it.message == invalidIdWarning
            })

            server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            assertTrue(client.latest(uri).isEmpty())
            client.notifications.clear()

            Files.writeString(file, graphDocument("INVALID ID@", "Timeline"))
            server.workspaceService.didChangeWatchedFiles(
                DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Created))),
            )

            assertTrue(client.notifications.single { it.uri == uri }.diagnostics.any {
                it.message == invalidIdWarning
            })
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
            coo
            ---
        """.trimIndent()
        val topLevelItems = FrontMatterCompletionResolver(
            text = topLevelText,
            offset = topLevelText.indexOf("coo") + 3,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("coordinate" in topLevelItems)

        val timecodeTopLevelText = """
            ---
            id: t
            kind: Timeline
            maps
            ---
        """.trimIndent()
        val timecodeTopLevelItems = FrontMatterCompletionResolver(
            text = timecodeTopLevelText,
            offset = timecodeTopLevelText.indexOf("maps") + 4,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("mapsTo" in timecodeTopLevelItems)

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
            coordinate: nu
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
            mapsTo:
              - kind: alignment
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

        val precisionText = """
            ---
            id: t
            kind: Timeline
            mapsTo:
              - timeline: CommonEra
                precision:
                  kind: ap
            ---
        """.trimIndent()
        val precisionItems = FrontMatterCompletionResolver(
            text = precisionText,
            offset = precisionText.lastIndexOf("ap") + 2,
            parsedDocument = null,
            nodeTypeIds = emptyList(),
            relTypeIds = emptyList(),
            timelineIds = listOf("CommonEra"),
        ).resolve()?.map { it.label }.orEmpty()
        assertTrue("approximate" in precisionItems)

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

    @Test
    fun `property schema completions require a property key`() {
        fun labelsAtCursor(
            source: String,
            parsedDocument: GraphDocument?,
            nodePropsSchema: Map<String, ResolvedPropSchema> = emptyMap(),
        ): List<String> {
            val offset = source.indexOf("<cursor>")
            assertTrue(offset >= 0)
            val text = source.replace("<cursor>", "")
            return FrontMatterCompletionResolver(
                text = text,
                offset = offset,
                parsedDocument = parsedDocument,
                nodeTypeIds = emptyList(),
                relTypeIds = emptyList(),
                timelineIds = listOf("CommonEra"),
                nodePropsSchema = nodePropsSchema,
            ).resolve()?.map { it.label }.orEmpty()
        }

        val nodeType = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md")
        val relType = RelTypeDocument(id = "Knows", sourcePath = "/tmp/knows.md")

        assertEquals(
            emptyList(),
            labelsAtCursor("---\nid: Person\nkind: NodeType\nprops:\n  <cursor>\n---", nodeType),
        )
        assertEquals(
            emptyList(),
            labelsAtCursor("---\nid: Person\nkind: NodeType\nprops:\n  req<cursor>\n---", nodeType),
        )
        assertEquals(
            listOf("type", "required"),
            labelsAtCursor("---\nid: Person\nkind: NodeType\nprops:\n  name:\n    <cursor>\n---", nodeType),
        )
        assertEquals(
            listOf("true", "false"),
            labelsAtCursor("---\nid: Person\nkind: NodeType\nprops:\n  name:\n    required: <cursor>\n---", nodeType),
        )
        assertEquals(
            listOf("type", "required"),
            labelsAtCursor("---\nid: Person\nkind: NodeType\nprops:\n  required: <cursor>\n---", nodeType),
        )
        assertEquals(
            listOf("type", "required"),
            labelsAtCursor(
                "---\nid: Person\nkind: NodeType\nprops:\n  tags:\n    type: array\n    items:\n      <cursor>\n---",
                nodeType,
            ),
        )
        assertEquals(
            listOf("true", "false"),
            labelsAtCursor(
                "---\nid: Person\nkind: NodeType\nprops:\n  tags:\n    type: array\n    items:\n      required: <cursor>\n---",
                nodeType,
            ),
        )
        assertEquals(
            listOf("required"),
            labelsAtCursor(
                "---\r\nid: Person\r\nkind: NodeType\r\nprops:\r\n  name:\r\n    type: string\r\n\r\n    # schema comment\r\n    <cursor>\r\n---",
                nodeType,
            ),
        )
        assertEquals(
            emptyList(),
            labelsAtCursor(
                "---\nid: Person\nkind: NodeType\nprops:\n  name:\n    type: string\n  req<cursor>\n---",
                nodeType,
            ),
        )
        assertEquals(
            emptyList(),
            labelsAtCursor(
                "---\nid: Person\nkind: NodeType\nmetadata:\n  props:\n    req<cursor>\n---",
                nodeType,
            ),
        )
        assertEquals(
            listOf("type", "required"),
            labelsAtCursor("---\nid: Knows\nkind: RelType\nprops:\n  since:\n    <cursor>\n---", relType),
        )
        assertEquals(
            listOf("type", "required"),
            labelsAtCursor("---\nid: Draft\nkind: NodeType\nprops:\n  name:\n    <cursor>\n---", null),
        )
        assertEquals(
            listOf("type", "required"),
            labelsAtCursor("---\nid: DraftRel\nkind: RelType\nprops:\n  weight:\n    <cursor>\n---", null),
        )

        val instanceCases = listOf(
            Triple(
                NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
                mapOf("foo" to ResolvedPropSchema(type = PropType.string)),
                "foo",
            ),
            Triple(
                NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
                mapOf("foo" to ResolvedPropSchema(type = PropType.string)),
                "unknown",
            ),
            Triple(
                NodeDocument(id = "alice", type = "Person", sourcePath = "/tmp/alice.md"),
                emptyMap(),
                "foo",
            ),
            Triple(
                NodeDocument(
                    id = "image",
                    type = "Image",
                    sourcePath = "/tmp/image.md",
                    documentKind = DocumentKind.Media,
                ),
                mapOf("foo" to ResolvedPropSchema(type = PropType.string)),
                "foo",
            ),
            Triple(
                NodeDocument(
                    id = "image",
                    type = "Image",
                    sourcePath = "/tmp/image.md",
                    documentKind = DocumentKind.Media,
                ),
                mapOf("foo" to ResolvedPropSchema(type = PropType.string)),
                "unknown",
            ),
            Triple(
                NodeDocument(
                    id = "image",
                    type = "Image",
                    sourcePath = "/tmp/image.md",
                    documentKind = DocumentKind.Media,
                ),
                emptyMap(),
                "foo",
            ),
        )
        instanceCases.forEach { (document, schema, property) ->
            val frontMatter = "---\nid: ${document.id}\nkind: ${document.kind}\ntype: ${document.type}\nprops:\n  $property:"
            assertEquals(
                emptyList(),
                labelsAtCursor("$frontMatter\n    <cursor>\n---", document, schema),
            )
            assertEquals(
                emptyList(),
                labelsAtCursor("$frontMatter\n    req<cursor>\n---", document, schema),
            )
            assertEquals(
                emptyList(),
                labelsAtCursor("$frontMatter\n    type: <cursor>\n---", document, schema),
            )
            assertEquals(
                emptyList(),
                labelsAtCursor("$frontMatter\n    required: <cursor>\n---", document, schema),
            )
            assertEquals(
                emptyList(),
                labelsAtCursor("$frontMatter\n    timeline: <cursor>\n---", document, schema),
            )
        }

    }

    @Test
    fun `property schema field completion inserts safe starter values`() {
        fun complete(source: String): List<CompletionEntry> {
            val offset = source.indexOf("<cursor>")
            assertTrue(offset >= 0)
            val text = source.replace("<cursor>", "")
            return FrontMatterCompletionResolver(
                text = text,
                offset = offset,
                parsedDocument = NodeTypeDocument(id = "Person", sourcePath = "/tmp/person.md"),
                nodeTypeIds = emptyList(),
                relTypeIds = emptyList(),
                timelineIds = listOf("CommonEra"),
            ).resolve().orEmpty()
        }

        val emptySchema = complete("---\nid: Person\nkind: NodeType\nprops:\n  name:\n    <cursor>\n---")
        assertEquals("type: \${1:string}", emptySchema.single { it.label == "type" }.insertText)
        assertEquals("required: \${1:false}", emptySchema.single { it.label == "required" }.insertText)
        assertEquals(InsertTextFormat.Snippet, emptySchema.single { it.label == "type" }.insertTextFormat)

        val arraySchema = complete(
            "---\nid: Person\nkind: NodeType\nprops:\n  tags:\n    type: array\n    it<cursor>\n---",
        )
        assertEquals("items: \${1:string}", arraySchema.single { it.label == "items" }.insertText)
        assertEquals(InsertTextFormat.Snippet, arraySchema.single { it.label == "items" }.insertTextFormat)
    }

    @Test
    fun `front matter type completion distinguishes document type from property schema type`() {
        fun complete(markedText: String): List<CompletionEntry> {
            val marker = "<cursor>"
            val offset = markedText.indexOf(marker)
            assertTrue(offset >= 0)
            return FrontMatterCompletionResolver(
                text = markedText.replace(marker, ""),
                offset = offset,
                parsedDocument = null,
                nodeTypeIds = listOf("Company", "Person"),
                relTypeIds = listOf("worksAt"),
                timelineIds = listOf("CommonEra"),
            ).resolve().orEmpty()
        }

        listOf(
            "---\nid: alice\nkind: Node\ntype: <cursor>\n---",
            "---\nid: alice\nkind: Node   \ntype: <cursor>\n---",
            "---\nid: alice\nkind: Node # node document\ntype: <cursor>\n---",
            "---\nid: alice\nkind: \"Node\" # node document\ntype: <cursor>\n---",
            "---\nid: alice\nkind: 'Node' # node document\ntype: <cursor>\n---",
            "---\nid: alice\nkind: Node\ntype:    <cursor>\n---",
            "---\nid: alice\nkind: Node\ntype: \"<cursor>\"\n---",
            "---\nid: image\nkind: Media\ntype: <cursor>\nurl: image.png\n---",
            "---\nid: image\nkind: Media   # media document\ntype: <cursor>\nurl: image.png\n---",
            "---\nid: image\nkind: \"Media\" # media document\ntype: <cursor>\nurl: image.png\n---",
            "---\nid: alice\nkind: Node\nprops:\n  score:\n    type: number\n\n# reset to a top-level sibling\ntype: <cursor>\n---",
        ).forEach { text ->
            assertEquals(listOf("Company", "Person"), complete(text).map { it.label }, text)
        }

        val partial = complete("---\nid: alice\nkind: Node\ntype: Per<cursor>\n---")
        assertEquals(listOf("Person"), partial.map { it.label })
        assertEquals(CompletionItemKind.Reference, partial.single().kind)
        assertEquals("Person", partial.single().insertText)
        assertEquals("NodeType", partial.single().detail)
        assertEquals(
            listOf("Person"),
            complete("---\nid: alice\nkind: Node\ntype: 'Per<cursor>'\n---").map { it.label },
        )
        listOf(
            "---\nid: alice\nkind: Node\ntype: Per   <cursor>\n---",
            "---\nid: alice\nkind: Node\ntype: Per # partial type<cursor>\n---",
            "---\nid: alice\nkind: Node\ntype: \"Per\" # partial type<cursor>\n---",
        ).forEach { text ->
            assertEquals(listOf("Person"), complete(text).map { it.label }, text)
        }
        assertTrue(complete("---\nid: alice\nkind: \"Node#draft\"\ntype: <cursor>\n---").isEmpty())
        assertTrue(complete("---\nid: alice\nkind: \"No\\\"de\" # escaped quote\ntype: <cursor>\n---").isEmpty())
        assertTrue(complete("---\nid: alice\nkind: 'No''de' # escaped quote\ntype: <cursor>\n---").isEmpty())

        val nodeTypeProp = complete(
            """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                # scalar property schema
                type: <cursor>
            ---
            """.trimIndent(),
        )
        assertEquals(listOf("number", "string", "text", "instant", "duration", "array"), nodeTypeProp.map { it.label })
        assertTrue(nodeTypeProp.none { it.label in setOf("Company", "Person") })

        val relTypeProp = complete(
            """
            ---
            id: worksAt
            kind: RelType
            from: [Person]
            to: [Company]
            props:
              since:
                type: st<cursor>
            ---
            """.trimIndent(),
        )
        assertEquals(listOf("string"), relTypeProp.map { it.label })

        assertTrue(complete("---\nid: Person\nkind: NodeType\ntype: <cursor>\n---").isEmpty())
        assertTrue(complete("---\nid: worksAt\nkind: RelType\ntype: <cursor>\n---").isEmpty())
        assertEquals(
            listOf("Person"),
            complete("---\nid: worksAt\nkind: RelType\nfrom: Per<cursor>\nto: [Company]\n---").map { it.label },
        )
        assertEquals(
            listOf("Person"),
            complete("---\nid: worksAt\nkind: RelType\nfrom:\n  - Per<cursor>\nto: [Company]\n---").map { it.label },
        )
    }

    @Test
    fun `front matter type completion handles crlf offsets`() {
        val markedText = "---\r\nid: alice\r\nkind: Node # document kind\r\n\r\n# type follows\r\ntype: Per<cursor>\r\n---"
        val marker = "<cursor>"
        val offset = markedText.indexOf(marker)
        val items = FrontMatterCompletionResolver(
            text = markedText.replace(marker, ""),
            offset = offset,
            parsedDocument = null,
            nodeTypeIds = listOf("Company", "Person"),
            relTypeIds = emptyList(),
            timelineIds = emptyList(),
        ).resolve().orEmpty()

        assertEquals(listOf("Person"), items.map { it.label })
    }

    @Test
    fun `server completes unfinished node type with workspace node types only`() {
        val uri = "file:///workspace/alice.md"
        val text = "---\nid: alice\nkind: Node # node document\ntype: \n---"
        val fixture = serverFixture(
            mapOf(
                "file:///workspace/types/Person.md" to "---\nid: Person\nkind: NodeType\n---",
                "file:///workspace/types/Company.md" to "---\nid: Company\nkind: NodeType\n---",
                "file:///workspace/types/worksAt.md" to "---\nid: worksAt\nkind: RelType\n---",
                uri to text,
            ),
        )

        val items = fixture.server.textDocumentService.completion(
            CompletionParams(TextDocumentIdentifier(uri), Position(3, "type: ".length)),
        ).get().left.orEmpty()

        assertEquals(listOf("Company", "Person"), items.map { it.label })
        assertTrue(items.all { it.kind == CompletionItemKind.Reference })
        assertEquals(listOf("Company", "Person"), items.map { it.insertText })
        assertTrue(items.all { it.detail == "NodeType" })
        assertEquals(listOf("1-Company", "1-Person"), items.map { it.sortText })
        assertTrue(items.all { it.textEdit == null })
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

    private fun positionAt(text: String, offset: Int): Position {
        val line = text.substring(0, offset).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
        return Position(line, offset - lineStart)
    }
    private fun initializedServer(root: Path, client: RecordingLanguageClient): GraphMdLanguageServer =
        GraphMdLanguageServer().also { server ->
            server.connect(client)
            server.initialize(
                InitializeParams().apply {
                    workspaceFolders = listOf(WorkspaceFolder(root.toUri().toString(), "workspace"))
                },
            ).get()
            server.initialized(InitializedParams())
        }

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
            var line = 0
            var lineStart = 0
            var index = 0
            while (index < offset) {
                when (text[index]) {
                    '\r' -> {
                        if (text.getOrNull(index + 1) == '\n' && index + 1 < offset) index++
                        line++
                        lineStart = index + 1
                    }
                    '\n' -> {
                        line++
                        lineStart = index + 1
                    }
                }
                index++
            }
            return Position(line, offset - lineStart)
        }

        fun actions(uri: String, message: String): List<CodeAction> {
            val diagnostic = diagnostics.getValue(uri).firstOrNull { it.message == message }
                ?: error("Missing diagnostic '$message'; found: ${diagnostics.getValue(uri).joinToString { it.message }}")
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
