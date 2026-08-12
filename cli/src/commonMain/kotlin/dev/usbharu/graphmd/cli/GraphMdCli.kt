package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.BodySyntaxExtractor
import dev.usbharu.graphmd.core.TemporalEngine
import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.GraphSearchEngine
import dev.usbharu.graphmd.query.embed.*
import dev.usbharu.graphmd.query.gmql.*
import dev.usbharu.graphmd.query.model.IntervalSet
import dev.usbharu.graphmd.query.model.TimelineId
import dev.usbharu.graphmd.query.model.TimelineCatalog
import kotlin.coroutines.*

data class CliResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
)

private enum class Visibility(val wireName: String) {
    Full("full"),
    AssertionOnly("assertion-only"),
    Hidden("hidden"),
}

class GraphMdCli internal constructor(
    internal val fileSystem: CliFileSystem = SystemCliFileSystem,
) {
    fun run(arguments: List<String>): CliResult {
        return when (val parsed = CliArguments.parse(arguments)) {
            is ParseResult.Print -> CliResult(stdout = parsed.text)
            is ParseResult.Error -> usageError(parsed.message, parsed.exitCode)
            is ParseResult.Run -> try {
                execute(parsed)
            } catch (exception: CliIoException) {
                usageError(
                    exception.message ?: "I/O error",
                    if (parsed.command is CliCommand.Demo || parsed.command is CliCommand.Embed) 1 else 2,
                )
            } catch (exception: Throwable) {
                usageError(
                    exception.message ?: "Unexpected error",
                    if (parsed.command is CliCommand.Demo || parsed.command is CliCommand.Embed) 1 else 2,
                )
            }
        }
    }

    private fun execute(invocation: ParseResult.Run): CliResult {
        val command = invocation.command
        if (command is CliCommand.Demo) return demo(command, invocation.json)
        if (command is CliCommand.Site) return site(command, invocation.json)
        val sources = WorkspaceLoader(fileSystem).load(command.paths)
        val options = if (command is CliCommand.Lint && command.strict) {
            CompileOptions(mode = ValidationMode.Strict)
        } else {
            CompileOptions()
        }
        val compilation = GraphCompiler(options).compileSources(sources)
        return when (command) {
            is CliCommand.ListItems -> listItems(compilation, command, invocation.json)
            is CliCommand.Show -> show(compilation, command, invocation.json)
            is CliCommand.Props -> props(compilation, command, invocation.json)
            is CliCommand.Links -> links(compilation, command, invocation.json)
            is CliCommand.Lint -> lint(compilation, command, invocation.json)
            is CliCommand.Stats -> stats(compilation, command, invocation.json)
            is CliCommand.Search -> search(compilation, sources, command, invocation.json)
            is CliCommand.Embed -> embed(compilation, sources, invocation.json)
            is CliCommand.Site -> error("site is executed before workspace loading")
            is CliCommand.Demo -> error("demo is executed before workspace loading")
        }
    }

    private fun embed(
        compilation: GraphCompilationResult,
        sources: List<SourceDocument>,
        json: Boolean,
    ): CliResult {
        val engine = EmbedEngine(GraphSearchEngine.build(compilation, sources))
        val targetPaths = compilation.nodes.associate { it.id to it.source.path }
        val updated = mutableListOf<Pair<String, Int>>()
        val skipped = mutableListOf<Pair<String, List<String>>>()

        sources.forEach { source ->
            val crlf = "\r\n" in source.text
            val normalized = source.text.replace("\r\n", "\n").replace('\r', '\n')
            val parsed = GraphCompiler().parseDocument(normalized, source.sourcePath)
            val document = parsed.document as? NodeDocument ?: return@forEach
            val extraction = BodySyntaxExtractor().extract(document.body, source.sourcePath, document.id)
            val blocks = extraction.blocks.filter { it.embed != null }
            if (blocks.isEmpty()) return@forEach
            val fileDiagnostics = parsed.diagnostics +
                compilation.diagnostics.filter { it.source?.path == source.sourcePath } +
                extraction.diagnostics
            val errors = fileDiagnostics
                .filter { it.severity == Severity.Error }
                .map { it.message }
                .distinct()
                .toMutableList()
            val replacements = mutableListOf<Pair<SourceRange, String>>()
            val bodyStart = markdownBodyStart(normalized)

            blocks.forEach { block ->
                val directive = checkNotNull(block.embed)
                val rendered = if (errors.isEmpty()) {
                    runCliSuspend { engine.render(directive, document.id) }
                } else {
                    null
                }
                if (rendered != null && rendered.isSuccess) {
                    val markdown = checkNotNull(rendered.table).toMarkdown { targetId ->
                        targetPaths[targetId]?.let { target -> relativeMarkdownPath(source.sourcePath, target) }
                    }
                    replacements += SourceRange(
                        bodyStart + block.contentRange.start,
                        bodyStart + block.contentRange.end,
                    ) to markdown
                } else if (rendered != null) {
                    errors += rendered.diagnostics.map { "${it.code}: ${it.message}" }
                }
            }

            if (errors.isNotEmpty()) {
                skipped += source.sourcePath to errors.distinct()
                return@forEach
            }
            var next = normalized
            replacements.sortedByDescending { it.first.start }.forEach { (range, markdown) ->
                next = next.replaceRange(range.start, range.end, markdown)
            }
            val output = if (crlf) next.replace("\n", "\r\n") else next
            try {
                fileSystem.writeText(source.sourcePath, output)
                updated += source.sourcePath to blocks.size
            } catch (exception: Throwable) {
                skipped += source.sourcePath to listOf("Cannot write ${source.sourcePath}: ${exception.message ?: "I/O error"}")
            }
        }

        val compileErrors = compilation.diagnostics.filter { it.severity == Severity.Error }
        val hasErrors = skipped.isNotEmpty() || compileErrors.isNotEmpty()
        if (json) {
            val output = jsonObject(
                "updatedFiles" to jsonArray(updated.map { (path, blocks) ->
                    jsonObject("path" to jsonString(path), "blocks" to jsonNumber(blocks))
                }),
                "skippedFiles" to jsonArray(skipped.map { (path, messages) ->
                    jsonObject(
                        "path" to jsonString(path),
                        "diagnostics" to jsonArray(messages.map(::jsonString)),
                    )
                }),
                "updatedBlocks" to jsonNumber(updated.sumOf { it.second }),
                "diagnostics" to jsonArray(compileErrors.map(Diagnostic::toJson)),
            ).encode() + "\n"
            return CliResult(stdout = output, exitCode = if (hasErrors) 1 else 0)
        }
        val stdout = buildString {
            updated.forEach { (path, blocks) -> append("Updated ").append(path).append(" (").append(blocks).append(" blocks)\n") }
        }
        val stderr = buildString {
            skipped.forEach { (path, messages) ->
                append("Skipped ").append(path).append('\n')
                messages.forEach { append("  ").append(it).append('\n') }
            }
            compileErrors.forEach { append(renderDiagnostic(it)) }
        }
        return CliResult(stdout, stderr, if (hasErrors) 1 else 0)
    }

    private fun demo(command: CliCommand.Demo, json: Boolean): CliResult {
        val outputExisted = when (fileSystem.kind(command.outputDirectory)) {
            null -> false
            FileKind.Directory -> {
                if (fileSystem.children(command.outputDirectory).isNotEmpty()) {
                    throw CliIoException("Output directory must be empty: ${command.outputDirectory}")
                }
                true
            }
            else -> throw CliIoException("Output path is not a directory: ${command.outputDirectory}")
        }
        val plan = DemoGenerator.plan(command.requestedCount, command.seed)

        var attemptedCount = 0
        try {
            if (!outputExisted) fileSystem.createDirectories(command.outputDirectory)
            plan.documents().forEach { document ->
                val path = fileSystem.child(command.outputDirectory, document.fileName)
                attemptedCount++
                fileSystem.writeText(path, document.text)
            }
        } catch (exception: Throwable) {
            for (index in attemptedCount - 1 downTo 0) {
                val path = fileSystem.child(command.outputDirectory, plan.fileNameAt(index))
                runCatching { fileSystem.delete(path, mustExist = false) }
            }
            if (!outputExisted) runCatching { fileSystem.delete(command.outputDirectory, mustExist = false) }
            throw CliIoException(
                "Cannot write demo data to ${command.outputDirectory}: ${exception.message ?: "I/O error"}",
            )
        }

        val counts = plan.counts
        val output = if (json) {
            jsonObject(
                "outputDirectory" to jsonString(command.outputDirectory),
                "requestedCount" to jsonNumber(plan.requestedCount),
                "generatedCount" to jsonNumber(plan.generatedCount),
                "seed" to jsonNumber(plan.seed),
                "counts" to jsonObject(
                    "node" to jsonNumber(counts.getValue(CliKind.Node)),
                    "media" to jsonNumber(counts.getValue(CliKind.Media)),
                    "nodeType" to jsonNumber(counts.getValue(CliKind.NodeType)),
                    "relType" to jsonNumber(counts.getValue(CliKind.RelType)),
                    "timeline" to jsonNumber(counts.getValue(CliKind.Timeline)),
                ),
            ).encode() + "\n"
        } else {
            buildString {
                append("Generated GraphMD demo data in ").append(command.outputDirectory).append('\n')
                append("requested\t").append(plan.requestedCount).append('\n')
                append("generated\t").append(plan.generatedCount).append('\n')
                append("seed\t").append(plan.seed).append('\n')
                append("node\t").append(counts.getValue(CliKind.Node)).append('\n')
                append("media\t").append(counts.getValue(CliKind.Media)).append('\n')
                append("node-type\t").append(counts.getValue(CliKind.NodeType)).append('\n')
                append("rel-type\t").append(counts.getValue(CliKind.RelType)).append('\n')
                append("timeline\t").append(counts.getValue(CliKind.Timeline)).append('\n')
            }
        }
        return CliResult(stdout = output)
    }

    private fun search(
        graph: GraphCompilationResult,
        sources: List<SourceDocument>,
        command: CliCommand.Search,
        json: Boolean,
    ): CliResult {
        val query = command.query ?: readQueryFile(checkNotNull(command.queryFile))
        val parameters = try {
            command.parameters.mapValues { (name, value) -> parseGmqlParameter(name, value) }
        } catch (exception: GmqlParameterException) {
            return usageError(exception.message ?: "Invalid parameter")
        }
        val result = runCliSuspend {
            GraphSearchEngine.build(graph, sources).queryGmql(query, parameters)
        }
        val graphDiagnostics = graph.diagnostics.filter { it.severity == Severity.Error }
        val output = if (json) {
            jsonArray(result.rows.map { row ->
                JsonValue.Object(
                    result.columns.mapIndexed { index, column ->
                        column.name to row.values[index].toJson()
                    }.toMap(linkedMapOf()),
                )
            }).encode() + "\n"
        } else {
            renderSearchResult(result)
        }
        val hasErrors = graphDiagnostics.isNotEmpty() || result.diagnostics.isNotEmpty()
        val stderr = when {
            !hasErrors -> ""
            json -> jsonArray(
                graphDiagnostics.map(Diagnostic::toJson) + result.diagnostics.map(GmqlDiagnostic::toJson),
            ).encode() + "\n"
            else -> graphDiagnostics.joinToString(separator = "", transform = ::renderDiagnostic) +
                result.diagnostics.joinToString(separator = "", transform = ::renderGmqlDiagnostic)
        }
        return CliResult(output, stderr, if (hasErrors) 1 else 0)
    }

    private fun readQueryFile(path: String): String {
        if (fileSystem.kind(path) != FileKind.File) throw CliIoException("Query file does not exist: $path")
        return try {
            fileSystem.readText(fileSystem.canonical(path))
        } catch (exception: Throwable) {
            throw CliIoException("Cannot read query file $path: ${exception.message ?: "I/O error"}")
        }
    }

    private fun listItems(
        graph: GraphCompilationResult,
        command: CliCommand.ListItems,
        json: Boolean,
    ): CliResult {
        val view = command.validTime?.let { validTime ->
            temporalView(graph, validTime) ?: return unknownTimeline(validTime, json)
        }
        val items = select(graph, command.kinds, command.types, command.includeDerived, view)
        val output = if (json) {
            jsonArray(items.map { it.summaryJson(view) }).encode() + "\n"
        } else {
            renderList(items, view)
        }
        return queryResult(output, queryDiagnosticsFor(graph, view), json)
    }

    private fun show(
        graph: GraphCompilationResult,
        command: CliCommand.Show,
        json: Boolean,
    ): CliResult {
        val view = command.validTime?.let { validTime ->
            temporalView(graph, validTime) ?: return unknownTimeline(validTime, json)
        }
        val candidates = select(graph, command.kinds, emptySet(), includeDerived = false, view)
            .filter { it.id == command.id && it.kind != CliKind.Link }
        val problem = candidateProblem(command.id, candidates)
        if (problem != null) return queryResult("", queryDiagnosticsFor(graph, view) + problem, json)
        val item = candidates.single()
        val output = if (json) {
            item.detailJson(graph, view).encode() + "\n"
        } else {
            renderShow(item, graph, view)
        }
        return queryResult(output, queryDiagnosticsFor(graph, view), json)
    }

    private fun props(
        graph: GraphCompilationResult,
        command: CliCommand.Props,
        json: Boolean,
    ): CliResult {
        val view = command.validTime?.let { validTime ->
            temporalView(graph, validTime) ?: return unknownTimeline(validTime, json)
        }
        val allowedKinds = command.kinds.ifEmpty { setOf(CliKind.Node, CliKind.Media) }
        val candidates = select(graph, allowedKinds, emptySet(), includeDerived = false, view)
            .filter { it.id == command.id }
        val problem = candidateProblem(command.id, candidates)
        if (problem != null) return queryResult("", queryDiagnosticsFor(graph, view) + problem, json)
        val node = (candidates.single() as NodeItem).node
        val entries = view?.filterProperties(node.propEntries) ?: node.propEntries
        val visibility = view?.visibility(node) ?: Visibility.Full
        val output = if (json) {
            propertyEntriesToJson(entries, node.id, visibility.wireName).encode() + "\n"
        } else {
            renderProperties(entries, node.id, visibility)
        }
        return queryResult(output, queryDiagnosticsFor(graph, view), json)
    }

    private fun links(
        graph: GraphCompilationResult,
        command: CliCommand.Links,
        json: Boolean,
    ): CliResult {
        val view = command.validTime?.let { validTime ->
            temporalView(graph, validTime) ?: return unknownTimeline(validTime, json)
        }
        val allowedKinds = command.kinds.ifEmpty { setOf(CliKind.Node, CliKind.Media) }
        val candidates = select(graph, allowedKinds, emptySet(), includeDerived = false, view)
            .filter { it.id == command.id }
        val problem = candidateProblem(command.id, candidates)
        if (problem != null) return queryResult("", queryDiagnosticsFor(graph, view) + problem, json)
        val selected = relationsFor(
            graph = graph,
            id = command.id,
            direction = command.direction,
            types = command.types,
            includeDerived = command.includeDerived,
            view = view,
        )
        val output = if (json) {
            jsonArray(selected.map { RelationItem(it).detailJson(graph, view) }).encode() + "\n"
        } else {
            renderRelations(selected, view)
        }
        return queryResult(output, queryDiagnosticsFor(graph, view), json)
    }

    private fun lint(graph: GraphCompilationResult, command: CliCommand.Lint, json: Boolean): CliResult {
        val view = command.validTime?.let { validTime ->
            temporalView(graph, validTime) ?: return unknownTimeline(validTime, json)
        }
        val diagnostics = (
            diagnosticsFor(graph, view) +
                view.orNullAssertionDiagnostics()
            )
            .sortedWith(diagnosticComparator)
        val output = if (json) {
            jsonArray(diagnostics.map(Diagnostic::toJson)).encode() + "\n"
        } else {
            diagnostics.joinToString(separator = "") { renderDiagnostic(it) }
        }
        return CliResult(stdout = output, exitCode = diagnostics.exitCode())
    }

    private fun stats(
        graph: GraphCompilationResult,
        command: CliCommand.Stats,
        json: Boolean,
    ): CliResult {
        val view = command.validTime?.let { validTime ->
            temporalView(graph, validTime) ?: return unknownTimeline(validTime, json)
        }
        val items = select(graph, command.kinds, command.types, command.includeDerived, view)
        val counts = CliKind.entries.associateWith { kind -> items.count { it.kind == kind } }
        val diagnostics = diagnosticsFor(graph, view)
        val warnings = diagnostics.count { it.severity == Severity.Warning }
        val errors = diagnostics.count { it.severity == Severity.Error }
        val value = jsonObject(
            "node" to jsonNumber(counts.getValue(CliKind.Node)),
            "media" to jsonNumber(counts.getValue(CliKind.Media)),
            "link" to jsonNumber(counts.getValue(CliKind.Link)),
            "nodeType" to jsonNumber(counts.getValue(CliKind.NodeType)),
            "relType" to jsonNumber(counts.getValue(CliKind.RelType)),
            "timeline" to jsonNumber(counts.getValue(CliKind.Timeline)),
            "warnings" to jsonNumber(warnings),
            "errors" to jsonNumber(errors),
            "full" to jsonNumber(items.count { it is NodeItem && (view?.visibility(it.node) ?: Visibility.Full) == Visibility.Full }),
            "assertionOnly" to jsonNumber(
                items.count { it is NodeItem && view?.visibility(it.node) == Visibility.AssertionOnly },
            ),
        )
        val output = if (json) {
            value.encode() + "\n"
        } else {
            buildString {
                counts.forEach { (kind, count) -> append(kind.wireName).append('\t').append(count).append('\n') }
                append("warnings\t").append(warnings).append('\n')
                append("errors\t").append(errors).append('\n')
                append("full\t")
                    .append(items.count { it is NodeItem && (view?.visibility(it.node) ?: Visibility.Full) == Visibility.Full })
                    .append('\n')
                append("assertion-only\t")
                    .append(items.count { it is NodeItem && view?.visibility(it.node) == Visibility.AssertionOnly })
                    .append('\n')
            }
        }
        return queryResult(output, diagnostics.filter { it.severity == Severity.Error }, json)
    }

    private fun select(
        graph: GraphCompilationResult,
        kinds: Set<CliKind>,
        types: Set<String>,
        includeDerived: Boolean,
        view: TemporalView?,
    ): List<GraphItem> {
        val selectedKinds = kinds.ifEmpty { CliKind.entries.toSet() }
        val nodeTypes = graph.nodeTypes.associateBy { it.id }
        val relTypes = graph.relTypes.associateBy { it.id }
        return buildList {
            graph.nodes.forEach { node ->
                val kind = if (node.kind == DocumentKind.Media) CliKind.Media else CliKind.Node
                if (
                    kind in selectedKinds &&
                    (view == null || view.visibility(node) != Visibility.Hidden) &&
                    typeMatches(node.type, types, includeDerived, nodeTypes[node.type]?.ancestorIds.orEmpty())
                ) {
                    add(NodeItem(node))
                }
            }
            if (CliKind.Link in selectedKinds) {
                graph.relations.forEach { relation ->
                    if (
                        (view == null || view.relationVisible(relation)) &&
                        typeMatches(relation.type, types, includeDerived, relTypes[relation.type]?.ancestorIds.orEmpty())
                    ) {
                        add(RelationItem(relation))
                    }
                }
            }
            if (types.isEmpty() && view == null) {
                if (CliKind.NodeType in selectedKinds) graph.nodeTypes.forEach { add(NodeTypeItem(it)) }
                if (CliKind.RelType in selectedKinds) graph.relTypes.forEach { add(RelTypeItem(it)) }
                if (CliKind.Timeline in selectedKinds) graph.timelines.forEach { add(TimelineItem(it)) }
            }
        }.sortedWith(graphItemComparator)
    }

    private fun typeMatches(
        actual: String,
        requested: Set<String>,
        includeDerived: Boolean,
        ancestors: Set<String>,
    ): Boolean = requested.isEmpty() || requested.any { it == actual || includeDerived && it in ancestors }

    private fun relationsFor(
        graph: GraphCompilationResult,
        id: String,
        direction: LinkDirection,
        types: Set<String>,
        includeDerived: Boolean,
        view: TemporalView?,
    ): List<NormalizedRelation> {
        val relTypes = graph.relTypes.associateBy { it.id }
        return graph.relations.filter { relation ->
            val directionMatches = when (direction) {
                LinkDirection.Incoming -> relation.to == id
                LinkDirection.Outgoing -> relation.from == id
                LinkDirection.Both -> relation.from == id || relation.to == id
            }
            directionMatches && (view == null || view.relationVisible(relation)) && typeMatches(
                relation.type,
                types,
                includeDerived,
                relTypes[relation.type]?.ancestorIds.orEmpty(),
            )
        }.sortedWith(relationComparator)
    }

    private fun temporalView(graph: GraphCompilationResult, validTime: ValidTimeFilter): TemporalView? {
        return graph.timelines.firstOrNull { it.id == validTime.timeline }?.let {
            TemporalView(graph, validTime, it)
        }
    }

    private fun unknownTimeline(
        validTime: ValidTimeFilter,
        json: Boolean,
    ): CliResult = queryResult(
        "",
        listOf(cliDiagnostic("Unknown Timeline in --valid-time: ${validTime.timeline}")),
        json,
    )

    private fun diagnosticsFor(
        graph: GraphCompilationResult,
        view: TemporalView?,
    ): List<Diagnostic> = if (view == null) {
        graph.diagnostics
    } else {
        graph.diagnostics.filter { it.source?.path in view.visibleSourcePaths }
    }

    private fun queryDiagnosticsFor(
        graph: GraphCompilationResult,
        view: TemporalView?,
    ): List<Diagnostic> = diagnosticsFor(graph, view).filter { it.severity == Severity.Error }

    private fun candidateProblem(id: String, candidates: List<GraphItem>): Diagnostic? = when {
        candidates.isEmpty() -> cliDiagnostic("No entity found with ID: $id")
        candidates.size > 1 -> cliDiagnostic(
            "ID is ambiguous across kinds (${candidates.map { it.kind.wireName }.distinct().joinToString()}): $id; specify --kind",
        )
        else -> null
    }

    private fun TemporalView?.orNullAssertionDiagnostics(): List<Diagnostic> =
        this?.assertionOnlyIds.orEmpty().sorted().map(::assertionOnlyDiagnostic)

    private fun queryResult(output: String, diagnostics: List<Diagnostic>, json: Boolean): CliResult {
        val sorted = diagnostics.sortedWith(diagnosticComparator)
        val errors = sorted.exitCode()
        val stderr = if (sorted.isEmpty()) "" else if (json) {
            jsonArray(sorted.map(Diagnostic::toJson)).encode() + "\n"
        } else {
            sorted.joinToString(separator = "") { renderDiagnostic(it) }
        }
        return CliResult(stdout = output, stderr = stderr, exitCode = errors)
    }

    private fun usageError(message: String, exitCode: Int = 2): CliResult =
        CliResult(stderr = "error: $message\nTry 'graphmd --help' for usage.\n", exitCode = exitCode)
}

private class TemporalView(
    graph: GraphCompilationResult,
    private val requested: ValidTimeFilter,
    private val requestedTimeline: NormalizedTimeline,
) {
    private val requestedTimelineId = TimelineId(requested.timeline)
    private val engine = TemporalEngine(graph.temporalModel)
    private val catalog = TimelineCatalog.from(graph.timelines)
    private val parsedFrom = requested.from?.let { engine.parse(requested.timeline, it).coordinate }
    private val parsedTo = requested.to?.let { engine.parse(requested.timeline, it).coordinate }

    init {
        val normalizedFrom = parsedFrom?.let { engine.normalizeToAxis(requested.timeline, it) }
        val normalizedTo = parsedTo?.let { engine.normalizeToAxis(requested.timeline, it) }
        require(normalizedFrom == null || normalizedTo == null || normalizedFrom <= normalizedTo) {
            "--valid-time from must not exceed to"
        }
    }

    private val requestedWindow = catalog.searchIntervals(
        timelineId = requestedTimelineId,
        start = parsedFrom?.let { it to true },
        end = parsedTo?.let { it to true },
    )
    private val nodesById = graph.nodes.associateBy { it.id }
    private val visibleRelations = graph.relations.filter { assertedAt(it.validTime) }
    private val linkedIds = visibleRelations.flatMapTo(hashSetOf()) { listOf(it.from, it.to) }

    val visibleSourcePaths: Set<String> = graph.nodes
        .filter(::documentVisible)
        .mapTo(hashSetOf()) { it.source.path }

    val assertionOnlyIds: Set<String>
        get() = nodesById.values
            .filter { visibility(it) == Visibility.AssertionOnly }
            .mapTo(linkedSetOf()) { it.id }

    fun visibility(node: NormalizedNode): Visibility = when {
        documentVisible(node) -> Visibility.Full
        filterProperties(node.propEntries).isNotEmpty() || node.id in linkedIds -> Visibility.AssertionOnly
        else -> Visibility.Hidden
    }

    fun visibility(id: String): Visibility =
        nodesById[id]?.let(::visibility)
            ?: if (id in linkedIds) Visibility.AssertionOnly else Visibility.Hidden

    private fun documentVisible(node: NormalizedNode): Boolean = assertedAt(node.validTime)

    fun relationVisible(relation: NormalizedRelation): Boolean = assertedAt(relation.validTime)

    fun filterProperties(
        entries: Map<String, List<NormalizedPropEntry>>,
    ): Map<String, List<NormalizedPropEntry>> = entries.mapNotNull { (name, assertions) ->
        val timedUnion = assertions.filterNot { it.isFallback }.fold(IntervalSet.empty()) { result, assertion ->
            result union requestedIntervals(assertion.validTime)
        }
        assertions.mapNotNull { filterEntry(it, timedUnion) }
            .takeIf { it.isNotEmpty() }
            ?.let { name to it }
    }.toMap(linkedMapOf())

    private fun filterEntry(
        entry: NormalizedPropEntry,
        timedUnion: IntervalSet = IntervalSet.empty(),
    ): NormalizedPropEntry? {
        val entryIntervals = requestedIntervals(entry.validTime)
        val effectiveIntervals = if (entry.isFallback && !timedUnion.isEmpty) {
            entryIntervals.subtract(timedUnion)
        } else {
            entryIntervals
        }
        val asserted = !(effectiveIntervals intersect requestedWindow).isEmpty
        val filtered = filterValue(entry.value)
        if (!asserted && !filtered.containsAssertion) return null
        return entry.copy(
            value = filtered.value,
            validTime = entry.validTime.takeIf { asserted }.orEmpty(),
        )
    }

    private fun filterValue(value: NormalizedValue): FilteredValue = when (value) {
        is TextValue -> {
            val entries = value.memberEntries.mapNotNull { (name, entry) ->
                filterEntry(entry)?.let { name to it }
            }.toMap(linkedMapOf())
            FilteredValue(TextValue(entries), entries.isNotEmpty())
        }
        is ArrayValue -> {
            val elements = value.elements.mapNotNull { element ->
                val asserted = assertedAt(element.validTime)
                val filtered = filterValue(element.value)
                if (!asserted && !filtered.containsAssertion) {
                    null
                } else {
                    element.copy(
                        value = filtered.value,
                        validTime = element.validTime.takeIf { asserted }.orEmpty(),
                    )
                }
            }
            FilteredValue(ArrayValue(elements.map { it.value }, elements), elements.isNotEmpty())
        }
        is ObjectValue -> {
            val members = value.members.mapNotNull { (name, entry) ->
                filterEntry(entry)?.let { name to it }
            }.toMap(linkedMapOf())
            FilteredValue(ObjectValue(members.mapValues { it.value.value }, members), members.isNotEmpty())
        }
        else -> FilteredValue(value, containsAssertion = false)
    }

    private fun assertedAt(validTimes: List<ValidTime>): Boolean =
        !(requestedIntervals(validTimes) intersect requestedWindow).isEmpty

    private fun requestedIntervals(validTimes: List<ValidTime>): IntervalSet = catalog.fromValidTimes(validTimes)
}

private data class FilteredValue(
    val value: NormalizedValue,
    val containsAssertion: Boolean,
)

private sealed interface GraphItem {
    val kind: CliKind
    val id: String
    val sourcePath: String
    fun summaryJson(view: TemporalView?): JsonValue
    fun detailJson(graph: GraphCompilationResult, view: TemporalView?): JsonValue
}

private data class NodeItem(val node: NormalizedNode) : GraphItem {
    override val kind: CliKind = if (node.kind == DocumentKind.Media) CliKind.Media else CliKind.Node
    override val id: String = node.id
    override val sourcePath: String = node.source.path

    override fun summaryJson(view: TemporalView?): JsonValue {
        val visibility = view?.visibility(node) ?: Visibility.Full
        if (visibility == Visibility.AssertionOnly) {
            return jsonObject(
                "kind" to jsonString(kind.wireName),
                "id" to jsonString(id),
                "visibility" to jsonString(visibility.wireName),
            )
        }
        return jsonObject(
            "kind" to jsonString(kind.wireName),
            "id" to jsonString(id),
            "visibility" to jsonString(visibility.wireName),
            "type" to jsonString(node.type),
            "url" to jsonNullableString(node.url),
            "source" to node.source.toJson(),
        )
    }

    override fun detailJson(graph: GraphCompilationResult, view: TemporalView?): JsonValue {
        val relations = graph.relations.filter { view == null || view.relationVisible(it) }
        val incoming = relations.filter { it.to == id }.sortedWith(relationComparator)
        val outgoing = relations.filter { it.from == id }.sortedWith(relationComparator)
        val visibility = view?.visibility(node) ?: Visibility.Full
        if (visibility == Visibility.AssertionOnly) {
            return jsonObject(
                "kind" to jsonString(kind.wireName),
                "id" to jsonString(id),
                "visibility" to jsonString(visibility.wireName),
                "props" to propertyEntriesToJson(view?.filterProperties(node.propEntries).orEmpty()),
                "incomingLinks" to jsonArray(incoming.map { RelationItem(it).detailJson(graph, view) }),
                "outgoingLinks" to jsonArray(outgoing.map { RelationItem(it).detailJson(graph, view) }),
            )
        }
        return jsonObject(
            "kind" to jsonString(kind.wireName),
            "id" to jsonString(id),
            "visibility" to jsonString(visibility.wireName),
            "type" to jsonString(node.type),
            "url" to jsonNullableString(node.url),
            "validTime" to jsonArray(node.validTime.map(ValidTime::toJson)),
            "props" to propertyEntriesToJson(view?.filterProperties(node.propEntries) ?: node.propEntries),
            "incomingLinks" to jsonArray(incoming.map { RelationItem(it).detailJson(graph, view) }),
            "outgoingLinks" to jsonArray(outgoing.map { RelationItem(it).detailJson(graph, view) }),
            "source" to node.source.toJson(),
        )
    }
}

private data class RelationItem(val relation: NormalizedRelation) : GraphItem {
    override val kind: CliKind = CliKind.Link
    override val id: String = "${relation.from}->${relation.to}:${relation.type}"
    override val sourcePath: String = relation.source.path

    override fun summaryJson(view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "from" to jsonString(relation.from),
        "to" to jsonString(relation.to),
        "type" to jsonString(relation.type),
        "label" to jsonString(relation.sourceLabel),
        "fromVisibility" to jsonString((view?.visibility(relation.from) ?: Visibility.Full).wireName),
        "toVisibility" to jsonString((view?.visibility(relation.to) ?: Visibility.Full).wireName),
        "source" to relation.source.toJson(),
    )

    override fun detailJson(graph: GraphCompilationResult, view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "from" to jsonString(relation.from),
        "to" to jsonString(relation.to),
        "type" to jsonString(relation.type),
        "label" to jsonString(relation.sourceLabel),
        "fromVisibility" to jsonString((view?.visibility(relation.from) ?: Visibility.Full).wireName),
        "toVisibility" to jsonString((view?.visibility(relation.to) ?: Visibility.Full).wireName),
        "validTime" to jsonArray(relation.validTime.map(ValidTime::toJson)),
        "props" to propertyEntriesToJson(view?.filterProperties(relation.propEntries) ?: relation.propEntries),
        "targetUrl" to jsonNullableString(relation.targetUrl),
        "source" to relation.source.toJson(),
    )
}

private data class NodeTypeItem(val type: NormalizedNodeType) : GraphItem {
    override val kind = CliKind.NodeType
    override val id = type.id
    override val sourcePath = type.source.path
    override fun summaryJson(view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "id" to jsonString(id),
        "source" to type.source.toJson(),
    )
    override fun detailJson(graph: GraphCompilationResult, view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "id" to jsonString(id),
        "ancestors" to jsonArray(type.ancestorIds.sorted().map(::jsonString)),
        "props" to JsonValue.Object(type.props.sortedByKey().mapValues { it.value.toJson() }),
        "source" to type.source.toJson(),
    )
}

private data class RelTypeItem(val type: NormalizedRelType) : GraphItem {
    override val kind = CliKind.RelType
    override val id = type.id
    override val sourcePath = type.source.path
    override fun summaryJson(view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "id" to jsonString(id),
        "source" to type.source.toJson(),
    )
    override fun detailJson(graph: GraphCompilationResult, view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "id" to jsonString(id),
        "from" to (type.from?.let { jsonArray(it.map(::jsonString)) } ?: JsonValue.Null),
        "to" to (type.to?.let { jsonArray(it.map(::jsonString)) } ?: JsonValue.Null),
        "ancestors" to jsonArray(type.ancestorIds.sorted().map(::jsonString)),
        "props" to JsonValue.Object(type.props.sortedByKey().mapValues { it.value.toJson() }),
        "source" to type.source.toJson(),
    )
}

private data class TimelineItem(val timeline: NormalizedTimeline) : GraphItem {
    override val kind = CliKind.Timeline
    override val id = timeline.id
    override val sourcePath = timeline.source.path
    override fun summaryJson(view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "id" to jsonString(id),
        "source" to timeline.source.toJson(),
    )
    override fun detailJson(graph: GraphCompilationResult, view: TemporalView?): JsonValue = jsonObject(
        "kind" to jsonString(kind.wireName),
        "id" to jsonString(id),
        "domain" to jsonString(timeline.domainId),
        "axis" to jsonString(timeline.axisId),
        "coordinate" to timeline.coordinate.toJson(),
        "lineage" to (timeline.lineage?.toJson() ?: JsonValue.Null),
        "mappings" to jsonArray(timeline.temporalMappings.map(TemporalMappingInstance::toJson)),
        "props" to JsonValue.Object(timeline.props.sortedByKey().mapValues { it.value.toJson() }),
        "source" to timeline.source.toJson(),
    )
}

private val relationComparator = compareBy<NormalizedRelation>(
    { it.type },
    { it.from },
    { it.to },
    { it.source.path },
    { it.source.range?.start ?: -1 },
)

private val graphItemComparator = Comparator<GraphItem> { left, right ->
    compareValuesBy(left, right, { it.kind.order }, { it.id }, { it.sourcePath })
}

private val diagnosticComparator = compareBy<Diagnostic>(
    { it.source?.path.orEmpty() },
    { it.source?.range?.start ?: -1 },
    { it.severity.ordinal },
    { it.category.ordinal },
    { it.message },
)

private fun List<Diagnostic>.exitCode(): Int = if (any { it.severity == Severity.Error }) 1 else 0

private class GmqlParameterException(message: String) : IllegalArgumentException(message)

private fun parseGmqlParameter(name: String, encoded: String): GmqlValue {
    val value = encoded.trim()
    return when {
        value == "null" -> GmqlValue.NullValue
        value.equals("true", ignoreCase = true) -> GmqlValue.BooleanValue(true)
        value.equals("false", ignoreCase = true) -> GmqlValue.BooleanValue(false)
        INTEGER_PARAMETER.matches(value) -> value.toLongOrNull()?.let(GmqlValue::IntegerValue)
            ?: throw GmqlParameterException("Parameter '$name' is outside the Integer range")
        DECIMAL_PARAMETER.matches(value) -> value.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?.let(GmqlValue::DecimalValue)
            ?: throw GmqlParameterException("Parameter '$name' is not a finite Decimal")
        value.startsWith('"') -> GmqlValue.StringValue(
            decodeParameterString(value)
                ?: throw GmqlParameterException("Parameter '$name' contains an invalid quoted string"),
        )
        else -> GmqlValue.StringValue(encoded)
    }
}

private fun decodeParameterString(encoded: String): String? {
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

private fun renderSearchResult(result: GmqlQueryResult): String {
    if (result.columns.isEmpty()) return ""
    return buildString {
        append(result.columns.joinToString("\t") { it.name }).append('\n')
        result.rows.forEach { row ->
            append(row.values.joinToString("\t", transform = ::renderSearchValue)).append('\n')
        }
    }
}

private fun renderSearchValue(value: GmqlValue): String = when (value) {
    is GmqlValue.StringValue -> value.value.replace("\t", "\\t").replace("\n", "\\n")
    is GmqlValue.IntegerValue -> value.value.toString()
    is GmqlValue.DecimalValue -> value.value.graphNumberText()
    is GmqlValue.BooleanValue -> value.value.toString()
    GmqlValue.NullValue -> "null"
    is GmqlValue.NodeValue -> value.id.value
    is GmqlValue.RelationValue -> value.id.value.toString()
    is GmqlValue.TypeRefValue -> value.name
    else -> value.toJson().encode()
}

private fun renderGmqlDiagnostic(diagnostic: GmqlDiagnostic): String = buildString {
    append("error[").append(diagnostic.code).append("]: ").append(diagnostic.message)
    diagnostic.range?.let { append(" (").append(it.start).append("..").append(it.end).append(')') }
    append('\n')
}

private fun <T> runCliSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "CLI query execution suspended unexpectedly" }.getOrThrow()
}

private fun markdownBodyStart(source: String): Int {
    if (!source.startsWith("---")) return 0
    var offset = source.indexOf('\n').takeIf { it >= 0 }?.plus(1) ?: return 0
    while (offset < source.length) {
        val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
        if (source.substring(offset, lineEnd).trim() in setOf("---", "...")) {
            return (lineEnd + 1).coerceAtMost(source.length)
        }
        offset = (lineEnd + 1).coerceAtMost(source.length)
    }
    return 0
}

private fun relativeMarkdownPath(fromFile: String, targetFile: String): String? {
    fun normalized(path: String): Pair<String?, List<String>> {
        val unix = path.replace('\\', '/')
        val drive = unix.takeIf { it.length >= 2 && it[1] == ':' }?.substring(0, 2)?.lowercase()
        return drive to unix.substringAfter(':', unix).split('/').filter { it.isNotEmpty() && it != "." }
    }
    val (fromDrive, fromParts) = normalized(fromFile)
    val (targetDrive, targetParts) = normalized(targetFile)
    if (fromDrive != targetDrive || fromParts.isEmpty() || targetParts.isEmpty()) return null
    val fromDirectory = fromParts.dropLast(1)
    var common = 0
    while (common < fromDirectory.size && common < targetParts.size && fromDirectory[common] == targetParts[common]) common++
    return (List(fromDirectory.size - common) { ".." } + targetParts.drop(common)).joinToString("/")
        .ifEmpty { targetParts.last() }
}

private val INTEGER_PARAMETER = Regex("""[-+]?[0-9]+""")
private val DECIMAL_PARAMETER =
    Regex("""[-+]?(?:(?:[0-9]+\.[0-9]*|[0-9]*\.[0-9]+)(?:[eE][-+]?[0-9]+)?|[0-9]+[eE][-+]?[0-9]+)""")

private fun cliDiagnostic(message: String): Diagnostic =
    Diagnostic(DiagnosticCategory.ReferenceError, Severity.Error, message)

private fun assertionOnlyDiagnostic(id: String): Diagnostic =
    Diagnostic(
        DiagnosticCategory.ReferenceError,
        Severity.Warning,
        "Document $id is outside --valid-time; showing matching assertions and ID only",
    )

private fun renderDiagnostic(diagnostic: Diagnostic): String {
    val location = diagnostic.source?.let { source ->
        buildString {
            append(source.path)
            source.range?.let { append(':').append(it.start) }
            append(": ")
        }
    }.orEmpty()
    return "$location${diagnostic.severity.name.lowercase()} ${diagnostic.category.name}: ${diagnostic.message}\n"
}

private fun renderList(items: List<GraphItem>, view: TemporalView?): String = buildString {
    append("KIND\tID\tVISIBILITY\tTYPE\tSOURCE\n")
    items.forEach { item ->
        when (item) {
            is NodeItem -> {
                val visibility = view?.visibility(item.node) ?: Visibility.Full
                append(item.kind.wireName).append('\t').append(item.id).append('\t')
                    .append(visibility.wireName).append('\t')
                if (visibility == Visibility.AssertionOnly) {
                    append("-\t-\n")
                } else {
                    append(item.node.type).append('\t').append(item.sourcePath).append('\n')
                }
            }
            is RelationItem -> append("link\t").append(item.relation.from).append(" -> ")
                .append(item.relation.to).append("\t-\t").append(item.relation.type).append('\t')
                .append(item.sourcePath).append('\n')
            else -> append(item.kind.wireName).append('\t').append(item.id).append("\tfull\t-\t")
                .append(item.sourcePath).append('\n')
        }
    }
}

private fun renderShow(item: GraphItem, graph: GraphCompilationResult, view: TemporalView?): String = buildString {
    append("Kind: ").append(item.kind.wireName).append('\n')
    append("ID: ").append(item.id).append('\n')
    when (item) {
        is NodeItem -> {
            val visibility = view?.visibility(item.node) ?: Visibility.Full
            append("Visibility: ").append(visibility.wireName).append('\n')
            if (visibility == Visibility.Full) {
                append("Source: ").append(item.sourcePath).append('\n')
                append("Type: ").append(item.node.type).append('\n')
                item.node.url?.let { append("URL: ").append(it).append('\n') }
            }
            append("\nProperties:\n")
                .append(renderProperties(view?.filterProperties(item.node.propEntries) ?: item.node.propEntries))
            append("\nIncoming links:\n")
            append(
                renderRelations(
                    graph.relations
                        .filter { it.to == item.id && (view == null || view.relationVisible(it)) }
                        .sortedWith(relationComparator),
                    view,
                ),
            )
            append("\nOutgoing links:\n")
            append(
                renderRelations(
                    graph.relations
                        .filter { it.from == item.id && (view == null || view.relationVisible(it)) }
                        .sortedWith(relationComparator),
                    view,
                ),
            )
        }
        is NodeTypeItem -> {
            append("Source: ").append(item.sourcePath).append('\n')
            append("Ancestors: ").append(item.type.ancestorIds.sorted().joinToString()).append('\n')
            append("Property schemas:\n")
            item.type.props.sortedByKey().forEach { (name, schema) ->
                append(name).append('\t').append(schema.type.name)
                if (schema.required) append("\trequired")
                append('\n')
            }
        }
        is RelTypeItem -> {
            append("Source: ").append(item.sourcePath).append('\n')
            append("From: ").append(item.type.from?.joinToString() ?: "*").append('\n')
            append("To: ").append(item.type.to?.joinToString() ?: "*").append('\n')
            append("Ancestors: ").append(item.type.ancestorIds.sorted().joinToString()).append('\n')
            append("Property schemas:\n")
            item.type.props.sortedByKey().forEach { (name, schema) ->
                append(name).append('\t').append(schema.type.name)
                if (schema.required) append("\trequired")
                append('\n')
            }
        }
        is TimelineItem -> {
            append("Source: ").append(item.sourcePath).append('\n')
            append("Domain: ").append(item.timeline.domainId).append('\n')
            append("Axis: ").append(item.timeline.axisId).append('\n')
            append("Coordinate: ").append(renderCoordinateSpec(item.timeline.coordinate)).append('\n')
            append("Lineage: ").append(item.timeline.lineage?.kind?.name?.lowercase() ?: "-").append('\n')
            append("Mappings: ").append(item.timeline.temporalMappings.size).append('\n')
        }
        is RelationItem -> Unit
    }
}

private fun renderProperties(
    entries: Map<String, List<NormalizedPropEntry>>,
    ownerId: String? = null,
    ownerVisibility: Visibility? = null,
): String = buildString {
    if (ownerId != null && ownerVisibility != null) {
        append("OWNER_ID\tOWNER_VISIBILITY\t")
    }
    append("NAME\tVALUE\tVALID_TIME\tFALLBACK\n")
    entries.sortedByKey().forEach { (name, values) ->
        values.forEach { entry ->
            val continuationPrefix: String
            if (ownerId != null && ownerVisibility != null) {
                append(ownerId).append('\t').append(ownerVisibility.wireName).append('\t')
                continuationPrefix = "\t\t\t"
            } else {
                continuationPrefix = "\t"
            }
            val valueLines = renderPropertyValue(entry.value).lines()
            append(name).append('\t').append(valueLines.first()).append('\t')
                .append(renderValidTimes(entry.validTime)).append('\t').append(entry.isFallback).append('\n')
            valueLines.drop(1).forEach { line ->
                append(continuationPrefix).append(line).append('\n')
            }
        }
    }
}

private fun renderPropertyValue(value: NormalizedValue): String = when (value) {
    is StringValue -> renderTabularText(value.value)
    is IntegerValue -> value.value.toString()
    is dev.usbharu.graphmd.core.model.NumberValue -> value.value.graphNumberText()
    is BooleanValue -> value.value.toString()
    NullValue -> "null"
    is TextValue -> renderNestedEntries("text", value.memberEntries)
    is ArrayValue -> renderArrayValue(value)
    is ObjectValue -> renderNestedEntries("object", value.members)
    is InstantValue -> renderFields(
        "instant",
        listOf(
            "timeline" to renderNullableText(value.timeline),
            "value" to renderNullableText(value.value),
            "coordinate" to renderCoordinate(value.coordinate),
        ),
    )
    is DurationValue -> renderFields(
        "duration",
        listOf(
            "timeline" to renderNullableText(value.timeline),
            "from" to (value.from?.let(::renderTemporalPoint) ?: "null"),
            "to" to (value.to?.let(::renderTemporalPoint) ?: "null"),
        ),
    )
}

private fun renderTabularText(value: String): String =
    value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")

private fun renderNestedEntries(
    kind: String,
    entries: Map<String, NormalizedPropEntry>,
): String {
    if (entries.isEmpty()) return "$kind {}"
    return buildString {
        append(kind).append(" {\n")
        entries.sortedByKey().forEach { (name, entry) ->
            append("  ").append(renderTabularText(name)).append(":\n")
            append(renderField("value", renderPropertyValue(entry.value), "    ")).append('\n')
            append("    validTime: ").append(renderValidTimes(entry.validTime)).append('\n')
            append("    fallback: ").append(entry.isFallback).append('\n')
        }
        append('}')
    }
}

private fun renderArrayValue(value: ArrayValue): String {
    if (value.elements.isEmpty()) return "array []"
    return buildString {
        append("array [\n")
        value.elements.forEachIndexed { index, element ->
            append("  [").append(index).append("]:\n")
            append(renderField("value", renderPropertyValue(element.value), "    ")).append('\n')
            append("    validTime: ").append(renderValidTimes(element.validTime)).append('\n')
            append("    fallback: ").append(element.isFallback).append('\n')
        }
        append(']')
    }
}

private fun renderFields(kind: String, fields: List<Pair<String, String>>): String = buildString {
    append(kind).append(" {\n")
    fields.forEach { (name, value) ->
        append(renderField(name, value, "  ")).append('\n')
    }
    append('}')
}

private fun renderField(name: String, value: String, indent: String): String {
    val lines = value.lines()
    if (lines.size == 1) return "$indent$name: ${lines.single()}"
    return buildString {
        append(indent).append(name).append(":\n")
        lines.forEachIndexed { index, line ->
            append(indent).append("  ").append(line)
            if (index != lines.lastIndex) append('\n')
        }
    }
}

private fun renderNullableText(value: String?): String = value?.let(::renderTabularText) ?: "null"

private fun renderTemporalPoint(value: TemporalPoint): String = renderFields(
    "timePoint",
    listOf(
        "timeline" to renderNullableText(value.timeline),
        "value" to renderNullableText(value.value),
        "coordinate" to renderCoordinate(value.coordinate),
    ),
)

private fun renderValidTimes(validTimes: List<ValidTime>): String =
    if (validTimes.isEmpty()) {
        "-"
    } else {
        validTimes.joinToString(", ") { validTime ->
            val from = validTime.from?.let(::renderValidTimePoint)
            val to = validTime.to?.let(::renderValidTimePoint)
            when {
                from == null && to == null -> validTime.timeline
                from == null -> "${validTime.timeline}: – $to"
                to == null -> "${validTime.timeline}: $from –"
                else -> "${validTime.timeline}: $from – $to"
            }
        }
    }

private fun renderValidTimePoint(value: TimePoint): String =
    value.value?.let { "${renderTabularText(it)} (${renderCoordinate(value.coordinate)})" }
        ?: renderCoordinate(value.coordinate)

private fun renderCoordinate(value: TemporalCoordinate): String = when (value) {
    is TemporalCoordinate.Rational -> value.value.toString()
    is TemporalCoordinate.CalendarDate -> "${value.year}-${value.month.toString().padStart(2, '0')}-${value.day.toString().padStart(2, '0')}"
    is TemporalCoordinate.EraDate -> "${value.era} ${value.year}-${value.month.toString().padStart(2, '0')}-${value.day.toString().padStart(2, '0')}"
    is TemporalCoordinate.FrameIndex -> value.value.toString()
    is TemporalCoordinate.Timecode -> "${value.hours.toString().padStart(2, '0')}:${value.minutes.toString().padStart(2, '0')}:" +
        "${value.seconds.toString().padStart(2, '0')}:${value.frames.toString().padStart(2, '0')}"
    is TemporalCoordinate.Label -> value.value
}

private fun renderCoordinateSpec(value: TemporalCoordinateSpec): String = when (value) {
    TemporalCoordinateSpec.Number -> "number"
    is TemporalCoordinateSpec.Calendar -> "calendar:${value.calendar.name.lowercase()}"
    is TemporalCoordinateSpec.Frame -> "frame"
    is TemporalCoordinateSpec.Timecode -> "timecode:${value.actualFps}"
    is TemporalCoordinateSpec.Era -> "era"
}

private fun renderRelations(relations: List<NormalizedRelation>, view: TemporalView? = null): String = buildString {
    append("TYPE\tFROM\tFROM_VISIBILITY\tTO\tTO_VISIBILITY\tLABEL\tVALID_TIME\tSOURCE\n")
    relations.forEach { relation ->
        append(relation.type).append('\t')
            .append(relation.from).append('\t')
            .append((view?.visibility(relation.from) ?: Visibility.Full).wireName).append('\t')
            .append(relation.to).append('\t')
            .append((view?.visibility(relation.to) ?: Visibility.Full).wireName).append('\t')
            .append(relation.sourceLabel).append('\t')
            .append(renderValidTimes(relation.validTime)).append('\t')
            .append(relation.source.path).append('\n')
    }
}
