package dev.usbharu.graphmd.lsp

import dev.usbharu.graphmd.core.*
import dev.usbharu.graphmd.core.model.Diagnostic
import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.GraphSearchEngine
import dev.usbharu.graphmd.query.gmql.GmqlExecutionOptions
import dev.usbharu.graphmd.query.gmql.GmqlExecutionProfile
import dev.usbharu.graphmd.query.gmql.GmqlValue
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

private val GRAPH_MD_ID_REGEX = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
private val COMPLETION_REPLACEMENT_TOKEN_REGEX =
    Regex("""-?\d+(?:\.\d*)?|[A-Za-z_][A-Za-z0-9_]*(?:[.:-][A-Za-z0-9_]+)*""")
private const val CREATE_DEFINITION_COMMAND = "graphmd.createDefinition"

class GraphMdLanguageServer : LanguageServer, LanguageClientAware, GraphMdSearchService {
    private val workspaceIndex = GraphMdWorkspaceIndex()
    private val textDocumentService = GraphMdTextDocumentService(this, workspaceIndex)
    private val workspaceService = GraphMdWorkspaceService(this, workspaceIndex)
    private var client: LanguageClient? = null
    private var shutdownRequested = false
    private val publishedDiagnosticUris = mutableSetOf<String>()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val roots = params.workspaceFolders.orEmpty().map { Paths.get(URI.create(it.uri)) }
        workspaceIndex.setWorkspaceRoots(roots)
        workspaceIndex.loadWorkspace()
        return CompletableFuture.completedFuture(
            InitializeResult(
                ServerCapabilities().apply {
                    textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
                    completionProvider = CompletionOptions().apply {
                        resolveProvider = false
                        triggerCharacters = listOf(":", "-", " ", "(", "{", ",", "=", "@")
                    }
                    definitionProvider = Either.forLeft(true)
                    referencesProvider = Either.forLeft(true)
                    hoverProvider = Either.forLeft(true)
                    renameProvider = Either.forRight(RenameOptions(true))
                    codeActionProvider = Either.forRight(
                        CodeActionOptions(listOf(CodeActionKind.QuickFix)).apply {
                            resolveProvider = false
                        },
                    )
                },
            ),
        )
    }

    override fun initialized(params: InitializedParams) {
        publishDiagnostics()
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdownRequested = true
        return CompletableFuture.completedFuture(Any())
    }

    override fun exit() {
        kotlin.system.exitProcess(if (shutdownRequested) 0 else 1)
    }

    override fun getTextDocumentService(): TextDocumentService = textDocumentService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
    }

    override fun search(params: GraphMdSearchParams): CompletableFuture<GraphMdSearchResponse> =
        CompletableFuture.supplyAsync { workspaceIndex.search(params) }

    override fun searchMetadata(): CompletableFuture<GraphMdSearchMetadata> =
        CompletableFuture.supplyAsync { workspaceIndex.searchMetadata() }

    @Synchronized
    fun publishDiagnostics() {
        val client = client ?: return
        val diagnosticsByUri = workspaceIndex.diagnosticsByUri()
        diagnosticsByUri.forEach { (uri, diagnostics) ->
            client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
        }
        (publishedDiagnosticUris - diagnosticsByUri.keys).forEach { uri ->
            client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
        }
        publishedDiagnosticUris.clear()
        publishedDiagnosticUris += diagnosticsByUri.keys
    }

    fun languageClient(): LanguageClient? = client
}

private class GraphMdTextDocumentService(
    private val server: GraphMdLanguageServer,
    private val index: GraphMdWorkspaceIndex,
) : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) {
        index.open(params.textDocument.uri, params.textDocument.text)
        server.publishDiagnostics()
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val latest = params.contentChanges.lastOrNull()?.text ?: return
        index.upsert(params.textDocument.uri, latest)
        server.publishDiagnostics()
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        index.close(params.textDocument.uri)
        server.publishDiagnostics()
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        server.publishDiagnostics()
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        val items = index.completions(params.textDocument.uri, params.position)
        return CompletableFuture.completedFuture(Either.forLeft(items.toMutableList()))
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        val locations = index.definitions(params.textDocument.uri, params.position)
        return CompletableFuture.completedFuture(Either.forLeft(locations.toMutableList()))
    }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
        return CompletableFuture.completedFuture(index.references(params.textDocument.uri, params.position).toMutableList())
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        return CompletableFuture.completedFuture(index.hover(params.textDocument.uri, params.position))
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        return CompletableFuture.completedFuture(index.rename(params.textDocument.uri, params.position, params.newName))
    }

    override fun prepareRename(
        params: PrepareRenameParams,
    ): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?> {
        return CompletableFuture.completedFuture(
            index.prepareRename(params.textDocument.uri, params.position)?.let {
                Either3.forSecond<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>(it)
            },
        )
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<List<Either<Command, CodeAction>>> {
        val actions = index.codeActions(params.textDocument.uri, params.context.diagnostics)
        return CompletableFuture.completedFuture(actions.map { Either.forRight(it) })
    }
}

private class GraphMdWorkspaceService(
    private val server: GraphMdLanguageServer,
    private val index: GraphMdWorkspaceIndex,
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        params.changes.forEach(index::updateFromDisk)
        server.publishDiagnostics()
    }
}

internal class GraphMdWorkspaceIndex(
    private val compileSources: (List<SourceDocument>) -> GraphCompilationResult = GraphCompiler()::compileSources,
) {
    private data class WorkspaceSnapshot(
        val generation: Long,
        val documents: List<IndexedDocument>,
    )

    private data class CompiledWorkspace(
        val generation: Long,
        val documents: List<IndexedDocument>,
        val compilation: GraphCompilationResult,
    )

    private data class CachedSearchEngine(
        val generation: Long,
        val engine: GraphSearchEngine,
    )

    private val analyzer = GraphDocumentAnalyzer()
    private var roots: List<Path> = emptyList()
    private val documents = linkedMapOf<String, IndexedDocument>()
    private val openDocuments = mutableSetOf<String>()
    private var workspaceGeneration = 0L
    private var compiledCache: CompiledWorkspace? = null
    private var searchEngineCache: CachedSearchEngine? = null

    fun setWorkspaceRoots(roots: List<Path>) {
        synchronized(this) {
            this.roots = roots
        }
    }

    fun loadWorkspace() {
        val loadedDocuments = linkedMapOf<String, IndexedDocument>()
        val workspaceRoots = synchronized(this) { roots.toList() }
        workspaceRoots.forEach { root ->
            if (!Files.exists(root)) return@forEach
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.extension == "md" }
                    .filter { path ->
                        val normalized = path.toString().replace('\\', '/')
                        !normalized.contains("/node_modules/") &&
                            !normalized.contains("/.git/") &&
                            !normalized.contains("/build/") &&
                            !normalized.contains("/dist/")
                    }
                    .forEach { file ->
                        val uri = file.toUri().toString()
                        loadedDocuments[uri] = indexedDocument(uri, file.readText())
                    }
            }
        }
        synchronized(this) {
            documents.clear()
            documents.putAll(loadedDocuments)
            openDocuments.clear()
            invalidateCompilation()
        }
    }

    fun open(uri: String, text: String) {
        val normalizedUri = normalizeUri(uri)
        val document = indexedDocument(normalizedUri, text)
        synchronized(this) {
            openDocuments += normalizedUri
            upsertNormalized(document)
        }
    }

    fun close(uri: String) {
        val normalizedUri = normalizeUri(uri)
        val document = readDocument(normalizedUri)
        synchronized(this) {
            openDocuments -= normalizedUri
            replaceNormalized(normalizedUri, document)
        }
    }

    fun updateFromDisk(change: FileEvent) {
        val normalizedUri = normalizeUri(change.uri)
        if (synchronized(this) { normalizedUri in openDocuments }) return
        val document = when (change.type) {
            FileChangeType.Deleted -> null
            else -> readDocument(normalizedUri)
        }
        synchronized(this) {
            if (normalizedUri in openDocuments) return
            replaceNormalized(normalizedUri, document)
        }
    }

    fun upsert(uri: String, text: String) {
        val document = indexedDocument(normalizeUri(uri), text)
        synchronized(this) {
            upsertNormalized(document)
        }
    }

    private fun indexedDocument(uri: String, text: String): IndexedDocument {
        val path = Paths.get(URI.create(uri))
        val analysis = analyzer.analyze(text, path.toString())
        return IndexedDocument(uri, path, analysis.text, analysis)
    }

    private fun upsertNormalized(document: IndexedDocument) {
        documents[document.uri] = document
        invalidateCompilation()
    }

    fun reload(uri: String) {
        reloadNormalized(normalizeUri(uri))
    }

    private fun reloadNormalized(uri: String) {
        val document = readDocument(uri)
        synchronized(this) {
            replaceNormalized(uri, document)
        }
    }

    private fun readDocument(uri: String): IndexedDocument? {
        val path = Paths.get(URI.create(uri))
        return if (Files.exists(path)) indexedDocument(uri, path.readText()) else null
    }

    fun remove(uri: String) {
        removeNormalized(normalizeUri(uri))
    }

    private fun removeNormalized(uri: String) {
        synchronized(this) {
            replaceNormalized(uri, null)
        }
    }

    private fun replaceNormalized(uri: String, document: IndexedDocument?) {
        if (document == null) {
            documents.remove(uri)
        } else {
            documents[uri] = document
        }
        invalidateCompilation()
    }

    private fun documentSnapshot(uri: String): IndexedDocument? =
        synchronized(this) { documents[uri] }

    private fun documentsSnapshot(): List<IndexedDocument> =
        synchronized(this) { documents.values.toList() }

    fun completions(uri: String, position: Position): List<CompletionItem> {
        val document = documentSnapshot(normalizeUri(uri)) ?: return emptyList()
        yamlFrontMatterCompletions(document, position)?.let { return it }
        linkSnippetCompletions(document, position)?.let { return it }
        exactPropsCompletions(document, position)?.let { return it }
        exactRelationPropsCompletions(document, position)?.let { return it }
        val referenceKind = analyzer.inferCompletionKind(
            document.analysis,
            document.analysisOffsetAt(position),
        ) ?: return emptyList()
        return contextualReferenceIds(document, position, referenceKind).map { id ->
            CompletionItem(id).apply {
                this.kind = when (referenceKind) {
                    ReferenceTargetKind.Node, ReferenceTargetKind.Media -> CompletionItemKind.Reference
                    ReferenceTargetKind.NodeType, ReferenceTargetKind.RelType, ReferenceTargetKind.Timeline -> CompletionItemKind.Class
                }
                detail = referenceKind.name
            }
        }
    }

    fun definitions(uri: String, position: Position): List<Location> {
        val documents = documentsSnapshot()
        val document = documents.firstOrNull { it.uri == normalizeUri(uri) } ?: return emptyList()
        val offset = document.analysisOffsetAt(position)
        val reference = analyzer.findReferenceAt(document.analysis, offset)
        if (reference != null) {
            return resolve(reference.kind, reference.targetId, documents).map { resolved ->
                Location(resolved.uri, resolved.range())
            }
        }
        val definition = analyzer.findDefinitionAt(document.analysis, offset)
        if (definition != null) {
            val self = Location(document.uri, document.analysisRangeOf(definition.range))
            val candidates = symbolDefinitions(definition.kind, definition.id, documents)
                .map { resolved -> Location(resolved.uri, resolved.range()) }
                .distinct()
                .sortedWith(
                    compareBy<Location>(
                        { it.uri },
                        { it.range.start.line },
                        { it.range.start.character },
                        { it.range.end.line },
                        { it.range.end.character },
                    ),
                )
            return listOf(self) + candidates.filterNot { it == self }
        }
        val propertyReference = analyzer.findPropertyReferenceAt(document.analysis, offset)
            ?: analyzer.findPropertyDefinitionAt(document.analysis, offset)?.let {
                PropertyReference(it.name, it.ownerId, it.ownerKind, it.range)
            }
            ?: return emptyList()
        return resolveProperty(propertyReference, documents).map { resolved ->
            Location(resolved.document.uri, resolved.document.analysisRangeOf(resolved.definition.range))
        }
    }

    fun references(uri: String, position: Position): List<Location> {
        val documents = documentsSnapshot()
        val document = documents.firstOrNull { it.uri == normalizeUri(uri) } ?: return emptyList()
        val offset = document.analysisOffsetAt(position)
        val reference = analyzer.findReferenceAt(document.analysis, offset)?.let { it.kind to it.targetId }
            ?: analyzer.findDefinitionAt(document.analysis, offset)?.let { it.kind to it.id }
            ?: return emptyList()

        val locations = mutableListOf<Location>()
        documents.forEach { indexed ->
            indexed.analysis.definitions
                .filter { it.kind.sharesSymbolNamespaceWith(reference.first) && it.id == reference.second }
                .forEach { locations += Location(indexed.uri, indexed.analysisRangeOf(it.range)) }
            indexed.analysis.references
                .filter { it.kind.acceptsDefinition(reference.first) && it.targetId == reference.second }
                .forEach { locations += Location(indexed.uri, indexed.analysisRangeOf(it.range)) }
        }
        return locations
    }

    fun hover(uri: String, position: Position): Hover? {
        val documents = documentsSnapshot()
        val document = documents.firstOrNull { it.uri == normalizeUri(uri) } ?: return null
        val offset = document.analysisOffsetAt(position)
        val definition = analyzer.findDefinitionAt(document.analysis, offset)
        val symbol = analyzer.findReferenceAt(document.analysis, offset)?.let { it.kind to it.targetId }
            ?: definition?.let { it.kind to it.id }
            ?: return null
        val resolved = if (definition != null) {
            symbolDefinitions(symbol.first, symbol.second, documents)
        } else {
            resolve(symbol.first, symbol.second, documents)
        }
        val resolvedKinds = resolved.map { it.kind }.distinct()
        val displayKind = definition?.kind ?: resolvedKinds.singleOrNull()
        val displayName = if (
            definition == null &&
            resolvedKinds.toSet() == setOf(ReferenceTargetKind.Node, ReferenceTargetKind.Media)
        ) {
            "Node or Media"
        } else {
            (displayKind ?: symbol.first).displayName()
        }
        val contents = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = buildString {
                append("**")
                append(displayName)
                append("** `")
                append(symbol.second)
                append("`")
                if (definition != null) {
                    append("\n\nDefined in `")
                    append(document.path.fileName.toString())
                    append("`")
                } else if (resolved.size == 1) {
                    append("\n\nDefined in `")
                    append(resolved.single().path.fileName.toString())
                    append("`")
                } else if (resolved.size > 1) {
                    append("\n\nAmbiguous: ")
                    append(resolved.size)
                    append(" definitions")
                }
            }
        }
        return Hover(contents)
    }

    fun rename(uri: String, position: Position, newName: String): WorkspaceEdit? {
        if (!GRAPH_MD_ID_REGEX.matches(newName)) {
            throw renameFailure("New name '$newName' is not a valid GraphMD ID; expected [A-Za-z_][A-Za-z0-9_.:-]*")
        }
        val documents = documentsSnapshot()
        val document = documents.firstOrNull { it.uri == normalizeUri(uri) } ?: return null
        val offset = document.analysisOffsetAt(position)
        val symbol = analyzer.findReferenceAt(document.analysis, offset)?.let { it.kind to it.targetId }
            ?: analyzer.findDefinitionAt(document.analysis, offset)?.let { it.kind to it.id }
            ?: return null
        validateRenameTarget(symbol, newName, documents)
        val changes = linkedMapOf<String, MutableList<TextEdit>>()
        documents.forEach { indexed ->
            indexed.analysis.definitions
                .filter { it.kind.sharesSymbolNamespaceWith(symbol.first) && it.id == symbol.second }
                .forEach {
                    changes.getOrPut(indexed.uri) { mutableListOf() } +=
                        TextEdit(indexed.analysisRangeOf(it.range), newName)
                }
            indexed.analysis.references
                .filter { it.kind.acceptsDefinition(symbol.first) && it.targetId == symbol.second }
                .forEach {
                    changes.getOrPut(indexed.uri) { mutableListOf() } +=
                        TextEdit(indexed.analysisRangeOf(it.range), newName)
                }
        }
        return WorkspaceEdit(changes)
    }

    fun prepareRename(uri: String, position: Position): PrepareRenameResult? {
        val documents = documentsSnapshot()
        val document = documents.firstOrNull { it.uri == normalizeUri(uri) } ?: return null
        val offset = document.offsetAt(position)
        val symbolAtPosition = analyzer.findReferenceAt(document.analysis, offset)?.let {
            Triple(it.kind, it.targetId, it.range)
        } ?: analyzer.findDefinitionAt(document.analysis, offset)?.let {
            Triple(it.kind, it.id, it.range)
        } ?: return null
        validateUnambiguousSource(symbolAtPosition.first to symbolAtPosition.second, documents)
        return PrepareRenameResult(document.rangeOf(symbolAtPosition.third), symbolAtPosition.second)
    }

    private fun validateRenameTarget(
        symbol: Pair<ReferenceTargetKind, String>,
        newName: String,
        documents: List<IndexedDocument>,
    ) {
        validateUnambiguousSource(symbol, documents)
        if (newName == symbol.second) return
        if (symbolDefinitions(symbol.first, newName, documents).isNotEmpty()) {
            throw renameFailure("${symbol.first.displayName()} '$newName' is already defined")
        }
    }

    private fun validateUnambiguousSource(
        symbol: Pair<ReferenceTargetKind, String>,
        documents: List<IndexedDocument>,
    ) {
        if (definitionsOf(symbol.first, documents).count { it.id == symbol.second } > 1) {
            throw renameFailure("Cannot rename ambiguous ${symbol.first.displayName()} '${symbol.second}'")
        }
    }

    private fun renameFailure(message: String): ResponseErrorException =
        ResponseErrorException(ResponseError(ResponseErrorCode.RequestFailed, message, null))

    fun codeActions(uri: String, diagnostics: List<org.eclipse.lsp4j.Diagnostic>): List<CodeAction> {
        val document = documentSnapshot(normalizeUri(uri)) ?: return emptyList()
        return diagnostics
            .filter { it.source == null || it.source == "graphmd" }
            .flatMap { diagnostic -> codeActionsForDiagnostic(document, diagnostic) }
            .distinctBy { action -> action.title to action.edit }
    }

    private fun codeActionsForDiagnostic(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): List<CodeAction> = buildList {
        val message = diagnostic.message

        if (message.startsWith("Ambiguous ") && " reference: " in message) {
            return@buildList
        }
        referenceTargetForDiagnostic(message)?.let { target ->
            val candidates = completionIds(target.kind)
                .sortedWith(compareBy<String> { levenshtein(it.lowercase(), target.id.lowercase()) }.thenBy { it })
                .take(8)
            candidates.forEachIndexed { index, replacement ->
                add(
                    quickFix(
                        title = "Change ${target.kind.displayName()} to '$replacement'",
                        diagnostic = diagnostic,
                        edit = WorkspaceEdit(mapOf(document.uri to listOf(TextEdit(diagnostic.range, replacement)))),
                        preferred = index == 0,
                    ),
                )
            }
            createDefinitionAction(document, diagnostic, target)?.let(::add)
            return@buildList
        }

        when (message) {
            "Document MUST start with YAML front matter" -> add(
                quickFix(
                    "Add GraphMD front matter",
                    diagnostic,
                    WorkspaceEdit(
                        mapOf(
                            document.uri to listOf(
                                TextEdit(
                                    Range(Position(0, 0), Position(0, 0)),
                                    "---\nid: ${document.defaultId()}\nkind: Node\ntype: \n---\n",
                                ),
                            ),
                        ),
                    ),
                    preferred = true,
                ),
            )
            "Unclosed YAML front matter" -> add(insertAtEnd(document, diagnostic, "Close YAML front matter", "\n---\n"))
            "YAML front matter is empty" -> add(
                replaceAction(
                    document,
                    diagnostic,
                    "Add required front matter fields",
                    document.rangeOf(SourceRange(4.coerceAtMost(document.text.length), 4.coerceAtMost(document.text.length))),
                    "id: ${document.defaultId()}\nkind: Node\ntype: \n",
                    preferred = true,
                ),
            )
            "id is required" -> addTopLevelFieldActions(document, diagnostic, "id", listOf(document.defaultId()), this)
            "kind is required" -> addTopLevelFieldActions(
                document,
                diagnostic,
                "kind",
                listOf("Node", "Media", "NodeType", "RelType", "Timeline"),
                this,
            )
            "type is required" -> {
                val values = completionIds(ReferenceTargetKind.NodeType).ifEmpty { listOf("NodeType") }
                addTopLevelFieldActions(document, diagnostic, "type", values, this)
            }
            "Media requires url" -> addTopLevelFieldActions(document, diagnostic, "url", listOf("\"\""), this)
            "Timeline with mappings requires timecode" -> add(
                insertTopLevelFieldAction(document, diagnostic, "timecode", "\n  type: number", preferred = true),
            )
        }

        Regex("""Unknown document kind: (.+)""").matchEntire(message)?.let { match ->
            val old = match.groupValues[1]
            val range = document.yamlScalarRange("kind", old) ?: diagnostic.range
            listOf("Node", "Media", "NodeType", "RelType", "Timeline").forEachIndexed { index, value ->
                add(replaceAction(document, diagnostic, "Change kind to '$value'", range, value, index == 0))
            }
        }
        Regex("""Unknown prop type: (.+)""").matchEntire(message)?.let { match ->
            val old = match.groupValues[1]
            val range = document.yamlScalarRange("type", old) ?: diagnostic.range
            PropType.entries.forEachIndexed { index, value ->
                add(replaceAction(document, diagnostic, "Change property type to '${value.name}'", range, value.name, index == 0))
            }
        }
        Regex("""Unknown timecode type: (.+)""").matchEntire(message)?.let { match ->
            val range = document.yamlScalarRange("type", match.groupValues[1]) ?: diagnostic.range
            add(replaceAction(document, diagnostic, "Use numeric timecodes", range, "number", preferred = true))
        }
        Regex("""Unknown mapping kind: (.+)""").matchEntire(message)?.let { match ->
            val range = document.yamlScalarRange("kind", match.groupValues[1]) ?: diagnostic.range
            add(replaceAction(document, diagnostic, "Use offset mapping", range, "offset", preferred = true))
        }

        Regex("""(Node|NodeType|RelType|Timeline) id must be unique: (.+)""").matchEntire(message)?.let { match ->
            val id = match.groupValues[2]
            val replacement = nextAvailableId(id, match.groupValues[1])
            val range = document.yamlScalarRange("id", id) ?: diagnostic.range
            add(replaceAction(document, diagnostic, "Rename duplicate id to '$replacement'", range, replacement, preferred = true))
        }

        unknownFieldName(message)?.let { field ->
            document.yamlFieldRange(field)?.let { range ->
                add(replaceAction(document, diagnostic, "Remove unknown field '$field'", range, "", preferred = true))
            }
        }
        Regex("""Node MUST NOT define top-level field: (.+)""").matchEntire(message)?.let { match ->
            val field = match.groupValues[1]
            document.yamlFieldRange(field)?.let { range ->
                add(replaceAction(document, diagnostic, "Move or remove reserved field '$field'", range, "", preferred = true))
            }
        }
        Regex("""(.+) has unknown fields: (.+)""").matchEntire(message)?.let { match ->
            match.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty).forEach { field ->
                document.yamlFieldRange(field)?.let { range ->
                    add(replaceAction(document, diagnostic, "Remove unknown field '$field'", range, "", preferred = size == 0))
                }
            }
        }

        Regex("""Cyclic (?:Timeline|NodeType|RelType) inheritance: .+""").matchEntire(message)?.let {
            document.yamlFieldRange("extends")?.let { range ->
                add(replaceAction(document, diagnostic, "Remove cyclic 'extends'", range, "", preferred = true))
            }
        }
        Regex("""Invalid refinement for prop (.+)""").matchEntire(message)?.let { match ->
            val property = match.groupValues[1]
            document.yamlFieldRange(property)?.let { range ->
                add(replaceAction(document, diagnostic, "Remove invalid refinement '$property'", range, "", preferred = true))
            }
        }
        Regex("""(?:Inherited(?: and child)? )?(from|to) constraints have an empty intersection""").matchEntire(message)?.let { match ->
            val field = match.groupValues[1]
            document.yamlFieldRange(field)?.let { range ->
                add(replaceAction(document, diagnostic, "Remove conflicting '$field' constraint", range, "", preferred = true))
            }
        }
        if (message.startsWith("Timeline extends must stay on the same time axis")) {
            document.yamlFieldRange("extends")?.let { range ->
                add(replaceAction(document, diagnostic, "Remove incompatible Timeline inheritance", range, "", preferred = true))
            }
        }
        if (message.startsWith("Timeline extends cannot change timecode schema")) {
            document.yamlFieldRange("timecode")?.let { range ->
                add(replaceAction(document, diagnostic, "Use inherited timecode schema", range, "", preferred = true))
            }
        }
        if (message == "offset mapping requires exactly one of from or to") {
            listOf("from", "to").forEach { field ->
                document.yamlFieldRange(field)?.let { range ->
                    add(replaceAction(document, diagnostic, "Remove mapping '$field'", range, "", preferred = size == 0))
                }
            }
            if (none { it.title.startsWith("Remove mapping") }) {
                val timeline = completionIds(ReferenceTargetKind.Timeline).firstOrNull()
                document.mappingFieldInsertion("to", timeline.orEmpty())?.let { insertion ->
                    add(replaceAction(document, diagnostic, "Add mapping 'to'", insertion.range, insertion.text, preferred = true))
                }
            }
        }
        if (message == "mapping.offset MUST be finite") {
            document.propertyValueRange("offset")?.let { range ->
                add(replaceAction(document, diagnostic, "Replace offset with 0", range, "0", preferred = true))
            }
        }
        Regex("""Invalid YAML mapping entry: (.+)""").matchEntire(message)?.let { match ->
            document.mappingColonInsertion(match.groupValues[1])?.let { range ->
                add(replaceAction(document, diagnostic, "Add ':' to YAML mapping", range, ": ", preferred = true))
            }
        }
        Regex("""(.+) items MUST be (strings|non-empty|unique)""").matchEntire(message)?.let { match ->
            val field = match.groupValues[1].substringAfterLast('.')
            document.normalizeStringList(field)?.let { edit ->
                add(
                    quickFix(
                        "Normalize '$field' string list",
                        diagnostic,
                        WorkspaceEdit(mapOf(document.uri to listOf(edit))),
                        preferred = true,
                    ),
                )
            }
        }
        Regex("""(.+) selector MUST be \{ id: Identifier, mapped: boolean } or .+""").matchEntire(message)?.let { match ->
            val field = match.groupValues[1].substringAfterLast('.')
            val timeline = completionIds(ReferenceTargetKind.Timeline).firstOrNull().orEmpty()
            document.propertyValueRange(field)?.let { range ->
                add(
                    replaceAction(
                        document,
                        diagnostic,
                        "Replace '$field' with a valid Timeline selector",
                        range,
                        "{ id: $timeline, mapped: false }",
                        preferred = true,
                    ),
                )
            }
        }
        Regex("""(.+) MUST be a Timeline identifier or legacy selector list entry""").matchEntire(message)?.let { match ->
            val field = match.groupValues[1].substringAfterLast('.')
            val range = document.propertyValueRange(field)
            if (range != null) {
                completionIds(ReferenceTargetKind.Timeline).take(8).forEachIndexed { index, timeline ->
                    add(replaceAction(document, diagnostic, "Use Timeline '$timeline'", range, timeline, preferred = index == 0))
                }
            }
        }

        Regex("""Required property missing after normalization: (.+)""").matchEntire(message)?.let { match ->
            val key = match.groupValues[1]
            val parsed = document.analysis.parsed.document as? NodeDocument
            val schema = parsed?.let { nodeTypeSchema(it.type)?.props?.get(key) }
            add(insertNodePropertyAction(document, diagnostic, key, schema, preferred = true))
        }

        Regex("""Unknown property ([A-Za-z_][A-Za-z0-9_.:-]*) on (.+)""").matchEntire(message)?.let { match ->
            val key = match.groupValues[1]
            declarationActionForUnknownProperty(document, diagnostic, key, match.groupValues[2])?.let(::add)
            document.propertyAssignmentRange(key)?.let { range ->
                add(replaceAction(document, diagnostic, "Remove unknown property '$key'", range, "", preferred = false))
            }
        }

        typedDefaultFix(document, diagnostic)?.let(::add)
        genericYamlTypeFix(document, diagnostic)?.let(::add)
        addAll(constraintFixes(document, diagnostic))
        syntaxClosingFix(document, diagnostic)?.let(::add)
        if (isEmpty() && diagnostic.range.start != diagnostic.range.end) {
            add(
                replaceAction(
                    document,
                    diagnostic,
                    "Remove invalid construct",
                    diagnostic.range,
                    "",
                    preferred = false,
                ),
            )
        }
    }

    private fun quickFix(
        title: String,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        edit: WorkspaceEdit?,
        preferred: Boolean = false,
        command: Command? = null,
    ): CodeAction = CodeAction(title).apply {
        kind = CodeActionKind.QuickFix
        diagnostics = listOf(diagnostic)
        this.edit = edit
        this.command = command
        isPreferred = preferred
    }

    private fun replaceAction(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        title: String,
        range: Range,
        newText: String,
        preferred: Boolean = false,
    ): CodeAction = quickFix(
        title,
        diagnostic,
        WorkspaceEdit(mapOf(document.uri to listOf(TextEdit(range, newText)))),
        preferred,
    )

    private fun insertAtEnd(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        title: String,
        text: String,
    ): CodeAction = replaceAction(document, diagnostic, title, document.endRange(), text, preferred = true)

    private fun addTopLevelFieldActions(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        field: String,
        values: List<String>,
        actions: MutableList<CodeAction>,
    ) {
        values.distinct().take(8).forEachIndexed { index, value ->
            actions += insertTopLevelFieldAction(document, diagnostic, field, value, preferred = index == 0)
        }
    }

    private fun insertTopLevelFieldAction(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        field: String,
        value: String,
        preferred: Boolean,
    ): CodeAction {
        val insertion = document.frontMatterClosingOffset() ?: document.text.length
        val text = "$field: $value\n"
        return replaceAction(
            document,
            diagnostic,
            "Add '$field${if (value.isNotEmpty()) ": $value" else ""}'",
            document.rangeOf(SourceRange(insertion, insertion)),
            text,
            preferred,
        )
    }

    private fun insertNodePropertyAction(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        key: String,
        schema: ResolvedPropSchema?,
        preferred: Boolean,
    ): CodeAction {
        val insertion = document.propsInsertion(key, defaultValue(schema))
        return replaceAction(
            document,
            diagnostic,
            "Add required property '$key'",
            insertion.range,
            insertion.text,
            preferred,
        )
    }

    private fun defaultValue(schema: ResolvedPropSchema?): String = when (schema?.type) {
        PropType.string, PropType.text, null -> "\"\""
        PropType.number, PropType.instant -> "0"
        PropType.duration -> "{ from: 0 }"
        PropType.array -> "[]"
    }

    private fun typedDefaultFix(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): CodeAction? {
        val match = Regex("""^([A-Za-z_][A-Za-z0-9_.:-]*)(?:\.[A-Za-z0-9_.:-]+)? must be (string|text|number|array|duration object)$""")
            .matchEntire(diagnostic.message) ?: return null
        val key = match.groupValues[1]
        val replacement = when (match.groupValues[2]) {
            "string", "text" -> "\"\""
            "number" -> "0"
            "array" -> "[]"
            "duration object" -> "{ from: 0 }"
            else -> return null
        }
        val range = document.propertyValueRange(key) ?: return null
        return replaceAction(document, diagnostic, "Replace '$key' with a valid ${match.groupValues[2]}", range, replacement, preferred = true)
    }

    private fun genericYamlTypeFix(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): CodeAction? {
        if (diagnostic.message == "id MUST be non-empty") {
            val range = document.propertyValueRange("id") ?: return null
            return replaceAction(document, diagnostic, "Use filename as id", range, document.defaultId(), preferred = true)
        }
        if (diagnostic.message == "validTime.timeline MUST be non-empty") {
            val timeline = completionIds(ReferenceTargetKind.Timeline).firstOrNull() ?: return null
            val range = document.propertyValueRange("timeline") ?: return null
            return replaceAction(document, diagnostic, "Use Timeline '$timeline'", range, timeline, preferred = true)
        }
        val scalar = Regex("""^(.+) MUST be (?:a |an )?(string|boolean|number|integer|mapping)$""")
            .matchEntire(diagnostic.message)
        if (scalar != null) {
            val field = scalar.groupValues[1].substringAfterLast('.')
            val replacement = when (scalar.groupValues[2]) {
                "string" -> "\"\""
                "boolean" -> "false"
                "number", "integer" -> "0"
                "mapping" -> "{}"
                else -> return null
            }
            val range = document.propertyValueRange(field) ?: return null
            return replaceAction(document, diagnostic, "Replace '$field' with a valid ${scalar.groupValues[2]}", range, replacement, preferred = true)
        }
        val list = Regex("""^(.+?)(?: items)? MUST be (?:a )?(?:non-empty )?list(?: of strings)?$""")
            .matchEntire(diagnostic.message)
        if (list != null) {
            val field = list.groupValues[1].substringAfterLast('.')
            val range = document.propertyValueRange(field) ?: return null
            val replacement = if ("non-empty" in diagnostic.message || "of strings" in diagnostic.message || "items" in diagnostic.message) "[value]" else "[]"
            return replaceAction(document, diagnostic, "Replace '$field' with a list", range, replacement, preferred = true)
        }
        return null
    }

    private fun constraintFixes(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): List<CodeAction> = buildList {
        Regex("""validTime\.from is after validTime\.to on (.+)""").matchEntire(diagnostic.message)?.let {
            val edits = document.swapValidTimeBounds(it.groupValues[1])
            if (edits != null) {
                add(
                    quickFix(
                        "Swap validTime from/to",
                        diagnostic,
                        WorkspaceEdit(mapOf(document.uri to edits)),
                        preferred = true,
                    ),
                )
            }
        }
        Regex("""([A-Za-z_][A-Za-z0-9_.:-]*) timeline ([A-Za-z_][A-Za-z0-9_.:-]*) is not allowed""")
            .matchEntire(diagnostic.message)?.let { match ->
                val property = match.groupValues[1]
                val current = match.groupValues[2]
                val parsed = document.analysis.parsed.document as? NodeDocument
                val schema = parsed?.let { nodeTypeSchema(it.type)?.props?.get(property) }
                val allowed = (listOfNotNull(schema?.timeline) + schema?.timelines.orEmpty())
                    .map {
                        when (it) {
                            is TimelineSelector.Id -> it.id
                            is TimelineSelector.Mapped -> it.to
                        }
                    }
                    .distinct()
                val range = document.tokenRange(current)
                if (range != null) {
                    allowed.forEachIndexed { index, replacement ->
                        add(replaceAction(document, diagnostic, "Use allowed Timeline '$replacement'", range, replacement, index == 0))
                    }
                }
            }
        Regex("""([A-Za-z_][A-Za-z0-9_.:-]*) duration must define from or to""")
            .matchEntire(diagnostic.message)?.let { match ->
                document.durationBoundInsertion(match.groupValues[1])?.let { insertion ->
                    add(
                        replaceAction(
                            document,
                            diagnostic,
                            "Add duration 'from' bound",
                            insertion.range,
                            insertion.text,
                            preferred = true,
                        ),
                    )
                }
            }
        Regex("""Relation (?:source|target) type .+ is not allowed for (.+)""")
            .matchEntire(diagnostic.message)?.let { match ->
                val currentRelType = match.groupValues[1]
                val relReference = document.analysis.references.firstOrNull {
                    it.kind == ReferenceTargetKind.RelType && it.targetId == currentRelType
                }
                if (relReference != null) {
                    val sourceType = (document.analysis.parsed.document as? NodeDocument)?.type
                    val targetId = document.analysis.references.firstOrNull {
                        it.kind == ReferenceTargetKind.Node && it.range.start <= relReference.range.start
                    }?.targetId
                    val compiled = compiledWorkspace()
                    val targetType = compiled.nodes.firstOrNull { it.id == targetId }?.type
                    compiled.relTypes.filter { rel ->
                        val from = rel.from
                        val to = rel.to
                        (sourceType == null || from == null || from.any { nodeTypeMatches(sourceType, it, compiled.nodeTypes) }) &&
                            (targetType == null || to == null || to.any { nodeTypeMatches(targetType, it, compiled.nodeTypes) })
                    }.filterNot { it.id == currentRelType }.forEachIndexed { index, rel ->
                        add(
                            replaceAction(
                                document,
                                diagnostic,
                                "Change relation type to '${rel.id}'",
                                document.analysisRangeOf(relReference.range),
                                rel.id,
                                preferred = index == 0,
                            ),
                        )
                    }
                }
            }
    }

    private fun syntaxClosingFix(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
    ): CodeAction? {
        val message = diagnostic.message
        if (message in setOf("@props only accepts validTime=...", "@link only accepts validTime=...")) {
            val marker = if (message.startsWith("@props")) "@props(" else "@link("
            val start = document.text.lastIndexOf(marker)
            val end = if (start >= 0) document.text.indexOf(')', start + marker.length) else -1
            if (start >= 0 && end >= 0) {
                val timeline = completionIds(ReferenceTargetKind.Timeline).firstOrNull().orEmpty()
                return replaceAction(
                    document,
                    diagnostic,
                    "Replace arguments with validTime",
                    document.rangeOf(SourceRange(start + marker.length, end)),
                    "validTime=$timeline",
                    preferred = true,
                )
            }
        }
        if (message == "@link must be followed immediately by a link") {
            document.linkWhitespaceRange()?.let { range ->
                return replaceAction(document, diagnostic, "Remove whitespace after @link", range, "", preferred = true)
            }
        }
        if (message == "Relation must be followed by (...)") {
            val closeLabel = document.text.lastIndexOf(']')
            if (closeLabel >= 0) {
                val target = completionIds(ReferenceTargetKind.Node).firstOrNull() ?: "target"
                val relType = completionIds(ReferenceTargetKind.RelType).firstOrNull() ?: "relationType"
                return replaceAction(
                    document,
                    diagnostic,
                    "Add relation target and type",
                    document.rangeOf(SourceRange(closeLabel + 1, closeLabel + 1)),
                    "($target $relType)",
                    preferred = true,
                )
            }
        }
        if (message == "Relation target and type must be separated by horizontal spaces") {
            document.lastRelationInnerRange()?.let { (range, inner) ->
                val tokens = Regex("""[A-Za-z_][A-Za-z0-9_.:-]*""").findAll(inner).map { it.value }.toList()
                if (tokens.size >= 2) {
                    return replaceAction(
                        document,
                        diagnostic,
                        "Separate relation target and type with a space",
                        range,
                        "${tokens[0]} ${tokens[1]}",
                        preferred = true,
                    )
                }
            }
        }
        val (title, marker, closing, before) = when (message) {
            "Unclosed @props arguments" -> SyntaxFix("Close @props arguments", "@props(", ")", "{")
            "Unclosed @props block" -> SyntaxFix("Close @props block", "@props", "}", null)
            "Unclosed @link arguments" -> SyntaxFix("Close @link arguments", "@link(", ")", "{")
            "Unclosed @link property block" -> SyntaxFix("Close @link property block", "@link", "}", "[")
            "Unclosed relation label" -> SyntaxFix("Close relation label", "[", "]", null)
            "Unclosed relation target" -> SyntaxFix("Close relation target", "](", ")", null)
            else -> return null
        }
        val start = document.text.lastIndexOf(marker)
        if (start < 0) return null
        val insertion = before?.let { document.text.indexOf(it, start + marker.length).takeIf { found -> found >= 0 } }
            ?: document.text.length
        return replaceAction(
            document,
            diagnostic,
            title,
            document.rangeOf(SourceRange(insertion, insertion)),
            closing,
            preferred = true,
        )
    }

    private fun nextAvailableId(id: String, kindName: String): String {
        val kind = when (kindName) {
            "Node" -> ReferenceTargetKind.Node
            "Media" -> ReferenceTargetKind.Media
            "NodeType" -> ReferenceTargetKind.NodeType
            "RelType" -> ReferenceTargetKind.RelType
            "Timeline" -> ReferenceTargetKind.Timeline
            else -> return "${id}2"
        }
        val used = completionIds(kind).toSet()
        var suffix = 2
        while ("$id$suffix" in used) suffix++
        return "$id$suffix"
    }

    private fun createDefinitionAction(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        target: DiagnosticReferenceTarget,
    ): CodeAction? {
        if (!target.id.matches(Regex("""[A-Za-z_][A-Za-z0-9_.:-]*"""))) return null
        val folder = when (target.kind) {
            ReferenceTargetKind.NodeType, ReferenceTargetKind.RelType -> "types"
            ReferenceTargetKind.Timeline -> "timelines"
            ReferenceTargetKind.Node, ReferenceTargetKind.Media -> "nodes"
        }
        val workspaceRoots = synchronized(this) { roots.toList() }
        val base = workspaceRoots.firstOrNull { document.path.startsWith(it) }
            ?: document.path.parent?.takeUnless { it.fileName?.toString() in setOf("types", "timelines", "nodes") }
            ?: document.path.parent?.parent
            ?: return null
        val preferredDirectory = base.resolve(folder)
        val definitionDirectory = preferredDirectory.takeIf { Files.isDirectory(it) } ?: base
        val newPath = definitionDirectory.resolve("${target.id}.md")
        val newUri = newPath.toUri().toString()
        if (documentSnapshot(newUri) != null || Files.exists(newPath)) return null

        val nodeTypeIds = unambiguousDefinitionIds(ReferenceTargetKind.NodeType)
        if (target.kind == ReferenceTargetKind.Node || target.kind == ReferenceTargetKind.Media) {
            if (nodeTypeIds.isEmpty()) return null
            val choices = nodeTypeIds.map { nodeTypeId ->
                mapOf(
                    "label" to nodeTypeId,
                    "content" to definitionContent(target, nodeTypeId),
                )
            }
            return quickFix(
                title = "Create ${target.kind.displayName()} '${target.id}'",
                diagnostic = diagnostic,
                edit = null,
                command = Command(
                    "Create ${target.kind.displayName()} '${target.id}'",
                    CREATE_DEFINITION_COMMAND,
                    listOf(
                        mapOf(
                            "uri" to newUri,
                            "kind" to target.kind.displayName(),
                            "id" to target.id,
                            "choices" to choices,
                        ),
                    ),
                ),
                preferred = completionIds(target.kind).isEmpty(),
            )
        }

        val content = definitionContent(target, null)
        val changes = listOf<Either<TextDocumentEdit, ResourceOperation>>(
            Either.forRight(CreateFile(newUri, CreateFileOptions(false, true))),
            Either.forLeft(
                TextDocumentEdit(
                    VersionedTextDocumentIdentifier(newUri, null),
                    listOf(TextEdit(Range(Position(0, 0), Position(0, 0)), content)),
                ),
            ),
        )
        return quickFix(
            "Create ${target.kind.displayName()} '${target.id}'",
            diagnostic,
            WorkspaceEdit(changes),
            preferred = completionIds(target.kind).isEmpty(),
        )
    }

    private fun definitionContent(target: DiagnosticReferenceTarget, nodeTypeId: String?): String = when (target.kind) {
        ReferenceTargetKind.NodeType -> "---\nid: ${target.id}\nkind: NodeType\nprops:\n---\n"
        ReferenceTargetKind.RelType -> "---\nid: ${target.id}\nkind: RelType\n---\n"
        ReferenceTargetKind.Timeline -> "---\nid: ${target.id}\nkind: Timeline\ntimecode:\n  type: number\n---\n"
        ReferenceTargetKind.Node -> nodeDefinitionContent(target, "Node", nodeTypeId)
        ReferenceTargetKind.Media -> nodeDefinitionContent(target, "Media", nodeTypeId, includeUrl = true)
    }

    private fun nodeDefinitionContent(
        target: DiagnosticReferenceTarget,
        kind: String,
        nodeTypeId: String?,
        includeUrl: Boolean = false,
    ): String {
        val type = requireNotNull(nodeTypeId)
        return buildString {
            append("---\n")
            append("id: ${target.id}\n")
            append("kind: $kind\n")
            append("type: $type\n")
            if (includeUrl) append("url: \"\"\n")
            append(requiredPropsContent(type))
            append("---\n")
        }
    }

    private fun requiredPropsContent(nodeTypeId: String): String {
        val requiredProps = nodeTypeSchema(nodeTypeId)?.props
            ?.filterValues { it.required }
            .orEmpty()
        if (requiredProps.isEmpty()) return ""
        return buildString {
            append("props:\n")
            requiredProps.forEach { (key, schema) ->
                append("  $key: ${defaultValue(schema)}\n")
            }
        }
    }

    private fun declarationActionForUnknownProperty(
        document: IndexedDocument,
        diagnostic: org.eclipse.lsp4j.Diagnostic,
        key: String,
        owner: String,
    ): CodeAction? {
        val normalized = compiledWorkspace()
        val targetSource = when {
            owner.startsWith("Node ") -> {
                val type = (document.analysis.parsed.document as? NodeDocument)?.type ?: return null
                normalized.nodeTypes.firstOrNull { it.id == type }?.source?.path
            }
            owner.startsWith("Relation ") -> {
                val relType = owner.substringAfterLast(':')
                normalized.relTypes.firstOrNull { it.id == relType }?.source?.path
            }
            else -> null
        } ?: return null
        val schemaDocument = documentsSnapshot().firstOrNull { it.path.toString() == targetSource } ?: return null
        val insertion = schemaDocument.propSchemaInsertion(key)
        return quickFix(
            "Declare '$key' in ${schemaDocument.analysis.parsed.document?.id ?: "schema"}",
            diagnostic,
            WorkspaceEdit(mapOf(schemaDocument.uri to listOf(TextEdit(insertion.range, insertion.text)))),
            preferred = true,
        )
    }

    private fun unknownFieldName(message: String): String? {
        val patterns = listOf(
            Regex("""Unknown top-level field: (.+)"""),
            Regex("""Unknown validTime field: (.+)"""),
            Regex("""Unknown validTime\.(?:from|to) field: (.+)"""),
            Regex("""Unknown mapping field: (.+)"""),
            Regex("""Unknown timecode field: (.+)"""),
            Regex("""Unknown property schema field: .+\.([^.]+)"""),
        )
        return patterns.firstNotNullOfOrNull { it.matchEntire(message)?.groupValues?.get(1) }
    }

    private fun ReferenceTargetKind.displayName(): String = when (this) {
        ReferenceTargetKind.Node -> "Node"
        ReferenceTargetKind.Media -> "Media"
        ReferenceTargetKind.NodeType -> "NodeType"
        ReferenceTargetKind.RelType -> "RelType"
        ReferenceTargetKind.Timeline -> "Timeline"
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1,
                )
            }
            previous = current
        }
        return previous.last()
    }

    fun diagnosticsByUri(): Map<String, MutableList<org.eclipse.lsp4j.Diagnostic>> {
        val workspace = compiledWorkspaceSnapshot()
        val compiled = workspace.compilation
        val documents = workspace.documents.associateBy { it.uri }
        val diagnostics = linkedMapOf<String, MutableList<org.eclipse.lsp4j.Diagnostic>>()
        compiled.diagnostics.forEach { diagnostic ->
            val sourcePath = diagnostic.source?.path ?: return@forEach
            val uri = Path.of(sourcePath).toUri().toString()
            val document = documents[uri] ?: return@forEach
            val referenceTarget = diagnostic
                .takeIf { it.category == DiagnosticCategory.ReferenceError }
                ?.let { referenceTargetForDiagnostic(it.message) }
            if (referenceTarget != null && document.analysis.references.any { reference ->
                    reference.kind == referenceTarget.kind &&
                        reference.targetId == referenceTarget.id &&
                        (referenceTarget.field == null || reference.field == referenceTarget.field)
                }
            ) {
                return@forEach
            }
            diagnostics.getOrPut(uri) { mutableListOf() } += org.eclipse.lsp4j.Diagnostic().apply {
                severity = when (diagnostic.severity) {
                    Severity.Error -> DiagnosticSeverity.Error
                    Severity.Warning -> DiagnosticSeverity.Warning
                }
                message = diagnostic.message
                source = "graphmd"
                code = Either.forLeft(diagnostic.category.name)
                range = inferredDiagnosticLspRange(document, diagnostic)
                    ?: document.analysisRangeOf(diagnosticSourceRange(document, diagnostic) ?: SourceRange(0, 0))
            }
        }
        val definitionsById = workspace.documents
            .flatMap { it.analysis.definitions }
            .groupBy { it.id }
        workspace.documents.forEach { document ->
            document.analysis.references.forEach { reference ->
                val candidates = definitionsById[reference.targetId].orEmpty()
                val matching = candidates.count { it.kind == reference.kind }
                val message = when {
                    matching > 1 -> "Ambiguous ${reference.kind.displayName()} reference: ${reference.targetId}"
                    matching == 1 -> null
                    candidates.isNotEmpty() -> {
                        val actualKinds = candidates.map { it.kind.displayName() }.distinct().sorted().joinToString(", ")
                        "Expected ${reference.kind.displayName()} but found $actualKinds: ${reference.targetId}"
                    }
                    else -> unresolvedReferenceMessage(reference)
                }
                if (message != null) {
                    diagnostics.getOrPut(document.uri) { mutableListOf() } += org.eclipse.lsp4j.Diagnostic().apply {
                        severity = DiagnosticSeverity.Error
                        this.message = message
                        source = "graphmd"
                        code = Either.forLeft(DiagnosticCategory.ReferenceError.name)
                        range = document.analysisRangeOf(reference.range)
                    }
                }
            }
        }
        documents.keys.forEach { uri -> diagnostics.putIfAbsent(uri, mutableListOf()) }
        return diagnostics
    }

    private fun unresolvedReferenceMessage(reference: SymbolReference): String = when {
        reference.kind == ReferenceTargetKind.NodeType && reference.field == "extends" ->
            "Unknown parent NodeType: ${reference.targetId}"
        reference.kind == ReferenceTargetKind.RelType && reference.field == "extends" ->
            "Unknown parent RelType: ${reference.targetId}"
        reference.kind == ReferenceTargetKind.Timeline && reference.field == "extends" ->
            "Unknown parent Timeline: ${reference.targetId}"
        reference.kind == ReferenceTargetKind.NodeType ->
            "Unknown NodeType: ${reference.targetId}"
        reference.kind == ReferenceTargetKind.RelType ->
            "Unknown RelType: ${reference.targetId}"
        reference.kind == ReferenceTargetKind.Node ->
            "Unknown Node target: ${reference.targetId}"
        else ->
            "Unknown Timeline: ${reference.targetId}"
    }

    private fun normalizeUri(uri: String): String =
        Paths.get(URI.create(uri)).normalize().toUri().toString()

    private fun diagnosticSourceRange(document: IndexedDocument, diagnostic: Diagnostic): SourceRange? {
        val range = diagnostic.source?.range ?: return null
        if (diagnostic.category != DiagnosticCategory.SyntaxError) return range
        return SourceRange(
            start = document.analysis.frontMatterEndOffset + range.start,
            end = document.analysis.frontMatterEndOffset + range.end,
        )
    }

    private fun yamlFrontMatterCompletions(document: IndexedDocument, position: Position): List<CompletionItem>? {
        val resolver = FrontMatterCompletionResolver(
            text = document.analysis.text,
            offset = document.analysisOffsetAt(position),
            parsedDocument = document.analysis.parsed.document,
            nodeTypeIds = completionIds(ReferenceTargetKind.NodeType),
            relTypeIds = completionIds(ReferenceTargetKind.RelType),
            timelineIds = completionIds(ReferenceTargetKind.Timeline),
            nodePropsSchema = ((document.analysis.parsed.document as? NodeDocument)?.type
                ?: frontMatterScalar(document.text, "type"))?.let { nodeTypeSchema(it)?.props }.orEmpty(),
        )
        val replacementRange = document.completionReplacementRange(position)
        return resolver.resolve()?.map { entry ->
            entry.toCompletionItem(replacementRange)
        }
    }

    private fun exactPropsCompletions(document: IndexedDocument, position: Position): List<CompletionItem>? {
        val parsed = document.analysis.parsed.document as? NodeDocument
        val offset = document.offsetAt(position)
        val nodeType = parsed?.type ?: frontMatterScalar(document.text, "type") ?: return null
        val schema = nodeTypeSchema(nodeType)?.props ?: return null
        val context = PropsCompletionContextResolver(document.text, offset, schema, timelineIds()).resolve() ?: return null
        val replacementRange = document.completionReplacementRange(position)
        return context.items.map { it.toCompletionItem(replacementRange) }
    }

    private fun linkSnippetCompletions(document: IndexedDocument, position: Position): List<CompletionItem>? {
        val parsed = document.analysis.parsed.document as? NodeDocument ?: return null
        val trigger = linkSnippetTrigger(document, position) ?: return null
        val compiled = compiledWorkspace()
        val relationTypes = compiled.relTypes
            .filter { relationType ->
                val allowedFrom = relationType.from
                allowedFrom == null || allowedFrom.any { allowed ->
                    nodeTypeMatches(parsed.type, allowed, compiled.nodeTypes)
                }
            }
            .distinctBy { it.id }
            .sortedBy { it.id }

        val candidates: List<NormalizedRelType?> = if (relationTypes.isEmpty()) listOf(null) else relationTypes
        return candidates.mapIndexed { index, relationType ->
            val snippet = linkSnippet(relationType)
            CompletionItem(relationType?.let { "@link (${it.id})" } ?: "@link").apply {
                kind = CompletionItemKind.Snippet
                detail = relationType?.let { "RelType link: ${it.id}" } ?: "GraphMD link"
                insertText = snippet
                insertTextFormat = InsertTextFormat.Snippet
                textEdit = Either.forLeft(
                    TextEdit(document.analysisRangeOf(trigger), snippet),
                )
                sortText = "0-${relationType?.id ?: "link"}-$index"
            }
        }
    }

    private fun linkSnippetTrigger(document: IndexedDocument, position: Position): SourceRange? {
        val text = document.analysis.text
        val offset = document.analysisOffsetAt(position).coerceIn(0, text.length)
        if (offset < document.analysis.frontMatterEndOffset) return null

        var at = offset - 1
        while (at >= document.analysis.frontMatterEndOffset && isIdentifierPart(text[at])) at--
        if (text.getOrNull(at) != '@') return null
        val prefixStart = at + 1
        val prefix = text.substring(prefixStart, offset)
        if (!"link".startsWith(prefix)) return null
        if (at > 0 && (isEscaped(text, at) || isIdentifierPart(text[at - 1]))) return null
        if (offset < text.length && !text[offset].isWhitespace()) return null
        if (isMarkdownCodeContext(text, at, document.analysis.frontMatterEndOffset)) return null
        return SourceRange(at, offset)
    }

    private fun linkSnippet(relationType: NormalizedRelType?): String = buildString {
        append("@link")
        val requiredProps = relationType?.props.orEmpty().filterValues { it.required }
        if (requiredProps.isNotEmpty()) {
            append('{')
            requiredProps.entries.forEachIndexed { index, (name, schema) ->
                if (index > 0) append(", ")
                append(name)
                append(" = ")
                append(inlineDefaultValue(schema))
            }
            append('}')
        }
        append('[')
        append('$').append("{1:title}")
        append("](")
        append('$').append("{2:id} ")
        append('$').append("{3:")
        append(relationType?.id ?: "reltype")
        append("})")
    }

    private fun inlineDefaultValue(schema: ResolvedPropSchema): String = when (schema.type) {
        PropType.string, PropType.text -> "\"\""
        PropType.number, PropType.instant -> "0"
        PropType.duration -> "{ from = 0 }"
        PropType.array -> "[]"
    }

    private fun isMarkdownCodeContext(text: String, offset: Int, bodyStartOffset: Int): Boolean {
        if (bodyStartOffset !in 0..text.length) return false
        val bodyOffset = offset - bodyStartOffset
        val body = text.substring(bodyStartOffset)
        if (bodyOffset !in body.indices) return false
        val masked = maskCommonMarkCodeRegions(body)
        return masked[bodyOffset] != body[bodyOffset]
    }

    private fun exactRelationPropsCompletions(document: IndexedDocument, position: Position): List<CompletionItem>? {
        val offset = document.offsetAt(position)
        val relationContext = RelationPropsCompletionContextResolver(document.text, offset).resolve() ?: return null
        val schema = relTypeSchema(relationContext.relType)?.props ?: return null
        val context = PropsCompletionContextResolver(
            text = document.text,
            offset = offset,
            rootSchema = schema,
            timelineIds = timelineIds(),
            explicitBraceStart = relationContext.braceStart,
        ).resolve() ?: return null
        val replacementRange = document.completionReplacementRange(position)
        return context.items.map { it.toCompletionItem(replacementRange) }
    }

    private fun contextualReferenceIds(
        document: IndexedDocument,
        position: Position,
        kind: ReferenceTargetKind,
    ): List<String> {
        val context = relationCompletionContext(document.text, document.offsetAt(position))
        if (context == null) return completionIds(kind)
        val compiled = compiledWorkspace()
        val sourceType = (document.analysis.parsed.document as? NodeDocument)?.type
            ?: frontMatterScalar(document.text, "type")
        val targetType = context.targetId?.let { target -> compiled.nodes.firstOrNull { it.id == target }?.type }
        return when (kind) {
            ReferenceTargetKind.Node, ReferenceTargetKind.Media -> {
                val allowedTargets = context.relType?.let { rel -> compiled.relTypes.firstOrNull { it.id == rel }?.to }
                compiled.nodes
                    .filter { node -> allowedTargets == null || allowedTargets.any { nodeTypeMatches(node.type, it, compiled.nodeTypes) } }
                    .map { it.id }
                    .distinct()
                    .sorted()
            }
            ReferenceTargetKind.RelType -> compiled.relTypes
                .filter { rel ->
                    val allowedFrom = rel.from
                    val allowedTo = rel.to
                    (sourceType == null || allowedFrom == null || allowedFrom.any { nodeTypeMatches(sourceType, it, compiled.nodeTypes) }) &&
                        (targetType == null || allowedTo == null || allowedTo.any { nodeTypeMatches(targetType, it, compiled.nodeTypes) })
                }
                .map { it.id }
                .distinct()
                .sorted()
            else -> completionIds(kind)
        }
    }

    private fun nodeTypeMatches(actual: String, allowed: String, nodeTypes: List<NormalizedNodeType>): Boolean {
        if (actual == allowed) return true
        return nodeTypes.firstOrNull { it.id == actual }?.ancestorIds?.contains(allowed) == true
    }

    private fun relationCompletionContext(text: String, offset: Int): RelationReferenceCompletionContext? {
        val openParen = text.lastIndexOf('(', offset.coerceAtMost(text.lastIndex))
        if (openParen < 0) return null
        val closeParen = text.indexOf(')', openParen + 1)
        if (closeParen < 0 || offset > closeParen) return null
        val labelClose = text.lastIndexOf(']', openParen)
        if (labelClose < 0 || labelClose + 1 != openParen) return null
        val before = text.substring(openParen + 1, closeParen)
        val tokens = before.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val firstWhitespace = before.indexOfFirst { it.isWhitespace() }
        val editingTarget = firstWhitespace < 0 || offset - openParen - 1 <= firstWhitespace
        return RelationReferenceCompletionContext(
            targetId = tokens.firstOrNull()?.trim('"')?.takeIf { !editingTarget || firstWhitespace >= 0 },
            relType = tokens.getOrNull(1)?.trim('"'),
        )
    }

    private fun isIdentifierPart(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '.' || char == ':' || char == '-'

    private fun isEscaped(text: String, offset: Int): Boolean {
        var slashCount = 0
        var index = offset - 1
        while (index >= 0 && text[index] == '\\') {
            slashCount++
            index--
        }
        return slashCount % 2 == 1
    }

    fun search(params: GraphMdSearchParams): GraphMdSearchResponse {
        if (params.query.isBlank()) {
            return GraphMdSearchResponse(
                diagnostics = listOf(
                    GraphMdSearchDiagnostic("GRAPHMD_SEARCH", "input", "Query must not be blank."),
                ),
            )
        }
        val parameters = try {
            params.parameters.mapValues { (name, value) -> parseSearchParameter(name, value) }
        } catch (exception: SearchParameterException) {
            return GraphMdSearchResponse(
                diagnostics = listOf(
                    GraphMdSearchDiagnostic("GRAPHMD_PARAM", "type", exception.message ?: "Invalid parameter."),
                ),
            )
        }
        val (workspace, engine) = stableSearchWorkspace()
        val compilation = workspace.compilation
        val result = runSearchSuspend {
            engine.queryGmql(
                params.query,
                parameters,
                GmqlExecutionOptions(profile = GmqlExecutionProfile.SERVER),
            )
        }
        val diagnostics = compilation.diagnostics
            .filter { it.severity == Severity.Error }
            .map {
                GraphMdSearchDiagnostic(
                    code = "GRAPHMD_COMPILE",
                    kind = it.category.name.lowercase(),
                    message = it.message,
                )
            } + result.diagnostics.map { it.toSearchDiagnostic() }
        val columns = result.columns.map { GraphMdSearchColumn(it.name, it.type.wireName()) }
        val idColumn = result.columns.indexOfFirst {
            it.name.equals("id", ignoreCase = true) || it.name.equals("nodeId", ignoreCase = true)
        }
        val rows = result.rows.map { row ->
            val relation = row.values.filterIsInstance<GmqlValue.RelationValue>().firstOrNull()
                ?.id
                ?.let { relationId -> engine.graph.relationAssertions.firstOrNull { it.id == relationId } }
            val nodeId = row.values.filterIsInstance<GmqlValue.NodeValue>().firstOrNull()?.id?.value
            val idValue = idColumn.takeIf { it >= 0 }
                ?.let { row.values.getOrNull(it) as? GmqlValue.StringValue }
                ?.value
            val relationByStableKey = idValue?.let { stableKey ->
                engine.graph.relationAssertions.firstOrNull { it.stableKey.value == stableKey }
            }
            GraphMdSearchRow(
                values = row.values.map(GmqlValue::toWireValue),
                location = relation?.source?.let { sourceLocation(it, workspace.documents) }
                    ?: relationByStableKey?.source?.let { sourceLocation(it, workspace.documents) }
                    ?: nodeId?.let { nodeLocation(it, workspace.documents) }
                    ?: idValue?.let { nodeLocation(it, workspace.documents) },
            )
        }
        return GraphMdSearchResponse(columns, rows, diagnostics)
    }

    fun searchMetadata(): GraphMdSearchMetadata {
        val compiled = compiledWorkspace()
        return GraphMdSearchMetadata(
            nodeTypes = compiled.nodeTypes
                .sortedBy { it.id }
                .map { type ->
                    GraphMdSearchNodeType(
                        type.id,
                        type.props.entries
                            .sortedBy { it.key }
                            .map { (name, schema) -> schema.toSearchProperty(name) },
                    )
                },
            relationTypes = compiled.relTypes
                .sortedBy { it.id }
                .map { type ->
                    GraphMdSearchRelationType(
                        id = type.id,
                        sourceTypes = type.from?.sorted(),
                        targetTypes = type.to?.sorted(),
                        properties = type.props.entries
                            .sortedBy { it.key }
                            .map { (name, schema) -> schema.toSearchProperty(name) },
                    )
                },
            timelines = compiled.timelines.map { it.id }.distinct().sorted(),
        )
    }

    private fun nodeLocation(
        nodeId: String,
        documents: List<IndexedDocument>,
    ): GraphMdSearchLocation? =
        resolve(ReferenceTargetKind.Node, nodeId, documents).firstOrNull()?.let { definition ->
            GraphMdSearchLocation(
                definition.uri,
                definition.range(),
            )
        }

    private fun sourceLocation(
        source: SourceInfo,
        documents: List<IndexedDocument>,
    ): GraphMdSearchLocation? {
        val document = documents.firstOrNull { indexed ->
            indexed.path.toString() == source.path
        } ?: return null
        return GraphMdSearchLocation(
            document.uri,
            source.range?.let(document::bodyRangeOf) ?: document.rangeOf(SourceRange(0, 0)),
        )
    }

    private fun List<IndexedDocument>.toSourceDocuments(): List<SourceDocument> =
        this
            .filter { it.isGraphDocumentCandidate() }
            .map { SourceDocument(it.text, it.path.toString()) }

    private fun invalidateCompilation() {
        workspaceGeneration++
        compiledCache = null
        searchEngineCache = null
    }

    private fun compiledWorkspace(): GraphCompilationResult =
        compiledWorkspaceSnapshot().compilation

    private fun compiledWorkspaceSnapshot(): CompiledWorkspace {
        while (true) {
            val snapshot = synchronized(this) {
                compiledCache
                    ?.takeIf { it.generation == workspaceGeneration }
                    ?.let { return it }
                WorkspaceSnapshot(workspaceGeneration, documents.values.toList())
            }
            val candidate = CompiledWorkspace(
                generation = snapshot.generation,
                documents = snapshot.documents,
                compilation = compileSources(snapshot.documents.toSourceDocuments()),
            )
            val current = synchronized(this) {
                if (workspaceGeneration != snapshot.generation) {
                    null
                } else {
                    compiledCache
                        ?.takeIf { it.generation == snapshot.generation }
                        ?: candidate.also { compiledCache = it }
                }
            }
            if (current != null) return current
        }
    }

    private fun stableSearchWorkspace(): Pair<CompiledWorkspace, GraphSearchEngine> {
        while (true) {
            val workspace = compiledWorkspaceSnapshot()
            val engine = searchEngine(workspace)
            if (synchronized(this) { workspaceGeneration == workspace.generation }) {
                return workspace to engine
            }
        }
    }

    private fun searchEngine(workspace: CompiledWorkspace): GraphSearchEngine {
        synchronized(this) {
            searchEngineCache
                ?.takeIf { it.generation == workspace.generation }
                ?.let { return it.engine }
        }
        val candidate = GraphSearchEngine.build(
            workspace.compilation,
            workspace.documents.toSourceDocuments(),
        )
        return synchronized(this) {
            if (workspaceGeneration != workspace.generation) {
                candidate
            } else {
                searchEngineCache
                    ?.takeIf { it.generation == workspace.generation }
                    ?.engine
                    ?: candidate.also {
                        searchEngineCache = CachedSearchEngine(workspace.generation, it)
                    }
            }
        }
    }

    private fun frontMatterScalar(text: String, key: String): String? {
        val frontMatterEnd = text.indexOf("\n---", startIndex = 3).takeIf { it >= 0 } ?: text.length
        val match = Regex(
            """(?m)^${Regex.escape(key)}\s*:\s*(?:"((?:\\.|[^"\\])*)"|'((?:''|[^'])*)'|([^#\r\n]+))""",
        )
            .find(text.substring(0, frontMatterEnd))
            ?: return null
        return (
            match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value?.trim()
            )?.takeIf { it.isNotEmpty() }
    }

    private fun completionIds(kind: ReferenceTargetKind): List<String> {
        return when (kind) {
            ReferenceTargetKind.Node -> definitionsCompatibleWith(kind).map { it.id }
            ReferenceTargetKind.Media -> definitionsOf(kind).map { it.id }
            ReferenceTargetKind.NodeType, ReferenceTargetKind.RelType -> definitionsOf(kind).map { it.id }
            ReferenceTargetKind.Timeline -> definitionsOf(kind).map { it.id }
        }.distinct().sorted()
    }

    private fun unambiguousDefinitionIds(kind: ReferenceTargetKind): List<String> =
        definitionsOf(kind)
            .groupBy { it.id }
            .filterValues { definitions -> definitions.size == 1 }
            .keys
            .sorted()

    private fun resolve(kind: ReferenceTargetKind, id: String): List<IndexedDefinition> {
        return definitionsCompatibleWith(kind).filter { it.id == id }
    }

    private fun resolve(
        kind: ReferenceTargetKind,
        id: String,
        documents: List<IndexedDocument>,
    ): List<IndexedDefinition> {
        return definitionsCompatibleWith(kind, documents).filter { it.id == id }
    }

    private fun resolveProperty(reference: PropertyReference): List<IndexedPropertyDefinition> =
        resolveProperty(reference, documentsSnapshot())

    private fun resolveProperty(
        reference: PropertyReference,
        documents: List<IndexedDocument>,
    ): List<IndexedPropertyDefinition> {
        fun resolveOwner(
            ownerId: String,
            visited: Set<String>,
        ): List<IndexedPropertyDefinition> {
            if (ownerId in visited) return emptyList()
            val ownerDocuments = documents.filter { indexed ->
                val parsed = indexed.analysis.parsed.document
                when (reference.ownerKind) {
                    PropertyOwnerKind.NodeType -> parsed is NodeTypeDocument && parsed.id == ownerId
                    PropertyOwnerKind.RelType -> parsed is RelTypeDocument && parsed.id == ownerId
                }
            }
            val localDefinitions = ownerDocuments.flatMap { indexed ->
                indexed.analysis.propertyDefinitions
                    .filter {
                        it.ownerKind == reference.ownerKind &&
                            it.ownerId == ownerId &&
                            it.name == reference.name
                    }
                    .map { IndexedPropertyDefinition(indexed, it) }
            }
            if (localDefinitions.isNotEmpty()) return localDefinitions

            val parentIds = ownerDocuments.flatMap { indexed ->
                when (val parsed = indexed.analysis.parsed.document) {
                    is NodeTypeDocument -> parsed.extends.takeIf { reference.ownerKind == PropertyOwnerKind.NodeType }.orEmpty()
                    is RelTypeDocument -> parsed.extends.takeIf { reference.ownerKind == PropertyOwnerKind.RelType }.orEmpty()
                    else -> emptyList()
                }
            }.distinct()
            return parentIds.flatMap { parentId ->
                resolveOwner(parentId, visited + ownerId)
            }
        }

        return resolveOwner(reference.ownerId, emptySet())
            .distinctBy {
                Triple(
                    it.document.uri,
                    it.definition.range.start,
                    it.definition.range.end,
                )
            }
    }

    private fun nodeTypeSchema(id: String): NormalizedNodeType? {
        return compiledWorkspace().nodeTypes.firstOrNull { it.id == id }
    }

    private fun relTypeSchema(id: String): NormalizedRelType? {
        return compiledWorkspace().relTypes.firstOrNull { it.id == id }
    }

    private fun timelineIds(): List<String> {
        return compiledWorkspace().timelines.map { it.id }.sorted()
    }

    private fun definitionsOf(kind: ReferenceTargetKind): List<IndexedDefinition> {
        val snapshot = synchronized(this) { documents.values.toList() }
        return definitionsOf(kind, snapshot)
    }

    private fun definitionsCompatibleWith(kind: ReferenceTargetKind): List<IndexedDefinition> {
        val snapshot = synchronized(this) { documents.values.toList() }
        return definitionsCompatibleWith(kind, snapshot)
    }

    private fun definitionsCompatibleWith(
        kind: ReferenceTargetKind,
        documents: List<IndexedDocument>,
    ): List<IndexedDefinition> {
        return documents.flatMap { indexed ->
            indexed.analysis.definitions
                .filter { kind.acceptsDefinition(it.kind) }
                .map { IndexedDefinition(indexed.uri, indexed.path, it.id, it.range, indexed, it.kind) }
        }
    }

    private fun symbolDefinitions(
        kind: ReferenceTargetKind,
        id: String,
        documents: List<IndexedDocument>,
    ): List<IndexedDefinition> {
        return documents.flatMap { indexed ->
            indexed.analysis.definitions
                .filter { it.kind.sharesSymbolNamespaceWith(kind) && it.id == id }
                .map { IndexedDefinition(indexed.uri, indexed.path, it.id, it.range, indexed, it.kind) }
        }
    }

    private fun definitionsOf(
        kind: ReferenceTargetKind,
        documents: List<IndexedDocument>,
    ): List<IndexedDefinition> {
        return documents.flatMap { indexed ->
            indexed.analysis.definitions
                .filter { it.kind == kind }
                .map { IndexedDefinition(indexed.uri, indexed.path, it.id, it.range, indexed, it.kind) }
        }
    }

    private fun inferredDiagnosticLspRange(document: IndexedDocument, diagnostic: Diagnostic): Range? {
        if (diagnostic.category == DiagnosticCategory.ReferenceError) {
            val reference = referenceTargetForDiagnostic(diagnostic.message)
            val sourceRange = reference?.let { target ->
                document.analysis.references.firstOrNull { ref ->
                    ref.kind == target.kind &&
                        ref.targetId == target.id &&
                        (target.field == null || ref.field == target.field)
                }?.range
            }
            if (sourceRange != null) return document.analysisRangeOf(sourceRange)
        }
        unknownFieldName(diagnostic.message)?.let { field -> document.yamlFieldKeyRange(field)?.let { return it } }
        Regex("""Unknown document kind: (.+)""").matchEntire(diagnostic.message)?.let {
            return document.yamlScalarRange("kind", it.groupValues[1])
        }
        Regex("""Unknown prop type: (.+)""").matchEntire(diagnostic.message)?.let {
            return document.yamlScalarRange("type", it.groupValues[1])
        }
        Regex("""Unknown (?:timecode type|mapping kind): (.+)""").matchEntire(diagnostic.message)?.let {
            val field = if (diagnostic.message.startsWith("Unknown timecode")) "type" else "kind"
            return document.yamlScalarRange(field, it.groupValues[1])
        }
        Regex("""(?:Node|NodeType|RelType|Timeline) id must be unique: (.+)""").matchEntire(diagnostic.message)?.let {
            return document.yamlScalarRange("id", it.groupValues[1])
        }
        if (
            diagnostic.message.startsWith("id MUST match ") ||
            diagnostic.message == "RelType id MUST NOT contain whitespace"
        ) {
            document.analysis.definitions.singleOrNull()?.range
                ?.let(document::analysisRangeOf)
                ?.let { return it }
            frontMatterScalar(document.text, "id")
                ?.let { document.yamlScalarRange("id", it) }
                ?.let { return it }
            document.analysis.parsed.document?.id
                ?.let { document.yamlScalarRange("id", it) }
                ?.let { return it }
        }
        Regex("""Unknown property ([A-Za-z_][A-Za-z0-9_.:-]*) on .+""").matchEntire(diagnostic.message)?.let {
            return document.propertyAssignmentRange(it.groupValues[1])
        }
        if (Regex("""Required property missing after normalization: .+""").matches(diagnostic.message)) {
            document.yamlTopLevelFieldKeyRange("props")?.let { return it }
            val offset = document.frontMatterClosingOffset() ?: 0
            return document.rangeOf(SourceRange(offset, offset))
        }
        if (diagnostic.message.endsWith(" is required") || diagnostic.message == "Media requires url") {
            val offset = document.frontMatterClosingOffset() ?: 0
            return document.rangeOf(SourceRange(offset, offset))
        }
        document.syntaxMarkerRange(diagnostic.message)?.let { return it }
        return null
    }

    private fun referenceTargetForDiagnostic(message: String): DiagnosticReferenceTarget? {
        Regex("""^Ambiguous (Node|NodeType|RelType|Timeline) reference: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(referenceTargetKind(it.groupValues[1]), it.groupValues[2], null)
        }
        Regex("""^Expected (Node|NodeType|RelType|Timeline) but found .+: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(referenceTargetKind(it.groupValues[1]), it.groupValues[2], null)
        }
        Regex("""^Unknown NodeType: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.NodeType, it.groupValues[1], "type")
        }
        Regex("""^Unknown parent NodeType: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.NodeType, it.groupValues[1], "extends")
        }
        Regex("""^Unknown RelType: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.RelType, it.groupValues[1], "relation.type")
        }
        Regex("""^Unknown parent RelType: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.RelType, it.groupValues[1], "extends")
        }
        Regex("""^Unknown Node target: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.Node, it.groupValues[1], "relation.target")
        }
        Regex("""^Unknown Timeline: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.Timeline, it.groupValues[1], null)
        }
        Regex("""^Unknown parent Timeline: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.Timeline, it.groupValues[1], "extends")
        }
        Regex("""^Unknown mapped Timeline: (.+)$""").matchEntire(message)?.let {
            return DiagnosticReferenceTarget(ReferenceTargetKind.Timeline, it.groupValues[1], null)
        }
        return null
    }

    private fun referenceTargetKind(name: String): ReferenceTargetKind = when (name) {
        "Node" -> ReferenceTargetKind.Node
        "NodeType" -> ReferenceTargetKind.NodeType
        "RelType" -> ReferenceTargetKind.RelType
        "Timeline" -> ReferenceTargetKind.Timeline
        else -> error("Unknown reference target kind: $name")
    }
}

internal data class CompletionEntry(
    val label: String,
    val kind: CompletionItemKind,
    val insertText: String = label,
    val detail: String? = null,
    val insertTextFormat: InsertTextFormat = InsertTextFormat.PlainText,
)

private fun CompletionEntry.toCompletionItem(replacementRange: Range? = null): CompletionItem = CompletionItem(label).also { item ->
    item.kind = kind
    item.insertText = insertText
    item.detail = detail
    item.insertTextFormat = insertTextFormat
    if (replacementRange != null && insertText != label) {
        item.textEdit = Either.forLeft(TextEdit(replacementRange, insertText))
    }
    item.sortText = when (kind) {
        CompletionItemKind.Property, CompletionItemKind.Field -> "0-$label"
        CompletionItemKind.Reference -> "1-$label"
        else -> "2-$label"
    }
}

private fun propValueSnippet(
    schema: ResolvedPropSchema,
    separator: String,
    timelineId: (ResolvedPropSchema) -> String?,
): String = propValueSnippet(schema, separator, timelineId, 1).text

private data class PropValueSnippet(
    val text: String,
    val nextPlaceholder: Int,
)

private fun propValueSnippet(
    schema: ResolvedPropSchema,
    separator: String,
    timelineId: (ResolvedPropSchema) -> String?,
    placeholder: Int,
): PropValueSnippet = when (schema.type) {
    PropType.string -> PropValueSnippet("\"${snippetPlaceholder(placeholder, "value")}\"", placeholder + 1)
    PropType.text -> PropValueSnippet("\"${snippetPlaceholder(placeholder, "text")}\"", placeholder + 1)
    PropType.number -> PropValueSnippet("0", placeholder)
    PropType.instant -> PropValueSnippet(
        "{ timeline$separator${snippetPlaceholder(placeholder, timelineId(schema).orEmpty())}, " +
            "timecode$separator${snippetPlaceholder(placeholder + 1, "0")} }",
        placeholder + 2,
    )
    PropType.duration -> PropValueSnippet(
        "{ timeline$separator${snippetPlaceholder(placeholder, timelineId(schema).orEmpty())}, " +
            "from$separator${snippetPlaceholder(placeholder + 1, "0")}, " +
            "to$separator${snippetPlaceholder(placeholder + 2, "0")} }",
        placeholder + 3,
    )
    PropType.array -> {
        val element = schema.items?.let {
            propArrayElementSnippet(it, separator, timelineId, placeholder)
        } ?: PropValueSnippet(snippetPlaceholder(placeholder, "value"), placeholder + 1)
        PropValueSnippet("[ ${element.text} ]", element.nextPlaceholder)
    }
}

private fun propArrayElementSnippet(
    schema: ResolvedPropSchema,
    separator: String,
    timelineId: (ResolvedPropSchema) -> String?,
    placeholder: Int,
): PropValueSnippet = when (schema.type) {
    // Keep the existing numeric shortcut for instant array elements while still
    // generating structured snippets for object-valued element types.
    PropType.number, PropType.instant -> PropValueSnippet(snippetPlaceholder(placeholder, "0"), placeholder + 1)
    else -> propValueSnippet(schema, separator, timelineId, placeholder)
}

private fun snippetPlaceholder(index: Int, defaultValue: String): String =
    "${'$'}{$index:$defaultValue}"

private fun propValueSnippetFormat(schema: ResolvedPropSchema): InsertTextFormat =
    if (schema.type == PropType.number) InsertTextFormat.PlainText else InsertTextFormat.Snippet

private data class RelationReferenceCompletionContext(
    val targetId: String?,
    val relType: String?,
)

internal class FrontMatterCompletionResolver(
    private val text: String,
    private val offset: Int,
    private val parsedDocument: GraphDocument?,
    private val nodeTypeIds: List<String>,
    private val relTypeIds: List<String>,
    private val timelineIds: List<String>,
    private val nodePropsSchema: Map<String, ResolvedPropSchema> = emptyMap(),
) {
    fun resolve(): List<CompletionEntry>? {
        val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n')
        val normalizedOffset = text
            .take(offset.coerceIn(0, text.length))
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .length
        val lines = normalizedText.split('\n')
        if (lines.firstOrNull() != "---") return null
        val endLine = lines.drop(1).indexOfFirst { it == "---" || it == "..." }.let { if (it >= 0) it + 1 else -1 }
        if (endLine < 0) return null
        val lineStarts = computeLineStarts(lines)
        if (normalizedOffset >= lineStarts[endLine] + lines[endLine].length) return null
        val lineIndex = lineStarts.indexOfLast { it <= normalizedOffset }.coerceAtLeast(0)
        if (lineIndex == 0 || lineIndex >= endLine) return null
        val line = lines[lineIndex]
        val indent = indentOf(line)
        val trimmed = line.trimStart()
        val cursorInLine = normalizedOffset - lineStarts[lineIndex]
        val beforeCursor = line.take(cursorInLine.coerceIn(0, line.length))
        val currentKeyPrefix = trimmed.takeWhile { it != ':' && !it.isWhitespace() }
        val usedTopLevelKeys = siblingKeysAtIndent(lines, lineIndex, 0)
        val documentKind = parsedDocument?.kind ?: inferredDocumentKind(lines, endLine)

        if (trimmed.startsWith("-")) {
            return listValueCompletions(lines, lineIndex, beforeCursor, documentKind)
        }

        val keyMatch = Regex("""^([A-Za-z][A-Za-z0-9_-]*)?\s*:?(.*)$""").matchEntire(trimmed) ?: return null
        val keyCandidate = keyMatch.groupValues[1]
        val hasColon = ':' in trimmed
        if (!hasColon && indent == 0) {
            return topLevelKeyCompletions(keyCandidate, usedTopLevelKeys, documentKind)
        }

        val path = contextPath(lines, lineIndex, indent, hasColon)
        val valuePrefix = if (hasColon) scalarPrefix(beforeCursor.substringAfter(':', "")) else ""
        nodePropsYamlCompletions(
            lines,
            lineIndex,
            indent,
            path,
            hasColon,
            currentKeyPrefix,
            valuePrefix,
            documentKind,
        )?.let { return it }
        return when {
            indent == 0 && keyCandidate.isNotEmpty() && !hasColon ->
                topLevelKeyCompletions(keyCandidate, usedTopLevelKeys, documentKind)
            hasColon && path == listOf("kind") -> enumCompletions(valuePrefix, listOf("Node", "Media", "NodeType", "RelType", "Timeline"), "kind")
            hasColon && path == listOf("type") && documentKind in setOf(DocumentKind.Node, DocumentKind.Media) ->
                idCompletions(valuePrefix, nodeTypeIds, "NodeType")
            hasColon && path == listOf("extends") && documentKind == DocumentKind.NodeType ->
                idCompletions(valuePrefix, nodeTypeIds, "NodeType")
            hasColon && path == listOf("extends") && documentKind == DocumentKind.RelType ->
                idCompletions(valuePrefix, relTypeIds, "RelType")
            hasColon && path == listOf("extends") && documentKind == DocumentKind.Timeline ->
                idCompletions(valuePrefix, timelineIds, "Timeline")
            hasColon && documentKind == DocumentKind.Timeline && "mappings" in path && path.lastOrNull() in setOf("from", "to") ->
                idCompletions(valuePrefix, timelineIds, "Timeline")
            hasColon && (path == listOf("from") || path == listOf("to")) -> idCompletions(valuePrefix, nodeTypeIds, "NodeType")
            hasColon && path.lastOrNull() == "required" && isPropSchemaPath(path.dropLast(1), documentKind) ->
                enumCompletions(valuePrefix, listOf("true", "false"), "boolean")
            hasColon && path == listOf("timecode", "type") ->
                enumCompletions(valuePrefix, listOf("number"), "timecode type")
            hasColon && path.lastOrNull() == "type" &&
                documentKind in setOf(DocumentKind.NodeType, DocumentKind.RelType) &&
                isPropSchemaPath(path.dropLast(1), documentKind) ->
                enumCompletions(valuePrefix, listOf("number", "string", "text", "instant", "duration", "array"), "prop type")
            hasColon && path.lastOrNull() == "timeline" && isPropSchemaPath(path.dropLast(1), documentKind) ->
                timelineSelectorCompletions(valuePrefix)
            hasColon && valuePrefix.isEmpty() -> nestedKeyCompletions(path, "", lines, lineIndex, documentKind)
            indent == 0 -> topLevelKeyCompletions(keyCandidate, usedTopLevelKeys, documentKind)
            else -> nestedKeyCompletions(path, currentKeyPrefix, lines, lineIndex, documentKind)
        }
    }

    private fun topLevelKeyCompletions(
        prefix: String,
        usedKeys: Set<String>,
        kind: DocumentKind?,
    ): List<CompletionEntry> {
        val keys = mutableListOf("id", "kind")
        when (kind) {
            DocumentKind.Node -> keys += listOf("type", "validTime", "props")
            DocumentKind.Media -> keys += listOf("type", "url", "validTime", "props")
            DocumentKind.NodeType -> keys += listOf("extends", "props")
            DocumentKind.RelType -> keys += listOf("extends", "from", "to", "props")
            DocumentKind.Timeline -> keys += listOf("extends", "timecode", "mappings", "props")
            null -> keys += listOf("type", "url", "validTime", "extends", "from", "to", "props", "timecode", "mappings")
        }
        val filteredKeys = keys.distinct().filter { key ->
            key.startsWith(prefix) && (key !in usedKeys || key == prefix)
        }
        return filteredKeys.map {
            CompletionEntry(it, CompletionItemKind.Field, "$it: ")
        }
    }

    private fun nodePropsYamlCompletions(
        lines: List<String>,
        lineIndex: Int,
        indent: Int,
        path: List<String>,
        hasColon: Boolean,
        keyPrefix: String,
        valuePrefix: String,
        documentKind: DocumentKind?,
    ): List<CompletionEntry>? {
        if (documentKind !in setOf(DocumentKind.Node, DocumentKind.Media)) return null
        if (nodePropsSchema.isEmpty()) return null
        if (path.firstOrNull() != "props") return null

        val rawPath = path.drop(1)
        if (rawPath.isEmpty()) {
            if (hasColon) return null
            return yamlObjectKeyCompletions(nodePropsSchema, emptyList(), lines, lineIndex, indent, keyPrefix)
        }

        if (!hasColon) {
            val currentContainer = nodePropsContainer(rawPath) ?: return null
            return yamlObjectKeyCompletions(
                currentContainer.properties,
                currentContainer.specialKeys,
                lines,
                lineIndex,
                indent,
                keyPrefix,
            )
        }

        val currentKey = rawPath.last()
        val parentContainer = nodePropsContainer(rawPath.dropLast(1)) ?: return null
        val schema = parentContainer.properties[currentKey]
        return when {
            schema != null -> typedValueCompletions(schema, valuePrefix, yaml = true)
            currentKey == "timeline" && currentKey in parentContainer.specialKeys ->
                idCompletions(valuePrefix, allowedTimelineIds(parentContainer.ownerSchema), "Timeline")
            else -> null
        }
    }

    private fun yamlObjectKeyCompletions(
        properties: Map<String, ResolvedPropSchema>,
        specialKeys: List<String>,
        lines: List<String>,
        lineIndex: Int,
        indent: Int,
        prefix: String,
    ): List<CompletionEntry>? {
        val usedKeys = siblingKeysAtIndent(lines, lineIndex, indent)
        val entries = (properties.keys + specialKeys)
            .distinct()
            .filterNot { it in usedKeys }
            .filter { it.startsWith(prefix) }
            .sorted()
            .map { key ->
                val schema = properties[key]
                CompletionEntry(
                    key,
                    CompletionItemKind.Field,
                    schema?.let {
                        "$key: ${propValueSnippet(it, ": ") { allowedTimelineIds(it).firstOrNull() }}"
                    } ?: "$key: ",
                    schema?.type?.name ?: "property",
                    schema?.let(::propValueSnippetFormat) ?: InsertTextFormat.PlainText,
                )
            }
        return entries.ifEmpty { null }
    }

    private fun nestedKeyCompletions(
        path: List<String>,
        prefix: String,
        lines: List<String>,
        lineIndex: Int,
        documentKind: DocumentKind?,
    ): List<CompletionEntry>? {
        if (
            path.lastOrNull() == "validTime" &&
            lines[lineIndex].isBlank() &&
            isDirectListItemPosition(lines, lineIndex)
        ) {
            return listOf(CompletionEntry("timeline", CompletionItemKind.Field, "- timeline: ", "validTime"))
        }
        val keys = when {
            path.lastOrNull() == "validTime" -> listOf("timeline", "from", "to")
            path.takeLast(2).let { it == listOf("validTime", "from") || it == listOf("validTime", "to") } ->
                listOf("value", "timecode")
            isPropSchemaPath(path, documentKind) -> when (siblingScalarValue(lines, lineIndex, "type")) {
                "instant", "duration" -> listOf("type", "required", "timeline")
                "array" -> listOf("type", "required", "items")
                else -> listOf("type", "required")
            }
            path == listOf("timecode") -> listOf("type")
            path == listOf("mappings") -> when (siblingScalarValue(lines, lineIndex, "kind")) {
                "offset" -> listOf("kind", "from", "to", "offset")
                else -> listOf("kind", "from", "to", "offset")
            }
            else -> return null
        }
        val usedKeys = siblingKeysAtIndent(lines, lineIndex, indentOf(lines[lineIndex])).toMutableSet()
        if (path.lastOrNull() == "validTime") {
            enclosingListItemKey(lines, lineIndex)?.let(usedKeys::add)
        }
        return keys
            .filter { it.startsWith(prefix) && (it !in usedKeys || it == prefix) }
            .map { key ->
                val schemaField = isPropSchemaPath(path, documentKind)
                val insertText = when {
                    schemaField && key == "type" -> "type: \${1:string}"
                    schemaField && key == "required" -> "required: \${1:false}"
                    schemaField && key == "items" -> "items: \${1:string}"
                    else -> "$key: "
                }
                CompletionEntry(
                    key,
                    CompletionItemKind.Field,
                    insertText,
                    if (schemaField) "property schema" else null,
                    if (schemaField && key in setOf("type", "required", "items")) {
                        InsertTextFormat.Snippet
                    } else {
                        InsertTextFormat.PlainText
                    },
                )
            }
    }

    private fun enumCompletions(prefix: String, values: List<String>, detail: String): List<CompletionEntry> =
        values.filter { it.startsWith(prefix) }.map { CompletionEntry(it, CompletionItemKind.EnumMember, it, detail) }

    private fun idCompletions(prefix: String, values: List<String>, detail: String): List<CompletionEntry> =
        values.distinct().filter { it.startsWith(prefix) }.map { CompletionEntry(it, CompletionItemKind.Reference, it, detail) }

    private fun timelineSelectorCompletions(prefix: String): List<CompletionEntry> {
        val entries = mutableListOf<CompletionEntry>()
        entries += idCompletions(prefix, timelineIds, "Timeline")
        return entries
    }

    private fun typedValueCompletions(schema: ResolvedPropSchema, prefix: String, yaml: Boolean): List<CompletionEntry>? {
        if (prefix.isNotEmpty()) return null
        val separator = if (yaml) ": " else " = "
        val entries = when (schema.type) {
            PropType.string -> listOf(CompletionEntry("string", CompletionItemKind.Value, "\"\${1:value}\"", "string", InsertTextFormat.Snippet))
            PropType.text -> listOf(
                CompletionEntry("text", CompletionItemKind.Value, "\"\${1:text}\"", "text (default)", InsertTextFormat.Snippet),
                CompletionEntry("localized text", CompletionItemKind.Struct, "{ default$separator\"\${1:text}\" }", "localized text", InsertTextFormat.Snippet),
            )
            PropType.number -> listOf(CompletionEntry("0", CompletionItemKind.Value, "0", "number"))
            PropType.instant -> listOf(
                CompletionEntry("0", CompletionItemKind.Value, "0", "instant timecode"),
                CompletionEntry(
                    "instant",
                    CompletionItemKind.Struct,
                    propValueSnippet(schema, separator) { allowedTimelineIds(it).firstOrNull() },
                    "instant",
                    InsertTextFormat.Snippet,
                ),
            )
            PropType.duration -> listOf(
                CompletionEntry(
                    "duration",
                    CompletionItemKind.Struct,
                    propValueSnippet(schema, separator) { allowedTimelineIds(it).firstOrNull() },
                    "duration",
                    InsertTextFormat.Snippet,
                ),
            )
            PropType.array -> {
                listOf(
                    CompletionEntry(
                        "array",
                        CompletionItemKind.Struct,
                        propValueSnippet(schema, separator) { allowedTimelineIds(it).firstOrNull() },
                        schema.items?.let { "array<${it.type.name}>" } ?: "array",
                        InsertTextFormat.Snippet,
                    ),
                )
            }
        }
        return entries
    }

    private fun listValueCompletions(
        lines: List<String>,
        lineIndex: Int,
        beforeCursor: String,
        documentKind: DocumentKind?,
    ): List<CompletionEntry>? {
        val parentKey = enclosingListKey(lines, lineIndex) ?: return null
        val afterDash = beforeCursor.substringAfter('-', "")
        val hasSpaceAfterDash = afterDash.firstOrNull()?.isWhitespace() == true
        val prefix = afterDash.trimStart()
        return when (parentKey) {
            "extends" -> when (documentKind) {
                DocumentKind.NodeType -> idCompletions(prefix, nodeTypeIds, "NodeType")
                DocumentKind.RelType -> idCompletions(prefix, relTypeIds, "RelType")
                DocumentKind.Timeline -> idCompletions(prefix, timelineIds, "Timeline")
                else -> null
            }
            "from", "to" -> idCompletions(prefix, if (documentKind == DocumentKind.Timeline) timelineIds else nodeTypeIds, if (documentKind == DocumentKind.Timeline) "Timeline" else "NodeType")
            "timeline" -> timelineSelectorCompletions(prefix)
            "validTime" -> {
                val key = prefix.substringBefore(':').trim()
                if (':' in prefix && key == "timeline") {
                    timelineSelectorCompletions(prefix.substringAfter(':').trimStart())
                } else {
                    listOf("timeline")
                        .filter { it.startsWith(key) }
                        .map {
                            CompletionEntry(
                                it,
                                CompletionItemKind.Field,
                                (if (hasSpaceAfterDash) "" else " ") + "$it: ",
                                "validTime",
                            )
                        }
                }
            }
            "mappings" -> listOf(CompletionEntry("kind", CompletionItemKind.Field, "kind: offset", "offset mapping"))
            else -> null
        }
    }

    private fun contextPath(
        lines: List<String>,
        currentLineIndex: Int,
        currentIndent: Int,
        includeCurrentLine: Boolean,
    ): List<String> {
        val stack = ArrayDeque<Pair<Int, String>>()
        val lastLineIndex = if (includeCurrentLine) currentLineIndex else currentLineIndex - 1
        for (index in 1..lastLineIndex.coerceAtLeast(0)) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("- ")) continue
            val key = trimmed.substringBefore(':').takeIf { ':' in trimmed } ?: continue
            val indent = indentOf(line)
            while (stack.isNotEmpty() && stack.last().first >= indent) {
                stack.removeLast()
            }
            stack.addLast(indent to key)
        }
        if (!includeCurrentLine) {
            while (stack.isNotEmpty() && stack.last().first >= currentIndent) {
                stack.removeLast()
            }
        }
        return stack.map { it.second }
    }

    private fun enclosingListKey(lines: List<String>, lineIndex: Int): String? {
        val currentIndent = indentOf(lines[lineIndex])
        for (index in lineIndex - 1 downTo 1) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val indent = indentOf(line)
            if (indent < currentIndent && trimmed.endsWith(":")) {
                return trimmed.removeSuffix(":").trim()
            }
        }
        return null
    }

    private fun isDirectListItemPosition(lines: List<String>, lineIndex: Int): Boolean {
        val currentIndent = indentOf(lines[lineIndex])
        for (index in lineIndex - 1 downTo 1) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val indent = indentOf(line)
            if (indent >= currentIndent) continue
            if (trimmed.startsWith("-")) return false
            return Regex("""validTime:(?:[ \t]+#.*)?""").matches(trimmed)
        }
        return false
    }

    private fun enclosingListItemKey(lines: List<String>, lineIndex: Int): String? {
        val currentIndent = indentOf(lines[lineIndex])
        for (index in lineIndex - 1 downTo 1) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val indent = indentOf(line)
            if (indent >= currentIndent) continue
            if (!trimmed.startsWith("-")) return null
            val item = trimmed.removePrefix("-").trimStart()
            if (':' !in item) return null
            return item.substringBefore(':').trim().takeIf {
                Regex("""[A-Za-z][A-Za-z0-9_-]*""").matches(it)
            }
        }
        return null
    }

    private fun siblingKeysAtIndent(lines: List<String>, lineIndex: Int, indent: Int): Set<String> {
        val keys = linkedSetOf<String>()
        for (index in lineIndex - 1 downTo 1) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val lineIndent = indentOf(line)
            if (lineIndent < indent) break
            if (lineIndent > indent) continue
            val key = trimmed.substringBefore(':').takeIf { ':' in trimmed } ?: continue
            keys += key
        }
        return keys
    }

    private fun siblingScalarValue(lines: List<String>, lineIndex: Int, key: String): String? {
        val currentIndent = indentOf(lines[lineIndex])
        for (index in lineIndex - 1 downTo 1) {
            val line = lines[index]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val indent = indentOf(line)
            if (indent < currentIndent) break
            if (indent != currentIndent) continue
            val parts = trimmed.split(':', limit = 2)
            if (parts.size != 2 || parts[0].trim() != key) continue
            val value = parts[1].trim()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun computeLineStarts(lines: List<String>): List<Int> {
        val starts = mutableListOf(0)
        var offset = 0
        lines.forEachIndexed { index, line ->
            if (index == lines.lastIndex) return@forEachIndexed
            offset += line.length + 1
            starts += offset
        }
        return starts
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }

    private fun isPropSchemaPath(path: List<String>, documentKind: DocumentKind?): Boolean =
        (documentKind == DocumentKind.NodeType || documentKind == DocumentKind.RelType) &&
            path.size >= 2 &&
            path.first() == "props" &&
            path.drop(2).all { it == "items" }

    private fun inferredDocumentKind(lines: List<String>, endLine: Int): DocumentKind? {
        val raw = lines
            .subList(1, endLine)
            .firstNotNullOfOrNull { line ->
                if (indentOf(line) != 0) return@firstNotNullOfOrNull null
                val parts = line.split(':', limit = 2)
                if (parts.size != 2 || parts[0].trim() != "kind") return@firstNotNullOfOrNull null
                scalarPrefix(parts[1]).takeIf { it.isNotEmpty() }
            }
        return DocumentKind.entries.firstOrNull { it.name == raw }
    }

    private fun scalarPrefix(raw: String): String {
        val value = raw.trimStart()
        val quote = value.firstOrNull().takeIf { it == '"' || it == '\'' }
        if (quote == null) {
            val commentStart = value.indices.firstOrNull { index ->
                value[index] == '#' && (index == 0 || value[index - 1].isWhitespace())
            }
            return value.substring(0, commentStart ?: value.length).trimEnd()
        }

        val content = StringBuilder()
        var index = 1
        while (index < value.length) {
            val character = value[index]
            if (quote == '"' && character == '\\' && index + 1 < value.length) {
                content.append(value[index + 1])
                index += 2
                continue
            }
            if (quote == '\'' && character == '\'' && value.getOrNull(index + 1) == '\'') {
                content.append('\'')
                index += 2
                continue
            }
            if (character == quote) break
            content.append(character)
            index++
        }
        return content.toString()
    }

    private fun nodePropsContainer(path: List<String>): NodePropsContainer? {
        var currentProperties = nodePropsSchema
        var currentSpecialKeys = emptyList<String>()
        var ownerSchema: ResolvedPropSchema? = null
        for (segment in path) {
            val schema = currentProperties[segment] ?: return null
            ownerSchema = schema
            currentProperties = nestedProperties(schema)
            currentSpecialKeys = specialKeys(schema)
        }
        return NodePropsContainer(currentProperties, currentSpecialKeys, ownerSchema)
    }

    private fun nestedProperties(schema: ResolvedPropSchema): Map<String, ResolvedPropSchema> = emptyMap()

    private fun specialKeys(schema: ResolvedPropSchema): List<String> {
        return when (schema.type) {
            PropType.instant -> listOf("timeline", "value", "timecode")
            PropType.duration -> listOf("timeline", "from", "to")
            else -> emptyList()
        }
    }

    private fun allowedTimelineIds(schema: ResolvedPropSchema?): List<String> {
        val selectors = listOfNotNull(schema?.timeline) + schema?.timelines.orEmpty()
        if (selectors.isEmpty()) return timelineIds
        val explicit = selectors.filterIsInstance<TimelineSelector.Id>().map { it.id }.toSet()
        return timelineIds.filter { it in explicit }
    }

    private data class NodePropsContainer(
        val properties: Map<String, ResolvedPropSchema>,
        val specialKeys: List<String>,
        val ownerSchema: ResolvedPropSchema? = null,
    )
}

internal data class PropsCompletionResult(
    val items: List<CompletionEntry>,
)

internal class PropsCompletionContextResolver(
    private val text: String,
    private val offset: Int,
    private val rootSchema: Map<String, ResolvedPropSchema>,
    private val timelineIds: List<String>,
    private val explicitBraceStart: Int? = null,
) {
    fun resolve(): PropsCompletionResult? {
        val propsStart = explicitBraceStart ?: findEnclosingPropsStart() ?: return null
        val prefix = text.substring(propsStart + 1, offset.coerceAtMost(text.length))
        val scanner = PropsPrefixScanner(rootSchema, timelineIds)
        return scanner.scan(prefix)
    }

    private fun findEnclosingPropsStart(): Int? {
        var searchIndex = offset.coerceAtMost(text.length)
        while (searchIndex >= 0) {
            val candidate = text.lastIndexOf("@props", searchIndex)
            if (candidate < 0) return null
            var braceIndex = candidate + "@props".length
            while (braceIndex < text.length && text[braceIndex].isWhitespace()) braceIndex++
            if (braceIndex < text.length && text[braceIndex] == '{') {
                if (offset > braceIndex && isInsideBraceRange(braceIndex, offset)) return braceIndex
            }
            searchIndex = candidate - 1
        }
        return null
    }

    private fun isInsideBraceRange(braceIndex: Int, currentOffset: Int): Boolean {
        var depth = 0
        var index = braceIndex
        var inString = false
        var escaped = false
        while (index < currentOffset) {
            val char = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return false
                    }
                }
            }
            index += 1
        }
        return depth > 0
    }
}

internal class PropsPrefixScanner(
    rootSchema: Map<String, ResolvedPropSchema>,
    private val timelineIds: List<String>,
) {
    private val frames = ArrayDeque<Frame>()
    private var currentKey: String? = null
    private var currentSchema: ResolvedPropSchema? = null
    private var expectingValue = false
    private var expectingDelimiter = false

    init {
        frames.addLast(Frame(rootSchema))
    }

    fun scan(prefix: String): PropsCompletionResult? {
        var index = 0
        while (index < prefix.length) {
            val char = prefix[index]
            if (char.isWhitespace()) {
                index += 1
                continue
            }
            if (expectingValue) {
                val valueResult = handleValue(prefix, index)
                if (valueResult.completion != null) return valueResult.completion
                index = valueResult.nextIndex
                continue
            }
            if (expectingDelimiter) {
                when (char) {
                    ',' -> {
                        expectingDelimiter = false
                        currentKey = null
                        currentSchema = null
                        index += 1
                    }
                    '}' -> {
                        frames.removeLastOrNull()
                        expectingDelimiter = true
                        currentKey = null
                        currentSchema = null
                        index += 1
                    }
                    else -> return null
                }
                continue
            }
            if (char == '}') {
                frames.removeLastOrNull()
                expectingDelimiter = true
                index += 1
                continue
            }
            if (!isIdentifierStart(char)) return null
            val tokenEnd = readIdentifier(prefix, index)
            val token = prefix.substring(index, tokenEnd)
            var cursor = tokenEnd
            while (cursor < prefix.length && prefix[cursor].isWhitespace()) cursor++
            if (cursor >= prefix.length) {
                return keyCompletion(token)
            }
            val schema = frames.lastOrNull()?.properties?.get(token)
            if (prefix[cursor] == '(') {
                val annotationEnd = findAnnotationEnd(prefix, cursor)
                if (annotationEnd == null) {
                    return annotationCompletion(prefix.substring(cursor + 1), schema)
                }
                cursor = annotationEnd
                while (cursor < prefix.length && prefix[cursor].isWhitespace()) cursor++
                if (cursor >= prefix.length) {
                    return PropsCompletionResult(
                        listOf(CompletionEntry("=", CompletionItemKind.Operator, "= ", "property value")),
                    )
                }
            }
            if (prefix[cursor] != '=') {
                return keyCompletion(token)
            }
            currentKey = token
            currentSchema = schema
            frames.lastOrNull()?.usedKeys?.add(token)
            expectingValue = true
            index = cursor + 1
        }
        return when {
            expectingValue -> valueCompletion("")
            expectingDelimiter -> null
            else -> keyCompletion("")
        }
    }

    private fun handleValue(prefix: String, index: Int): ValueParseResult {
        val char = prefix[index]
        if (char == '{') {
            val schema = currentSchema
            frames.addLast(Frame(nestedProperties(schema), specialKeys(schema), ownerSchema = schema))
            expectingValue = false
            expectingDelimiter = false
            currentKey = null
            currentSchema = null
            return ValueParseResult(index + 1, null)
        }
        if (char == '"') {
            val end = readQuoted(prefix, index)
            if (end < 0) return ValueParseResult(prefix.length, null)
            expectingValue = false
            expectingDelimiter = true
            return ValueParseResult(end, null)
        }
        if (char == '[') {
            val end = prefix.indexOf(']', index).let { if (it < 0) prefix.length else it + 1 }
            if (end >= prefix.length) return ValueParseResult(end, null)
            expectingValue = false
            expectingDelimiter = true
            return ValueParseResult(end, null)
        }
        if (isIdentifierStart(char) || char == '-' || char.isDigit()) {
            val end = readScalar(prefix, index)
            val token = prefix.substring(index, end)
            if (end >= prefix.length) {
                return ValueParseResult(end, valueCompletion(token))
            }
            expectingValue = false
            expectingDelimiter = true
            return ValueParseResult(end, null)
        }
        return ValueParseResult(prefix.length, valueCompletion(""))
    }

    private fun keyCompletion(prefix: String): PropsCompletionResult? {
        val frame = frames.lastOrNull() ?: return null
        val entries = (frame.properties.keys + frame.specialKeys)
            .distinct()
            .filterNot { it in frame.usedKeys }
            .filter { it.startsWith(prefix) }
            .sorted()
            .map { key ->
                val schema = frame.properties[key]
                CompletionEntry(
                    label = key,
                    kind = CompletionItemKind.Property,
                    insertText = schema?.let {
                        "$key = ${propValueSnippet(it, " = ") { allowedTimelineIds(it).firstOrNull() }}"
                    } ?: "$key = ",
                    detail = schema?.type?.name ?: "property",
                    insertTextFormat = schema?.let(::propValueSnippetFormat) ?: InsertTextFormat.PlainText,
                )
            }
        return if (entries.isEmpty()) null else PropsCompletionResult(entries)
    }

    private fun valueCompletion(prefix: String): PropsCompletionResult? {
        val key = currentKey ?: return null
        val schema = currentSchema
        val entries = when {
            schema != null -> typedValueCompletions(schema, prefix)
            key == "timeline" -> allowedTimelineIds(frames.lastOrNull()?.ownerSchema)
                .filter { it.startsWith(prefix) }
                .map { CompletionEntry(it, CompletionItemKind.Value, it, "timeline") }
            key in setOf("timecode", "from", "to") ->
                listOf(CompletionEntry("0", CompletionItemKind.Value, "0", "number")).filter { it.label.startsWith(prefix) }
            key == "value" ->
                listOf(CompletionEntry("string", CompletionItemKind.Value, "\"\${1:value}\"", "display value", InsertTextFormat.Snippet))
            else -> emptyList()
        }
        return if (entries.isEmpty()) null else PropsCompletionResult(entries)
    }

    private fun nestedProperties(schema: ResolvedPropSchema?): Map<String, ResolvedPropSchema> = emptyMap()

    private fun typedValueCompletions(schema: ResolvedPropSchema, prefix: String): List<CompletionEntry> {
        if (prefix.isNotEmpty() && schema.type !in setOf(PropType.number, PropType.instant)) return emptyList()
        return when (schema.type) {
            PropType.string -> listOf(CompletionEntry("string", CompletionItemKind.Value, "\"\${1:value}\"", "string", InsertTextFormat.Snippet))
            PropType.text -> listOf(
                CompletionEntry("text", CompletionItemKind.Value, "\"\${1:text}\"", "text (default)", InsertTextFormat.Snippet),
                CompletionEntry("localized text", CompletionItemKind.Struct, "{ default = \"\${1:text}\" }", "localized text", InsertTextFormat.Snippet),
            )
            PropType.number -> listOf(CompletionEntry("0", CompletionItemKind.Value, "0", "number")).filter { it.label.startsWith(prefix) }
            PropType.instant -> listOf(
                CompletionEntry("0", CompletionItemKind.Value, "0", "instant timecode"),
                CompletionEntry(
                    "{",
                    CompletionItemKind.Struct,
                    propValueSnippet(schema, " = ") { allowedTimelineIds(it).firstOrNull() },
                    "instant",
                    InsertTextFormat.Snippet,
                ),
            ).filter { prefix.isEmpty() || it.label.startsWith(prefix) }
            PropType.duration -> listOf(
                CompletionEntry(
                    "duration",
                    CompletionItemKind.Struct,
                    propValueSnippet(schema, " = ") { allowedTimelineIds(it).firstOrNull() },
                    "duration",
                    InsertTextFormat.Snippet,
                ),
            )
            PropType.array -> {
                listOf(
                    CompletionEntry(
                        "array",
                        CompletionItemKind.Struct,
                        propValueSnippet(schema, " = ") { allowedTimelineIds(it).firstOrNull() },
                        schema.items?.let { "array<${it.type.name}>" } ?: "array",
                        InsertTextFormat.Snippet,
                    ),
                )
            }
        }
    }

    private fun annotationCompletion(prefix: String, schema: ResolvedPropSchema?): PropsCompletionResult? {
        val current = prefix.substringAfterLast(',').trimStart()
        val entries = when {
            '=' !in current -> {
                val used = Regex("""(?:^|,)\s*([A-Za-z][A-Za-z0-9_-]*)\s*=""")
                    .findAll(prefix).map { it.groupValues[1] }.toSet()
                buildList {
                    if ("validTime" !in used && "validTime".startsWith(current)) {
                        add(CompletionEntry("validTime", CompletionItemKind.Property, "validTime=", "property validity"))
                    }
                    if (schema?.type == PropType.text && "key" !in used && "key".startsWith(current)) {
                        add(CompletionEntry("key", CompletionItemKind.Property, "key=\"\${1:locale}\"", "text key", InsertTextFormat.Snippet))
                    }
                }
            }
            current.substringBefore('=').trim() == "key" ->
                listOf(CompletionEntry("text key", CompletionItemKind.Value, "\"\${1:locale}\"", "text key", InsertTextFormat.Snippet))
            current.substringBefore('=').trim() == "validTime" -> {
                val valuePrefix = current.substringAfter('=').trimStart()
                val openBounds = valuePrefix.lastIndexOf('(')
                    .takeIf { it > valuePrefix.lastIndexOf(')') }
                if (openBounds != null) {
                    val boundsPrefix = valuePrefix.substring(openBounds + 1)
                    val currentBound = boundsPrefix.substringAfterLast(',').trimStart()
                    if ('=' in currentBound) {
                        listOf(CompletionEntry("0", CompletionItemKind.Value, "0", "timecode"))
                    } else {
                        val usedBounds = Regex("""(?:^|,)\s*(from|to)\s*=""")
                            .findAll(boundsPrefix).map { it.groupValues[1] }.toSet()
                        listOf("from", "to")
                            .filter { it !in usedBounds && it.startsWith(currentBound) }
                            .map { CompletionEntry(it, CompletionItemKind.Property, "$it=", "validTime bound") }
                    }
                } else {
                    val timelinePrefix = valuePrefix.substringAfterLast('[').substringAfterLast(',').trimStart()
                    allowedTimelineIds(schema).filter { it.startsWith(timelinePrefix) }
                        .map { CompletionEntry(it, CompletionItemKind.Reference, it, "Timeline") }
                }
            }
            else -> emptyList()
        }
        return entries.takeIf { it.isNotEmpty() }?.let(::PropsCompletionResult)
    }

    private fun findAnnotationEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> if (--depth == 0) return index + 1
                }
            }
        }
        return null
    }

    private fun specialKeys(schema: ResolvedPropSchema?): List<String> {
        return when (schema?.type) {
            PropType.instant -> listOf("timeline", "value", "timecode")
            PropType.duration -> listOf("timeline", "from", "to")
            else -> emptyList()
        }
    }

    private fun allowedTimelineIds(schema: ResolvedPropSchema?): List<String> {
        val selectors = listOfNotNull(schema?.timeline) + schema?.timelines.orEmpty()
        if (selectors.isEmpty()) return timelineIds
        val explicit = selectors.filterIsInstance<TimelineSelector.Id>().map { it.id }.toSet()
        return timelineIds.filter { it in explicit }
    }

    private fun readIdentifier(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length && isIdentifierPart(text[index])) index++
        return index
    }

    private fun readScalar(text: String, start: Int): Int {
        var index = start
        while (index < text.length && !text[index].isWhitespace() && text[index] != ',' && text[index] != '}') index++
        return index
    }

    private fun readQuoted(text: String, start: Int): Int {
        var index = start + 1
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return index + 1
            }
            index += 1
        }
        return -1
    }

    private fun isIdentifierStart(char: Char): Boolean = char.isLetter() || char == '_'

    private fun isIdentifierPart(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '.' || char == ':' || char == '-'

    private data class Frame(
        val properties: Map<String, ResolvedPropSchema>,
        val specialKeys: List<String> = emptyList(),
        val ownerSchema: ResolvedPropSchema? = null,
        val usedKeys: MutableSet<String> = linkedSetOf(),
    )

    private data class ValueParseResult(
        val nextIndex: Int,
        val completion: PropsCompletionResult?,
    )
}

internal data class RelationPropsContext(
    val relType: String,
    val braceStart: Int,
)

internal class RelationPropsCompletionContextResolver(
    private val text: String,
    private val offset: Int,
) {
    fun resolve(): RelationPropsContext? {
        return resolveCanonical()
    }

    private fun resolveCanonical(): RelationPropsContext? {
        var searchIndex = offset.coerceAtMost(text.length)
        while (searchIndex >= 0) {
            val relationStart = text.lastIndexOf("@link", searchIndex)
            if (relationStart < 0) return null
            var cursor = relationStart + "@link".length
            if (text.getOrNull(cursor) == '(') {
                cursor = balancedEnd(cursor, '(', ')') ?: return null
            }
            if (text.getOrNull(cursor) != '{') {
                searchIndex = relationStart - 1
                continue
            }
            val braceStart = cursor
            val braceEnd = balancedEnd(braceStart, '{', '}') ?: text.length
            if (offset <= braceStart || offset > braceEnd) {
                searchIndex = relationStart - 1
                continue
            }
            val labelOpen = braceEnd
            if (text.getOrNull(labelOpen) != '[') return null
            val closeLabel = findUnescaped(']', labelOpen + 1) ?: return null
            if (text.getOrNull(closeLabel + 1) != '(') return null
            val closeParen = findUnescaped(')', closeLabel + 2) ?: return null
            val relType = parseRelationTargetAndType(text.substring(closeLabel + 2, closeParen))?.second ?: return null
            return RelationPropsContext(relType, braceStart)
        }
        return null
    }

    private fun balancedEnd(start: Int, open: Char, close: Char): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    open -> depth++
                    close -> if (--depth == 0) return index + 1
                }
            }
        }
        return null
    }

    private fun findUnescaped(target: Char, start: Int): Int? {
        var escaped = false
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '\n') {
                return null
            } else if (char == target) {
                return index
            }
            index += 1
        }
        return null
    }

    private fun isInsideBraceRange(braceIndex: Int, currentOffset: Int): Boolean {
        var depth = 0
        var index = braceIndex
        var inString = false
        var escaped = false
        while (index < currentOffset) {
            val char = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) return false
                    }
                }
            }
            index += 1
        }
        return depth > 0
    }

    private fun parseRelationTargetAndType(value: String): Pair<String, String>? {
        val trimmed = value.trim()
        val separator = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (separator <= 0) return null
        val target = trimmed.substring(0, separator)
        val typePart = trimmed.substring(separator).trim()
        if (typePart.isEmpty()) return null
        if (typePart.first() == '"') {
            val relType = parseQuotedRelationType(typePart) ?: return null
            return target to relType
        }
        if (typePart.any { it == ' ' || it == '\t' || it == ')' }) return null
        return target to typePart
    }

    private fun parseQuotedRelationType(value: String): String? {
        val builder = StringBuilder()
        var index = 1
        var escaped = false
        while (index < value.length) {
            val char = value[index]
            if (escaped) {
                builder.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return if (value.substring(index + 1).trim().isEmpty()) builder.toString() else null
            } else {
                builder.append(char)
            }
            index += 1
        }
        return null
    }
}

private data class IndexedDocument(
    val uri: String,
    val path: Path,
    val text: String,
    val analysis: GraphDocumentAnalysis,
) {
    private val lineStarts: List<Int> = buildList {
        add(0)
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (text.getOrNull(index + 1) == '\n') index++
                    add(index + 1)
                }
                '\n' -> add(index + 1)
            }
            index++
        }
    }
    private val analysisLineStarts: List<Int> = buildList {
        add(0)
        analysis.text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    fun offsetAt(position: Position): Int {
        val lineStart = lineStarts.getOrElse(position.line) { text.length }
        return (lineStart + position.character).coerceAtMost(text.length)
    }

    fun analysisOffsetAt(position: Position): Int {
        val lineStart = analysisLineStarts.getOrElse(position.line) { analysis.text.length }
        return (lineStart + position.character).coerceAtMost(analysis.text.length)
    }

    fun completionReplacementRange(position: Position): Range {
        val lineStart = lineStarts.getOrElse(position.line) { text.length }
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\r' && text[lineEnd] != '\n') {
            lineEnd++
        }
        val line = text.substring(lineStart, lineEnd)
        val cursor = position.character.coerceIn(0, line.length)
        val token = COMPLETION_REPLACEMENT_TOKEN_REGEX.findAll(line).firstOrNull {
            it.range.first < cursor && cursor <= it.range.last + 1
        }
        val start = token?.range?.first ?: cursor
        val end = token?.range?.last?.plus(1) ?: cursor
        return Range(Position(position.line, start), Position(position.line, end))
    }

    fun rangeOf(sourceRange: SourceRange): Range {
        return Range(positionAt(sourceRange.start), positionAt(sourceRange.end))
    }

    fun analysisRangeOf(sourceRange: SourceRange): Range {
        return Range(analysisPositionAt(sourceRange.start), analysisPositionAt(sourceRange.end))
    }

    fun bodyRangeOf(sourceRange: SourceRange): Range {
        val bodyStart = positionAt(analysis.frontMatterEndOffset)
        val normalizedBody = analysis.parsed.document?.body.orEmpty()
        fun bodyPosition(offset: Int): Position {
            val safeOffset = offset.coerceIn(0, normalizedBody.length)
            val before = normalizedBody.substring(0, safeOffset)
            val relativeLine = before.count { it == '\n' }
            val relativeLineStart = before.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
            return Position(
                bodyStart.line + relativeLine,
                (if (relativeLine == 0) bodyStart.character else 0) + safeOffset - relativeLineStart,
            )
        }
        return Range(bodyPosition(sourceRange.start), bodyPosition(sourceRange.end))
    }

    fun endRange(): Range = rangeOf(SourceRange(text.length, text.length))

    fun defaultId(): String = path.fileName.toString().substringBeforeLast('.').ifBlank { "node" }

    fun frontMatterClosingOffset(): Int? {
        if (!text.startsWith("---")) return null
        return Regex("""(?m)^(---|\.\.\.)\s*$""")
            .findAll(text)
            .drop(1)
            .firstOrNull()
            ?.range?.first
    }

    fun yamlScalarRange(field: String, value: String): Range? {
        val pattern = Regex(
            """(?m)^\s*${Regex.escape(field)}\s*:\s*["']?(${Regex.escape(value)})["']?\s*(?:#.*)?$""",
        )
        val group = pattern.find(text)?.groups?.get(1) ?: return null
        return rangeOf(SourceRange(group.range.first, group.range.last + 1))
    }

    fun yamlFieldRange(field: String): Range? {
        val lines = sourceLines()
        val index = lines.indexOfFirst { it.content.matches(Regex("""\s*${Regex.escape(field)}\s*:.*""")) }
        if (index < 0) return null
        val indent = lines[index].content.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        var endIndex = index + 1
        while (endIndex < lines.size) {
            val content = lines[endIndex].content
            if (content.trim() in setOf("---", "...")) break
            if (content.isNotBlank()) {
                val nextIndent = content.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                if (nextIndent <= indent) break
            }
            endIndex++
        }
        val end = lines.getOrNull(endIndex)?.start ?: text.length
        return rangeOf(SourceRange(lines[index].start, end))
    }

    fun yamlFieldKeyRange(field: String): Range? {
        val match = Regex("""(?m)^\s*(${Regex.escape(field)})\s*:""").find(text) ?: return null
        val group = match.groups[1] ?: return null
        return rangeOf(SourceRange(group.range.first, group.range.last + 1))
    }

    fun yamlTopLevelFieldKeyRange(field: String): Range? {
        val frontMatterEnd = frontMatterClosingOffset() ?: return null
        val match = Regex("""(?m)^(${Regex.escape(field)})\s*:""")
            .find(text.substring(0, frontMatterEnd)) ?: return null
        val group = match.groups[1] ?: return null
        return rangeOf(SourceRange(group.range.first, group.range.last + 1))
    }

    fun propsInsertion(key: String, value: String): YamlInsertion {
        val lines = sourceLines()
        val propsIndex = lines.indexOfFirst { it.content.matches(Regex("""props\s*:\s*""")) }
        if (propsIndex < 0) {
            val offset = frontMatterClosingOffset() ?: text.length
            return YamlInsertion(rangeOf(SourceRange(offset, offset)), "props:\n  $key: $value\n")
        }
        val end = blockEndOffset(lines, propsIndex, indent = 0)
        return YamlInsertion(rangeOf(SourceRange(end, end)), "  $key: $value\n")
    }

    fun propSchemaInsertion(key: String): YamlInsertion {
        val lines = sourceLines()
        val propsIndex = lines.indexOfFirst { it.content.matches(Regex("""props\s*:\s*""")) }
        if (propsIndex < 0) {
            val offset = frontMatterClosingOffset() ?: text.length
            return YamlInsertion(rangeOf(SourceRange(offset, offset)), "props:\n  $key:\n    type: string\n")
        }
        val end = blockEndOffset(lines, propsIndex, indent = 0)
        return YamlInsertion(rangeOf(SourceRange(end, end)), "  $key:\n    type: string\n")
    }

    fun propertyAssignmentRange(key: String): Range? {
        yamlFieldRange(key)?.let { return it }
        val match = Regex("""\b${Regex.escape(key)}(?:\s*\([^)]*\))?\s*=""").find(text) ?: return null
        val valueStart = match.range.last + 1
        var end = inlineValueEnd(valueStart)
        var start = match.range.first
        while (end < text.length && text[end].isWhitespace()) end++
        if (text.getOrNull(end) == ',') {
            end++
        } else {
            var cursor = start - 1
            while (cursor >= 0 && text[cursor].isWhitespace()) cursor--
            if (text.getOrNull(cursor) == ',') start = cursor
        }
        return rangeOf(SourceRange(start, end))
    }

    fun propertyValueRange(key: String): Range? {
        val yaml = Regex("""(?m)^\s*${Regex.escape(key)}\s*:\s*(\S.*)$""").find(text)
        yaml?.groups?.get(1)?.let { group ->
            val value = group.value.substringBefore('#').trimEnd()
            return rangeOf(SourceRange(group.range.first, group.range.first + value.length))
        }
        val inline = Regex("""\b${Regex.escape(key)}(?:\s*\([^)]*\))?\s*=\s*""").find(text) ?: return null
        val start = inline.range.last + 1
        return rangeOf(SourceRange(start, inlineValueEnd(start)))
    }

    fun linkWhitespaceRange(): Range? {
        val match = Regex("""@link(?:\([^)]*\))?(?:\{[^}]*\})?(\s+)(?=\[)""").findAll(text).lastOrNull()
            ?: return null
        val whitespace = match.groups[1] ?: return null
        return rangeOf(SourceRange(whitespace.range.first, whitespace.range.last + 1))
    }

    fun lastRelationInnerRange(): Pair<Range, String>? {
        val match = Regex("""\]\(([^)\n]*)\)""").findAll(text).lastOrNull() ?: return null
        val inner = match.groups[1] ?: return null
        return rangeOf(SourceRange(inner.range.first, inner.range.last + 1)) to inner.value
    }

    fun syntaxMarkerRange(message: String): Range? {
        val marker = when {
            message.startsWith("Unclosed @props") || message.startsWith("Invalid @props") || message.startsWith("@props ") -> "@props"
            message.startsWith("Unclosed @link") || message.startsWith("Invalid @link") || message.startsWith("@link ") -> "@link"
            message.startsWith("Relation ") || message.startsWith("Unclosed relation") -> "@link"
            else -> return null
        }
        val start = text.lastIndexOf(marker)
        if (start < 0) return null
        val end = text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length
        return rangeOf(SourceRange(start, end))
    }

    fun tokenRange(token: String): Range? {
        val match = Regex("""(?<![A-Za-z0-9_.:-])${Regex.escape(token)}(?![A-Za-z0-9_.:-])""")
            .findAll(text).lastOrNull() ?: return null
        return rangeOf(SourceRange(match.range.first, match.range.last + 1))
    }

    fun swapValidTimeBounds(timeline: String): List<TextEdit>? {
        val inline = Regex(
            """${Regex.escape(timeline)}\s*\(\s*from\s*=\s*(-?[0-9]+(?:\.[0-9]+)?)\s*,\s*to\s*=\s*(-?[0-9]+(?:\.[0-9]+)?)""",
        ).find(text)
        if (inline != null) {
            val from = inline.groups[1] ?: return null
            val to = inline.groups[2] ?: return null
            return listOf(
                TextEdit(rangeOf(SourceRange(from.range.first, from.range.last + 1)), to.value),
                TextEdit(rangeOf(SourceRange(to.range.first, to.range.last + 1)), from.value),
            )
        }
        val yaml = Regex(
            """(?ms)timeline\s*:\s*${Regex.escape(timeline)}\s*$.*?from\s*:\s*(?:\n\s*timecode\s*:\s*)?(-?[0-9]+(?:\.[0-9]+)?).*?to\s*:\s*(?:\n\s*timecode\s*:\s*)?(-?[0-9]+(?:\.[0-9]+)?)""",
        ).find(text) ?: return null
        val from = yaml.groups[1] ?: return null
        val to = yaml.groups[2] ?: return null
        return listOf(
            TextEdit(rangeOf(SourceRange(from.range.first, from.range.last + 1)), to.value),
            TextEdit(rangeOf(SourceRange(to.range.first, to.range.last + 1)), from.value),
        )
    }

    fun durationBoundInsertion(key: String): YamlInsertion? {
        val inline = Regex("""\b${Regex.escape(key)}\s*=\s*\{\s*""").find(text)
        if (inline != null) {
            val offset = inline.range.last + 1
            val suffix = if (text.getOrNull(offset) == '}') "from = 0" else "from = 0, "
            return YamlInsertion(rangeOf(SourceRange(offset, offset)), suffix)
        }
        val lines = sourceLines()
        val index = lines.indexOfFirst { it.content.matches(Regex("""\s*${Regex.escape(key)}\s*:\s*(?:\{\s*\})?\s*""")) }
        if (index < 0) return null
        val line = lines[index]
        if ('{' in line.content) {
            val valueRange = propertyValueRange(key) ?: return null
            return YamlInsertion(valueRange, "{ from: 0 }")
        }
        val indent = line.content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) + 2
        val offset = line.start + line.content.length + 1
        return YamlInsertion(rangeOf(SourceRange(offset, offset)), " ".repeat(indent) + "from: 0\n")
    }

    fun mappingFieldInsertion(field: String, value: String): YamlInsertion? {
        val lines = sourceLines()
        val index = lines.indexOfLast { it.content.matches(Regex("""\s*-\s*kind\s*:\s*offset\s*""")) }
        if (index < 0) return null
        val line = lines[index]
        val indent = line.content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) + 2
        val offset = line.start + line.content.length + 1
        return YamlInsertion(rangeOf(SourceRange(offset, offset)), " ".repeat(indent) + "$field: $value\n")
    }

    fun mappingColonInsertion(content: String): Range? {
        val line = sourceLines().firstOrNull { it.content.trim() == content.trim() } ?: return null
        val offset = line.start + line.content.length
        return rangeOf(SourceRange(offset, offset))
    }

    fun normalizeStringList(field: String): TextEdit? {
        val flow = Regex("""(?m)^(\s*)${Regex.escape(field)}\s*:\s*\[([^]]*)]\s*$""").find(text)
        if (flow != null) {
            val values = flow.groups[2]?.value.orEmpty().split(',')
                .map { it.trim().trim('"', '\'') }
                .filter(String::isNotEmpty)
                .distinct()
            val replacement = values.joinToString(prefix = "[", postfix = "]", separator = ", ")
            val valueGroup = flow.groups[2] ?: return null
            return TextEdit(
                rangeOf(SourceRange(valueGroup.range.first - 1, valueGroup.range.last + 2)),
                replacement,
            )
        }
        val lines = sourceLines()
        val index = lines.indexOfFirst { it.content.matches(Regex("""\s*${Regex.escape(field)}\s*:\s*""")) }
        if (index < 0) return null
        val fieldIndent = lines[index].content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val values = mutableListOf<String>()
        var cursor = index + 1
        while (cursor < lines.size) {
            val content = lines[cursor].content
            if (content.isBlank()) {
                cursor++
                continue
            }
            val indent = content.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            if (indent <= fieldIndent) break
            Regex("""\s*-\s*(.*)""").matchEntire(content)?.groupValues?.get(1)
                ?.trim()?.trim('"', '\'')?.takeIf(String::isNotEmpty)?.let(values::add)
            cursor++
        }
        val distinct = values.distinct()
        if (distinct.isEmpty()) return null
        val end = lines.getOrNull(cursor)?.start ?: text.length
        val replacement = buildString {
            append(" ".repeat(fieldIndent)).append(field).append(":\n")
            distinct.forEach { append(" ".repeat(fieldIndent + 2)).append("- ").append(it).append('\n') }
        }
        return TextEdit(rangeOf(SourceRange(lines[index].start, end)), replacement)
    }

    fun isGraphDocumentCandidate(): Boolean {
        val hasGraphSyntax = "@props" in text || "@link" in text
        if (!text.startsWith("---")) return hasGraphSyntax
        val frontMatterEnd = text.indexOf("\n---", startIndex = 3).takeIf { it >= 0 } ?: text.length
        val frontMatter = text.substring(3, frontMatterEnd)
        val hasId = Regex("""(?m)^id\s*:""").containsMatchIn(frontMatter)
        val kind = Regex("""(?m)^kind\s*:\s*["']?([^\s"']+)""").find(frontMatter)?.groupValues?.get(1)
        return hasGraphSyntax || kind in setOf("Node", "Media", "NodeType", "RelType", "Timeline") || (hasId && kind != null)
    }

    private fun positionAt(offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        val line = lineStarts.indexOfLast { it <= safeOffset }.coerceAtLeast(0)
        return Position(line, safeOffset - lineStarts[line])
    }

    private fun normalizedPositionAt(offset: Int): Position {
        return analysisPositionAt(offset)
    }

    private fun analysisPositionAt(offset: Int): Position {
        val safeOffset = offset.coerceIn(0, analysis.text.length)
        val line = analysisLineStarts.indexOfLast { it <= safeOffset }.coerceAtLeast(0)
        return Position(line, safeOffset - analysisLineStarts[line])
    }

    private fun sourceLines(): List<SourceLine> = buildList {
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == '\r' || text[index] == '\n') {
                add(SourceLine(start, text.substring(start, index)))
                if (text[index] == '\r' && text.getOrNull(index + 1) == '\n') index++
                start = index + 1
            }
            index++
        }
        add(SourceLine(start, text.substring(start)))
    }

    private fun blockEndOffset(lines: List<SourceLine>, startIndex: Int, indent: Int): Int {
        for (index in startIndex + 1 until lines.size) {
            val content = lines[index].content
            if (content.trim() in setOf("---", "...")) return lines[index].start
            if (content.isBlank()) continue
            val nextIndent = content.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (nextIndent <= indent) return lines[index].start
        }
        return text.length
    }

    private fun inlineValueEnd(start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        val valueStart = index
        if (text.getOrNull(index) == '"') {
            index++
            var escaped = false
            while (index < text.length) {
                val char = text[index++]
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> return index
                }
            }
            return text.length
        }
        val open = text.getOrNull(index)
        if (open == '{' || open == '[') {
            val close = if (open == '{') '}' else ']'
            var depth = 0
            var inString = false
            var escaped = false
            while (index < text.length) {
                val char = text[index++]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> inString = false
                    }
                } else {
                    when (char) {
                        '"' -> inString = true
                        open -> depth++
                        close -> if (--depth == 0) return index
                    }
                }
            }
            return text.length
        }
        while (index < text.length && text[index] !in setOf(',', '}', '\n')) index++
        return index.coerceAtLeast(valueStart)
    }
}

private data class SourceLine(val start: Int, val content: String)

private data class YamlInsertion(val range: Range, val text: String)

private data class SyntaxFix(val title: String, val marker: String, val closing: String, val before: String?)

private data class IndexedDefinition(
    val uri: String,
    val path: Path,
    val id: String,
    val sourceRange: SourceRange,
    val document: IndexedDocument,
    val kind: ReferenceTargetKind,
) {
    fun range(): Range = document.analysisRangeOf(sourceRange)
}

private data class DiagnosticReferenceTarget(
    val kind: ReferenceTargetKind,
    val id: String,
    val field: String? = null,
)

private data class IndexedPropertyDefinition(
    val document: IndexedDocument,
    val definition: PropertyDefinition,
)

private class SearchParameterException(message: String) : IllegalArgumentException(message)

private fun parseSearchParameter(name: String, encoded: String): GmqlValue {
    val value = encoded.trim()
    return when {
        value == "null" -> GmqlValue.NullValue
        value.equals("true", ignoreCase = true) -> GmqlValue.BooleanValue(true)
        value.equals("false", ignoreCase = true) -> GmqlValue.BooleanValue(false)
        SEARCH_INTEGER.matches(value) -> value.toLongOrNull()?.let(GmqlValue::IntegerValue)
            ?: throw SearchParameterException("Parameter '$name' is outside the Integer range.")
        SEARCH_DECIMAL.matches(value) -> value.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?.let(GmqlValue::DecimalValue)
            ?: throw SearchParameterException("Parameter '$name' is not a finite Decimal.")
        value.startsWith('"') -> GmqlValue.StringValue(
            decodeSearchString(value)
                ?: throw SearchParameterException("Parameter '$name' contains an invalid quoted string."),
        )
        else -> GmqlValue.StringValue(encoded)
    }
}

private fun decodeSearchString(encoded: String): String? {
    if (encoded.length < 2 || encoded.last() != '"') return null
    var index = 1
    return buildString {
        while (index < encoded.lastIndex) {
            when (val character = encoded[index++]) {
                '\\' -> {
                    if (index >= encoded.lastIndex) return null
                    append(
                        when (val escaped = encoded[index++]) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                if (index + 4 > encoded.lastIndex) return null
                                encoded.substring(index, index + 4).toIntOrNull(16)?.toChar()
                                    ?.also { index += 4 } ?: return null
                            }
                            else -> return null
                        },
                    )
                }
                '"' -> return null
                else -> append(character)
            }
        }
    }
}

private fun <T> runSearchSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "Search execution suspended unexpectedly." }.getOrThrow()
}

private val SEARCH_INTEGER = Regex("""[-+]?[0-9]+""")
private val SEARCH_DECIMAL =
    Regex("""[-+]?(?:(?:[0-9]+\.[0-9]*|[0-9]*\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[0-9]+[eE][-+]?[0-9]+)""")
