package dev.usbharu.graphmd.lsp

import dev.usbharu.graphmd.core.*
import dev.usbharu.graphmd.core.model.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
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
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class GraphMdLanguageServer : LanguageServer, LanguageClientAware {
    private val workspaceIndex = GraphMdWorkspaceIndex()
    private val textDocumentService = GraphMdTextDocumentService(this, workspaceIndex)
    private val workspaceService = GraphMdWorkspaceService(this, workspaceIndex)
    private var client: LanguageClient? = null
    private var shutdownRequested = false

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        val roots = params.workspaceFolders?.map { Paths.get(URI.create(it.uri)) }
            ?: params.rootPath?.let { listOf(Paths.get(it)) }
            ?: emptyList()
        workspaceIndex.setWorkspaceRoots(roots)
        workspaceIndex.loadWorkspace()
        publishDiagnostics()
        return CompletableFuture.completedFuture(
            InitializeResult(
                ServerCapabilities().apply {
                    textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
                    completionProvider = CompletionOptions().apply {
                        resolveProvider = false
                        triggerCharacters = listOf(":", "-", " ", "(", "{", ",", "=")
                    }
                    definitionProvider = Either.forLeft(true)
                    referencesProvider = Either.forLeft(true)
                    hoverProvider = Either.forLeft(true)
                },
            ),
        )
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

    fun publishDiagnostics() {
        val client = client ?: return
        workspaceIndex.diagnosticsByUri().forEach { (uri, diagnostics) ->
            client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
        }
    }

    fun languageClient(): LanguageClient? = client
}

private class GraphMdTextDocumentService(
    private val server: GraphMdLanguageServer,
    private val index: GraphMdWorkspaceIndex,
) : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) {
        index.upsert(params.textDocument.uri, params.textDocument.text)
        server.publishDiagnostics()
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val latest = params.contentChanges.lastOrNull()?.text ?: return
        index.upsert(params.textDocument.uri, latest)
        server.publishDiagnostics()
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        index.reload(params.textDocument.uri)
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
}

private class GraphMdWorkspaceService(
    private val server: GraphMdLanguageServer,
    private val index: GraphMdWorkspaceIndex,
) : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        params.changes.forEach { change ->
            when (change.type) {
                FileChangeType.Deleted -> index.remove(change.uri)
                else -> index.reload(change.uri)
            }
        }
        server.publishDiagnostics()
    }
}

private class GraphMdWorkspaceIndex {
    private val analyzer = GraphDocumentAnalyzer()
    private val compiler = GraphCompiler()
    private var roots: List<Path> = emptyList()
    private val documents = linkedMapOf<String, IndexedDocument>()

    fun setWorkspaceRoots(roots: List<Path>) {
        this.roots = roots
    }

    fun loadWorkspace() {
        documents.clear()
        roots.forEach { root ->
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
                        upsert(uri, file.readText())
                    }
            }
        }
    }

    fun upsert(uri: String, text: String) {
        val path = Paths.get(URI.create(uri))
        val analysis = analyzer.analyze(text, path.toString())
        documents[uri] = IndexedDocument(uri, path, text, analysis)
    }

    fun reload(uri: String) {
        val path = Paths.get(URI.create(uri))
        if (!Files.exists(path)) {
            documents.remove(uri)
            return
        }
        upsert(uri, path.readText())
    }

    fun remove(uri: String) {
        documents.remove(uri)
    }

    fun completions(uri: String, position: Position): List<CompletionItem> {
        val document = documents[uri] ?: return emptyList()
        yamlFrontMatterCompletions(document, position)?.let { return it }
        exactPropsCompletions(document, position)?.let { return it }
        exactRelationPropsCompletions(document, position)?.let { return it }
        val referenceKind = analyzer.inferCompletionKind(document.analysis, document.offsetAt(position)) ?: return emptyList()
        return completionIds(referenceKind).map { id ->
            CompletionItem(id).apply {
                this.kind = when (referenceKind) {
                    ReferenceTargetKind.Node -> CompletionItemKind.Reference
                    ReferenceTargetKind.NodeType, ReferenceTargetKind.RelType -> CompletionItemKind.Class
                }
            }
        }
    }

    fun definitions(uri: String, position: Position): List<Location> {
        val document = documents[uri] ?: return emptyList()
        val reference = analyzer.findReferenceAt(document.analysis, document.offsetAt(position)) ?: return emptyList()
        return resolve(reference.kind, reference.targetId).map { resolved ->
            Location(resolved.uri, resolved.range())
        }
    }

    fun references(uri: String, position: Position): List<Location> {
        val document = documents[uri] ?: return emptyList()
        val offset = document.offsetAt(position)
        val reference = analyzer.findReferenceAt(document.analysis, offset)?.let { it.kind to it.targetId }
            ?: analyzer.findDefinitionAt(document.analysis, offset)?.let { it.kind to it.id }
            ?: return emptyList()

        val locations = mutableListOf<Location>()
        documents.values.forEach { indexed ->
            indexed.analysis.definitions
                .filter { it.kind == reference.first && it.id == reference.second }
                .forEach { locations += Location(indexed.uri, indexed.rangeOf(it.range)) }
            indexed.analysis.references
                .filter { it.kind == reference.first && it.targetId == reference.second }
                .forEach { locations += Location(indexed.uri, indexed.rangeOf(it.range)) }
        }
        return locations
    }

    fun hover(uri: String, position: Position): Hover? {
        val document = documents[uri] ?: return null
        val offset = document.offsetAt(position)
        val symbol = analyzer.findReferenceAt(document.analysis, offset)?.let { it.kind to it.targetId }
            ?: analyzer.findDefinitionAt(document.analysis, offset)?.let { it.kind to it.id }
            ?: return null
        val first = resolve(symbol.first, symbol.second).firstOrNull()
        val contents = MarkupContent().apply {
            kind = MarkupKind.MARKDOWN
            value = buildString {
                append("**")
                append(
                    when (symbol.first) {
                        ReferenceTargetKind.Node -> "Node"
                        ReferenceTargetKind.NodeType -> "NodeType"
                        ReferenceTargetKind.RelType -> "RelType"
                    },
                )
                append("** `")
                append(symbol.second)
                append("`")
                if (first != null) {
                    append("\n\nDefined in `")
                    append(first.path.fileName.toString())
                    append("`")
                }
            }
        }
        return Hover(contents)
    }

    fun diagnosticsByUri(): Map<String, MutableList<org.eclipse.lsp4j.Diagnostic>> {
        val graphDocuments = documents.values.filter { it.isGraphDocumentCandidate() }
        val compiled = compiler.compileSources(graphDocuments.map { SourceDocument(it.text, it.path.toString()) })
        val diagnostics = linkedMapOf<String, MutableList<org.eclipse.lsp4j.Diagnostic>>()
        compiled.diagnostics.forEach { diagnostic ->
            val sourcePath = diagnostic.source?.path ?: return@forEach
            val uri = Path.of(sourcePath).toUri().toString()
            val document = documents[uri] ?: return@forEach
            diagnostics.getOrPut(uri) { mutableListOf() } += org.eclipse.lsp4j.Diagnostic().apply {
                severity = when (diagnostic.severity) {
                    Severity.Error -> DiagnosticSeverity.Error
                    Severity.Warning -> DiagnosticSeverity.Warning
                }
                message = diagnostic.message
                source = "graphmd"
                range = document.rangeOf(
                    inferredDiagnosticRange(document, diagnostic)
                        ?: diagnostic.source?.range
                        ?: SourceRange(0, 0),
                )
            }
        }
        documents.keys.forEach { uri -> diagnostics.putIfAbsent(uri, mutableListOf()) }
        return diagnostics
    }

    private fun yamlFrontMatterCompletions(document: IndexedDocument, position: Position): List<CompletionItem>? {
        val resolver = FrontMatterCompletionResolver(
            text = document.text,
            offset = document.offsetAt(position),
            parsedDocument = document.analysis.parsed.document,
            nodeTypeIds = completionIds(ReferenceTargetKind.NodeType),
            relTypeIds = completionIds(ReferenceTargetKind.RelType),
            timelineIds = timelineIds(),
            nodePropsSchema = (document.analysis.parsed.document as? NodeDocument)?.let { nodeTypeSchema(it.type)?.props }.orEmpty(),
        )
        return resolver.resolve()?.map { entry ->
            CompletionItem(entry.label).apply {
                kind = entry.kind
                insertText = entry.insertText
                detail = entry.detail
            }
        }
    }

    private fun exactPropsCompletions(document: IndexedDocument, position: Position): List<CompletionItem>? {
        val parsed = document.analysis.parsed.document as? NodeDocument ?: return null
        val offset = document.offsetAt(position)
        val schema = nodeTypeSchema(parsed.type)?.props ?: return null
        val context = PropsCompletionContextResolver(document.text, offset, schema, timelineIds()).resolve() ?: return null
        return context.items.map { entry ->
            CompletionItem(entry.label).apply {
                kind = entry.kind
                insertText = entry.insertText
                detail = entry.detail
            }
        }
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
        return context.items.map { entry ->
            CompletionItem(entry.label).apply {
                kind = entry.kind
                insertText = entry.insertText
                detail = entry.detail
            }
        }
    }

    private fun completionIds(kind: ReferenceTargetKind): List<String> {
        return when (kind) {
            ReferenceTargetKind.Node -> definitionsOf(kind).map { it.id }
            ReferenceTargetKind.NodeType, ReferenceTargetKind.RelType -> definitionsOf(kind).filter { it.path.toString().contains("/types/") }.ifEmpty { definitionsOf(kind) }.map { it.id }
        }.distinct().sorted()
    }

    private fun resolve(kind: ReferenceTargetKind, id: String): List<IndexedDefinition> {
        return definitionsOf(kind).filter { it.id == id }
    }

    private fun nodeTypeSchema(id: String): NormalizedNodeType? {
        val graphDocuments = documents.values.filter { it.isGraphDocumentCandidate() }
        return compiler.compileSources(graphDocuments.map { SourceDocument(it.text, it.path.toString()) }).nodeTypes.firstOrNull { it.id == id }
    }

    private fun relTypeSchema(id: String): NormalizedRelType? {
        val graphDocuments = documents.values.filter { it.isGraphDocumentCandidate() }
        return compiler.compileSources(graphDocuments.map { SourceDocument(it.text, it.path.toString()) }).relTypes.firstOrNull { it.id == id }
    }

    private fun timelineIds(): List<String> {
        val graphDocuments = documents.values.filter { it.isGraphDocumentCandidate() }
        return compiler.compileSources(graphDocuments.map { SourceDocument(it.text, it.path.toString()) }).timelines.map { it.id }.sorted()
    }

    private fun definitionsOf(kind: ReferenceTargetKind): List<IndexedDefinition> {
        return documents.values.flatMap { indexed ->
            indexed.analysis.definitions
                .filter { it.kind == kind }
                .map { IndexedDefinition(indexed.uri, indexed.path, it.id, it.range, indexed) }
        }
    }

    private fun inferredDiagnosticRange(document: IndexedDocument, diagnostic: Diagnostic): SourceRange? {
        if (diagnostic.category != DiagnosticCategory.ReferenceError) return null
        val reference = referenceTargetForDiagnostic(diagnostic.message) ?: return null
        return document.analysis.references.firstOrNull { ref ->
            ref.kind == reference.kind &&
                ref.targetId == reference.id &&
                (reference.field == null || ref.field == reference.field)
        }?.range
    }

    private fun referenceTargetForDiagnostic(message: String): DiagnosticReferenceTarget? {
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
        return null
    }
}

internal data class CompletionEntry(
    val label: String,
    val kind: CompletionItemKind,
    val insertText: String = label,
    val detail: String? = null,
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
        val lines = text.replace("\r\n", "\n").split('\n')
        if (lines.firstOrNull() != "---") return null
        val endLine = lines.drop(1).indexOfFirst { it == "---" || it == "..." }.let { if (it >= 0) it + 1 else -1 }
        if (endLine < 0) return null
        val lineStarts = computeLineStarts(lines)
        if (offset >= lineStarts[endLine] + lines[endLine].length) return null
        val lineIndex = lineStarts.indexOfLast { it <= offset }.coerceAtLeast(0)
        if (lineIndex == 0 || lineIndex >= endLine) return null
        val line = lines[lineIndex]
        val indent = indentOf(line)
        val trimmed = line.trimStart()
        val cursorInLine = offset - lineStarts[lineIndex]
        val beforeCursor = line.take(cursorInLine.coerceIn(0, line.length))
        val currentKeyPrefix = trimmed.takeWhile { it != ':' && !it.isWhitespace() }

        if (trimmed.startsWith("-")) {
            return listValueCompletions(lines, lineIndex, parsedDocument)
        }

        val keyMatch = Regex("""^([A-Za-z][A-Za-z0-9_-]*)?\s*:?(.*)$""").matchEntire(trimmed) ?: return null
        val keyCandidate = keyMatch.groupValues[1]
        val hasColon = ':' in trimmed
        if (!hasColon && indent == 0) {
            return topLevelKeyCompletions(keyCandidate)
        }

        val path = contextPath(lines, lineIndex, indent, hasColon)
        val valuePrefix = if (hasColon) beforeCursor.substringAfter(':', "").trimStart() else ""
        nodePropsYamlCompletions(lines, lineIndex, indent, path, hasColon, currentKeyPrefix, valuePrefix)?.let { return it }
        return when {
            indent == 0 && keyCandidate.isNotEmpty() && !hasColon -> topLevelKeyCompletions(keyCandidate)
            hasColon && path == listOf("kind") -> enumCompletions(valuePrefix, listOf("Node", "NodeType", "RelType", "Timeline"), "kind")
            hasColon && path == listOf("type") && parsedDocument is NodeDocument -> idCompletions(valuePrefix, nodeTypeIds, "NodeType")
            hasColon && path == listOf("extends") && parsedDocument is NodeTypeDocument -> idCompletions(valuePrefix, nodeTypeIds, "NodeType")
            hasColon && path == listOf("extends") && parsedDocument is RelTypeDocument -> idCompletions(valuePrefix, relTypeIds, "RelType")
            hasColon && path == listOf("extends") && parsedDocument is TimelineDocument -> idCompletions(valuePrefix, timelineIds, "Timeline")
            hasColon && (path == listOf("from") || path == listOf("to")) -> idCompletions(valuePrefix, nodeTypeIds, "NodeType")
            hasColon && path.lastOrNull() == "required" ->
                enumCompletions(valuePrefix, listOf("true", "false"), "boolean")
            hasColon && path == listOf("timecode", "type") ->
                enumCompletions(valuePrefix, TimecodeType.entries.map { it.name }, "timecode type")
            hasColon && path == listOf("timecode", "direction") ->
                enumCompletions(valuePrefix, TimecodeDirection.entries.map { it.name }, "timecode direction")
            hasColon && path.lastOrNull() == "type" ->
                enumCompletions(valuePrefix, PropType.entries.map { it.name }, "prop type")
            hasColon && path.lastOrNull() == "index" ->
                enumCompletions(valuePrefix, PropIndex.entries.map { it.name }, "prop index")
            hasColon && path.lastOrNull() == "timeline" ->
                timelineSelectorCompletions(valuePrefix, includeAny = true)
            hasColon && path.lastOrNull() == "timelines" ->
                timelineSelectorCompletions(valuePrefix, includeAny = true)
            hasColon && valuePrefix.isEmpty() -> nestedKeyCompletions(path, "", lines, lineIndex)
            indent == 0 -> topLevelKeyCompletions(keyCandidate)
            else -> nestedKeyCompletions(path, currentKeyPrefix, lines, lineIndex)
        }
    }

    private fun topLevelKeyCompletions(prefix: String): List<CompletionEntry> {
        val kind = parsedDocument?.kind
        val keys = mutableListOf("id", "kind")
        when (kind) {
            DocumentKind.Node -> keys += listOf("type", "props")
            DocumentKind.NodeType -> keys += listOf("extends", "props")
            DocumentKind.RelType -> keys += listOf("extends", "from", "to", "props")
            DocumentKind.Timeline -> keys += listOf("extends", "timecode", "mappings", "props")
            null -> keys += listOf("type", "extends", "from", "to", "props", "timecode", "mappings")
        }
        val filteredKeys = keys.distinct().filter { key ->
            key.startsWith(prefix)
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
    ): List<CompletionEntry>? {
        if (parsedDocument !is NodeDocument || nodePropsSchema.isEmpty()) return null
        if (path.firstOrNull() != "props") return null

        val rawPath = path.drop(1)
        if (rawPath.isEmpty()) {
            if (hasColon) return null
            return yamlObjectKeyCompletions(nodePropsSchema, emptyList(), lines, lineIndex, indent, keyPrefix)
        }

        val currentKey = rawPath.last()
        val parentContainer = nodePropsContainer(rawPath.dropLast(1)) ?: return null

        if (!hasColon) {
            return yamlObjectKeyCompletions(
                parentContainer.properties,
                parentContainer.specialKeys,
                lines,
                lineIndex,
                indent,
                keyPrefix,
            )
        }

        val schema = parentContainer.properties[currentKey]
        return when {
            currentKey == "timeline" ->
                idCompletions(valuePrefix, timelineIds + listOf("any"), "Timeline")
            currentKey == "fromInclusive" || currentKey == "toInclusive" || schema?.type == PropType.boolean ->
                enumCompletions(valuePrefix, listOf("true", "false"), "boolean")
            valuePrefix.isEmpty() && (schema?.type == PropType.instant || schema?.type == PropType.interval || schema?.type == PropType.duration || schema?.type == PropType.`object`) ->
                listOf(CompletionEntry("{", CompletionItemKind.Operator, "{ }", "object"))
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
                CompletionEntry(
                    key,
                    CompletionItemKind.Field,
                    "$key: ",
                    properties[key]?.type?.name ?: "property",
                )
            }
        return entries.ifEmpty { null }
    }

    private fun nestedKeyCompletions(
        path: List<String>,
        prefix: String,
        lines: List<String>,
        lineIndex: Int,
    ): List<CompletionEntry>? {
        val keys = when {
            isInsidePropSchema(path) -> listOf("type", "required", "default", "index", "timeline", "timelines", "items", "properties")
            path == listOf("timecode") -> {
                val typeValue = siblingScalarValue(lines, lineIndex, "type")
                listOfNotNull("type", "direction".takeIf { typeValue == null || typeValue == "number" })
            }
            path == listOf("mappings") -> when (siblingScalarValue(lines, lineIndex, "kind")) {
                "offset" -> listOf("kind", "to", "offset")
                "table" -> listOf("kind", "to", "entries")
                else -> listOf("kind", "to", "offset", "entries")
            }
            else -> return null
        }
        return keys.filter { it.startsWith(prefix) }.map { CompletionEntry(it, CompletionItemKind.Field, "$it: ") }
    }

    private fun enumCompletions(prefix: String, values: List<String>, detail: String): List<CompletionEntry> =
        values.filter { it.startsWith(prefix) }.map { CompletionEntry(it, CompletionItemKind.EnumMember, it, detail) }

    private fun idCompletions(prefix: String, values: List<String>, detail: String): List<CompletionEntry> =
        values.distinct().filter { it.startsWith(prefix) }.map { CompletionEntry(it, CompletionItemKind.Reference, it, detail) }

    private fun timelineSelectorCompletions(prefix: String, includeAny: Boolean): List<CompletionEntry> {
        val entries = mutableListOf<CompletionEntry>()
        val scalarPool = if (includeAny) timelineIds + listOf("any") else timelineIds
        entries += idCompletions(prefix, scalarPool, "Timeline")
        val showMapped = prefix.isEmpty() || prefix == "{" || "mapped".startsWith(prefix)
        if (showMapped) {
            entries += timelineIds.map {
                CompletionEntry("mapped: $it", CompletionItemKind.Reference, "{ mapped: $it }", "mapped Timeline")
            }
        }
        return entries
    }

    private fun listValueCompletions(lines: List<String>, lineIndex: Int, parsedDocument: GraphDocument?): List<CompletionEntry>? {
        val parentKey = enclosingListKey(lines, lineIndex) ?: return null
        val prefix = lines[lineIndex].substringAfter('-').trim()
        return when (parentKey) {
            "extends" -> when (parsedDocument) {
                is NodeTypeDocument -> idCompletions(prefix, nodeTypeIds, "NodeType")
                is RelTypeDocument -> idCompletions(prefix, relTypeIds, "RelType")
                is TimelineDocument -> idCompletions(prefix, timelineIds, "Timeline")
                else -> null
            }
            "from", "to" -> idCompletions(prefix, nodeTypeIds, "NodeType")
            "timelines" -> timelineSelectorCompletions(prefix, includeAny = true)
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

    private fun isInsidePropSchema(path: List<String>): Boolean {
        if (path.isEmpty()) return false
        val propsIndex = path.indexOf("props")
        val propertiesIndex = path.indexOf("properties")
        return propsIndex >= 0 || propertiesIndex >= 0
    }

    private fun nodePropsContainer(path: List<String>): NodePropsContainer? {
        var currentProperties = nodePropsSchema
        var currentSpecialKeys = emptyList<String>()
        for (segment in path) {
            val schema = currentProperties[segment] ?: return null
            currentProperties = nestedProperties(schema)
            currentSpecialKeys = specialKeys(schema)
        }
        return NodePropsContainer(currentProperties, currentSpecialKeys)
    }

    private fun nestedProperties(schema: ResolvedPropSchema): Map<String, ResolvedPropSchema> {
        return when (schema.type) {
            PropType.`object` -> schema.properties
            else -> emptyMap()
        }
    }

    private fun specialKeys(schema: ResolvedPropSchema): List<String> {
        return when (schema.type) {
            PropType.instant -> listOf("timeline", "value", "timecode", "precision")
            PropType.interval -> listOf("timeline", "from", "to", "fromInclusive", "toInclusive")
            PropType.duration -> listOf("timeline", "unit", "value")
            PropType.text -> listOf("default")
            else -> emptyList()
        }
    }

    private data class NodePropsContainer(
        val properties: Map<String, ResolvedPropSchema>,
        val specialKeys: List<String>,
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
            if (prefix[cursor] != '=') {
                return keyCompletion(token)
            }
            currentKey = token
            currentSchema = frames.lastOrNull()?.properties?.get(token)
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
            frames.addLast(Frame(nestedProperties(schema), specialKeys(schema)))
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
                CompletionEntry(
                    label = key,
                    kind = CompletionItemKind.Property,
                    insertText = "$key = ",
                    detail = frame.properties[key]?.type?.name ?: "property",
                )
            }
        return if (entries.isEmpty()) null else PropsCompletionResult(entries)
    }

    private fun valueCompletion(prefix: String): PropsCompletionResult? {
        val key = currentKey ?: return null
        val schema = currentSchema
        val entries = when {
            key == "timeline" -> timelineIds
                .filter { it.startsWith(prefix) }
                .map { CompletionEntry(it, CompletionItemKind.Value, it, "timeline") }
            schema?.type == PropType.boolean || key == "fromInclusive" || key == "toInclusive" ->
                listOf("true", "false")
                    .filter { it.startsWith(prefix) }
                    .map { CompletionEntry(it, CompletionItemKind.Value, it, "boolean") }
            schema?.type == PropType.instant && schema.timeline is TimelineSelector.Id ->
                listOf(
                    CompletionEntry("\"\"", CompletionItemKind.Value, "\"\"", "instant value"),
                    CompletionEntry("{", CompletionItemKind.Operator, "{  }", "instant object"),
                ).filter { it.label.startsWith(prefix) || prefix.isEmpty() }
            schema?.type == PropType.instant || schema?.type == PropType.interval || schema?.type == PropType.duration || schema?.type == PropType.`object` ->
                listOf(CompletionEntry("{", CompletionItemKind.Operator, "{  }", "object"))
            else -> emptyList()
        }
        return if (entries.isEmpty()) null else PropsCompletionResult(entries)
    }

    private fun nestedProperties(schema: ResolvedPropSchema?): Map<String, ResolvedPropSchema> {
        return when (schema?.type) {
            PropType.`object` -> schema.properties
            else -> emptyMap()
        }
    }

    private fun specialKeys(schema: ResolvedPropSchema?): List<String> {
        return when (schema?.type) {
            PropType.instant -> listOf("timeline", "value", "timecode", "precision")
            PropType.interval -> listOf("timeline", "from", "to", "fromInclusive", "toInclusive")
            PropType.duration -> listOf("timeline", "unit", "value")
            PropType.text -> listOf("default")
            else -> emptyList()
        }
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
        var searchIndex = offset.coerceAtMost(text.length)
        while (searchIndex >= 0) {
            val relationStart = text.lastIndexOf("@[", searchIndex)
            if (relationStart < 0) return null
            val closeLabel = findUnescaped(']', relationStart + 2) ?: return null
            if (text.getOrNull(closeLabel + 1) != '(') {
                searchIndex = relationStart - 1
                continue
            }
            val closeParen = findUnescaped(')', closeLabel + 2) ?: return null
            val raw = text.substring(closeLabel + 2, closeParen)
            val relType = parseRelationTargetAndType(raw)?.second
            val braceStart = text.indexOfFirstAfter(closeParen) { it == '{' }
            if (relType != null && braceStart >= 0 && offset > braceStart && isInsideBraceRange(braceStart, offset)) {
                return RelationPropsContext(relType, braceStart)
            }
            searchIndex = relationStart - 1
        }
        return null
    }

    private fun String.indexOfFirstAfter(start: Int, predicate: (Char) -> Boolean): Int {
        var index = start + 1
        while (index < length && this[index].isWhitespace()) index++
        return if (index < length && predicate(this[index])) index else -1
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
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    fun offsetAt(position: Position): Int {
        val lineStart = lineStarts.getOrElse(position.line) { text.length }
        return (lineStart + position.character).coerceAtMost(text.length)
    }

    fun rangeOf(sourceRange: SourceRange): Range {
        return Range(positionAt(sourceRange.start), positionAt(sourceRange.end))
    }

    fun isGraphDocumentCandidate(): Boolean = text.startsWith("---")

    private fun positionAt(offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        val line = lineStarts.indexOfLast { it <= safeOffset }.coerceAtLeast(0)
        return Position(line, safeOffset - lineStarts[line])
    }
}

private data class IndexedDefinition(
    val uri: String,
    val path: Path,
    val id: String,
    val sourceRange: SourceRange,
    val document: IndexedDocument,
) {
    fun range(): Range = document.rangeOf(sourceRange)
}

private data class DiagnosticReferenceTarget(
    val kind: ReferenceTargetKind,
    val id: String,
    val field: String? = null,
)
