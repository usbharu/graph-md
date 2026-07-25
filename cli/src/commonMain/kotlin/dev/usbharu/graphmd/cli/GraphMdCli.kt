package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.*

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
    private val fileSystem: CliFileSystem = SystemCliFileSystem,
) {
    fun run(arguments: List<String>): CliResult {
        return when (val parsed = CliArguments.parse(arguments)) {
            is ParseResult.Print -> CliResult(stdout = parsed.text)
            is ParseResult.Error -> usageError(parsed.message)
            is ParseResult.Run -> try {
                execute(parsed)
            } catch (exception: CliIoException) {
                usageError(exception.message ?: "I/O error")
            } catch (exception: Throwable) {
                usageError(exception.message ?: "Unexpected error")
            }
        }
    }

    private fun execute(invocation: ParseResult.Run): CliResult {
        val command = invocation.command
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
        return queryResult(output, diagnosticsFor(graph, view), json)
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
        if (problem != null) return queryResult("", diagnosticsFor(graph, view) + problem, json)
        val item = candidates.single()
        val output = if (json) {
            item.detailJson(graph, view).encode() + "\n"
        } else {
            renderShow(item, graph, view)
        }
        return queryResult(output, diagnosticsFor(graph, view), json)
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
        if (problem != null) return queryResult("", diagnosticsFor(graph, view) + problem, json)
        val node = (candidates.single() as NodeItem).node
        val entries = view?.filterProperties(node.propEntries) ?: node.propEntries
        val visibility = view?.visibility(node) ?: Visibility.Full
        val output = if (json) {
            propertyEntriesToJson(entries, node.id, visibility.wireName).encode() + "\n"
        } else {
            renderProperties(entries, node.id, visibility)
        }
        return queryResult(output, diagnosticsFor(graph, view), json)
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
        if (problem != null) return queryResult("", diagnosticsFor(graph, view) + problem, json)
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
        return queryResult(output, diagnosticsFor(graph, view), json)
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
        return queryResult(output, diagnostics, json)
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

    private fun usageError(message: String): CliResult =
        CliResult(stderr = "error: $message\nTry 'graphmd --help' for usage.\n", exitCode = 2)
}

private class TemporalView(
    graph: GraphCompilationResult,
    private val requested: ValidTimeFilter,
    private val requestedTimeline: NormalizedTimeline,
) {
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
        assertions.mapNotNull(::filterEntry).takeIf { it.isNotEmpty() }?.let { name to it }
    }.toMap(linkedMapOf())

    private fun filterEntry(entry: NormalizedPropEntry): NormalizedPropEntry? =
        entry.takeIf { assertedAt(it.validTime) }?.copy(value = filterValue(entry.value))

    private fun filterValue(value: NormalizedValue): NormalizedValue = when (value) {
        is TextValue -> TextValue(
            value.memberEntries.mapNotNull { (name, entry) ->
                filterEntry(entry)?.let { name to it }
            }.toMap(linkedMapOf()),
        )
        is ArrayValue -> {
            val elements = value.elements.mapNotNull { element ->
                element.takeIf { assertedAt(it.validTime) }?.copy(value = filterValue(element.value))
            }
            ArrayValue(elements.map { it.value }, elements)
        }
        is ObjectValue -> {
            val members = value.members.mapNotNull { (name, entry) ->
                filterEntry(entry)?.let { name to it }
            }.toMap(linkedMapOf())
            ObjectValue(members.mapValues { it.value.value }, members)
        }
        else -> value
    }

    private fun assertedAt(validTimes: List<ValidTime>): Boolean = validTimes.any { validTime ->
        if (
            validTime.timeline != requested.timeline &&
            validTime.timeline !in requestedTimeline.ancestorIds
        ) {
            return@any false
        }
        val requestedFrom = requested.from
        val requestedTo = requested.to
        val assertedFrom = validTime.from?.timecode
        val assertedTo = validTime.to?.timecode
        (requestedTo == null || assertedFrom == null || requestedTo >= assertedFrom) &&
            (assertedTo == null || requestedFrom == null || assertedTo >= requestedFrom)
    }
}

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
        "timecode" to (timeline.timecode?.let {
            jsonObject("type" to jsonString(it.type.name))
        } ?: JsonValue.Null),
        "mappings" to jsonArray(timeline.mappings.map(TimelineMapping::toJson)),
        "mappedOffsets" to JsonValue.Object(timeline.mappedOffsets.sortedByKey().mapValues { jsonNumber(it.value) }),
        "ancestors" to jsonArray(timeline.ancestorIds.sorted().map(::jsonString)),
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
            append("Timecode: ").append(item.timeline.timecode?.type?.name ?: "-").append('\n')
            append("Ancestors: ").append(item.timeline.ancestorIds.sorted().joinToString()).append('\n')
            append("Mappings: ").append(item.timeline.mappings.size).append('\n')
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
    append("NAME\tVALUE\tVALID_TIME\n")
    entries.sortedByKey().forEach { (name, values) ->
        values.forEach { entry ->
            if (ownerId != null && ownerVisibility != null) {
                append(ownerId).append('\t').append(ownerVisibility.wireName).append('\t')
            }
            append(name).append('\t').append(entry.value.toJson().encode()).append('\t')
            append(jsonArray(entry.validTime.map(ValidTime::toJson)).encode()).append('\n')
        }
    }
}

private fun renderRelations(relations: List<NormalizedRelation>, view: TemporalView? = null): String = buildString {
    append("TYPE\tFROM\tFROM_VISIBILITY\tTO\tTO_VISIBILITY\tLABEL\tSOURCE\n")
    relations.forEach { relation ->
        append(relation.type).append('\t')
            .append(relation.from).append('\t')
            .append((view?.visibility(relation.from) ?: Visibility.Full).wireName).append('\t')
            .append(relation.to).append('\t')
            .append((view?.visibility(relation.to) ?: Visibility.Full).wireName).append('\t')
            .append(relation.sourceLabel).append('\t').append(relation.source.path).append('\n')
    }
}
