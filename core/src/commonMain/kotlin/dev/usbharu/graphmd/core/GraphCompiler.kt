package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.math.abs

class GraphCompiler(
    private val options: CompileOptions = CompileOptions(),
) {
    private val extractor = BodySyntaxExtractor()
    private val documentParser = GraphDocumentParser()

    fun parseDocument(text: String, sourcePath: String): ParsedGraphDocumentResult =
        documentParser.parseDocument(text, sourcePath)

    fun compileParsed(parsedDocuments: List<ParsedGraphDocumentResult>): GraphCompilationResult {
        val parseDiagnostics = parsedDocuments.flatMap { it.diagnostics }
        val compiled = compile(parsedDocuments.mapNotNull { it.document })
        return compiled.copy(diagnostics = parseDiagnostics + compiled.diagnostics)
    }

    fun compileSources(documents: List<SourceDocument>): GraphCompilationResult =
        compileParsed(documents.map { parseDocument(it.text, it.sourcePath) })

    fun compile(documents: List<GraphDocument>): GraphCompilationResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val nodeDocs = documents.filterIsInstance<NodeDocument>()
        val nodeTypeDocs = documents.filterIsInstance<NodeTypeDocument>()
        val relTypeDocs = documents.filterIsInstance<RelTypeDocument>()
        val timelineDocs = documents.filterIsInstance<TimelineDocument>()

        diagnostics += checkUniqueIds(nodeDocs.map { it.id }, DocumentKind.Node)
        diagnostics += checkUniqueIds(nodeTypeDocs.map { it.id }, DocumentKind.NodeType)
        diagnostics += checkUniqueIds(relTypeDocs.map { it.id }, DocumentKind.RelType)
        diagnostics += checkUniqueIds(timelineDocs.map { it.id }, DocumentKind.Timeline)

        val timelines = resolveTimelineMappings(resolveTimelines(timelineDocs, diagnostics), timelineDocs, diagnostics)
        val nodeTypes = resolveNodeTypes(nodeTypeDocs, timelines, diagnostics)
        val relTypes = resolveRelTypes(relTypeDocs, timelines, nodeTypes, diagnostics)

        val timelineById = timelines.associateBy { it.id }
        val nodeTypeById = nodeTypes.associateBy { it.id }
        val relTypeById = relTypes.associateBy { it.id }
        val nodeDocById = nodeDocs.associateBy { it.id }

        val normalizedNodes = mutableListOf<NormalizedNode>()
        val relationSeeds = mutableListOf<NormalizedRelation>()

        for (document in nodeDocs) {
            diagnostics += validateNodeTopLevelFields(document)
            diagnostics += validateValidTimes(document.validTime, timelineById, document.sourcePath, document.id)
            val nodeSchema = nodeTypeById[document.type]
            if (nodeSchema == null) {
                diagnostics += referenceError("Unknown NodeType: ${document.type}", document.sourcePath, document.id)
            }
            val extraction = extractor.extract(document.body, document.sourcePath, document.id)
            diagnostics += extraction.diagnostics

            val mergedProps = LinkedHashMap<String, RawValue>()
            mergedProps.putAll(document.props)
            extraction.propsBlocks.forEach { block ->
                block.props.forEach { (name, value) ->
                    mergedProps[name] = mergePropertyRaw(mergedProps[name], value, nodeSchema?.props?.get(name))
                }
            }

            val propEntries = normalizePropEntries(
                ownerLabel = "Node ${document.id}",
                rawProps = mergedProps,
                schema = nodeSchema?.props.orEmpty(),
                inheritedValidTime = document.validTime,
                sourcePath = document.sourcePath,
                documentId = document.id,
                timelineById = timelineById,
                diagnostics = diagnostics,
            )
            val props = propEntries.mapValues { it.value.last().value }
            propEntries.values.flatten().forEach {
                diagnostics += validateValidTimes(it.validTime, timelineById, document.sourcePath, document.id)
                validateNestedValidTimes(it.value, timelineById, document.sourcePath, document.id, diagnostics)
            }
            normalizedNodes += NormalizedNode(
                id = document.id,
                type = document.type,
                props = props,
                kind = document.kind,
                url = document.url,
                validTime = document.validTime,
                propEntries = propEntries,
                source = SourceInfo(document.sourcePath),
            )

            extraction.relations.forEach { relation ->
                val relSchema = relTypeById[relation.relType]
                if (relSchema == null) {
                    diagnostics += referenceError("Unknown RelType: ${relation.relType}", document.sourcePath, document.id, relation.range)
                }
                if (nodeDocById[relation.target] == null) {
                    diagnostics += referenceError("Unknown Node target: ${relation.target}", document.sourcePath, document.id, relation.range)
                }
                val relationValidTime = relation.validTime.ifEmpty { document.validTime }
                val normalizedEntries = normalizePropEntries(
                    ownerLabel = "Relation ${document.id}->${relation.target}:${relation.relType}",
                    rawProps = relation.props,
                    schema = relSchema?.props.orEmpty(),
                    inheritedValidTime = relationValidTime,
                    sourcePath = document.sourcePath,
                    documentId = document.id,
                    timelineById = timelineById,
                    diagnostics = diagnostics,
                )
                val normalizedProps = normalizedEntries.mapValues { it.value.last().value }
                normalizedEntries.values.flatten().forEach {
                    diagnostics += validateValidTimes(it.validTime, timelineById, document.sourcePath, document.id)
                    validateNestedValidTimes(it.value, timelineById, document.sourcePath, document.id, diagnostics)
                }
                relationSeeds += NormalizedRelation(
                    from = document.id,
                    to = relation.target,
                    type = relation.relType,
                    props = normalizedProps,
                    sourceLabel = relation.label,
                    source = SourceInfo(document.sourcePath, document.id, relation.range),
                    validTime = relation.validTime.ifEmpty { document.validTime },
                    propEntries = normalizedEntries,
                    targetUrl = nodeDocById[relation.target]?.takeIf { it.kind == DocumentKind.Media }?.url,
                )
                diagnostics += validateValidTimes(
                    relation.validTime.ifEmpty { document.validTime },
                    timelineById,
                    document.sourcePath,
                    document.id,
                )
            }
        }

        val normalizedNodeById = normalizedNodes.associateBy { it.id }
        val validatedRelations = relationSeeds.mapNotNull { relation ->
            val relType = relTypeById[relation.type] ?: return@mapNotNull relation
            val fromNode = normalizedNodeById[relation.from]
            val toNode = normalizedNodeById[relation.to]
            if (fromNode != null && relType.from != null && !nodeTypeAllowed(fromNode.type, relType.from, nodeTypeById)) {
                diagnostics += constraintError("Relation source type ${fromNode.type} is not allowed for ${relation.type}", relation.source)
            }
            if (toNode != null && relType.to != null && !nodeTypeAllowed(toNode.type, relType.to, nodeTypeById)) {
                diagnostics += constraintError("Relation target type ${toNode.type} is not allowed for ${relation.type}", relation.source)
            }
            relation
        }

        return GraphCompilationResult(
            nodes = normalizedNodes,
            relations = validatedRelations,
            nodeTypes = nodeTypes,
            relTypes = relTypes,
            timelines = timelines,
            diagnostics = diagnostics,
        )
    }

    private fun checkUniqueIds(ids: List<String>, kind: DocumentKind): List<Diagnostic> {
        return ids.groupBy { it }.filterValues { it.size > 1 }.keys.map {
            Diagnostic(
                DiagnosticCategory.SchemaError,
                Severity.Error,
                "$kind id must be unique: $it",
            )
        }
    }

    private fun validateNodeTopLevelFields(document: NodeDocument): List<Diagnostic> {
        val reserved = setOf("name", "aliases", "tags", "lang", "meta")
        val diagnostics = mutableListOf<Diagnostic>()
        if (document.kind == DocumentKind.Media && document.url == null) {
            diagnostics += schemaError("Media requires url", document.sourcePath, document.id)
        }
        reserved.intersect(document.topLevelFields).forEach {
            diagnostics += Diagnostic(
                DiagnosticCategory.SchemaError,
                Severity.Error,
                "Node MUST NOT define top-level field: $it",
                SourceInfo(document.sourcePath, document.id),
            )
        }
        val allowed = setOf("id", "kind", "type", "url", "validTime", "props")
        (document.topLevelFields - allowed).forEach {
            diagnostics += Diagnostic(
                DiagnosticCategory.SchemaError,
                severityForUnknown(),
                "Unknown top-level field: $it",
                SourceInfo(document.sourcePath, document.id),
            )
        }
        return diagnostics
    }

    private fun validateValidTimes(
        validTimes: List<ValidTime>,
        timelineById: Map<String, NormalizedTimeline>,
        sourcePath: String,
        documentId: String,
    ): List<Diagnostic> = buildList {
        validTimes.forEach { validTime ->
            if (validTime.timeline !in timelineById) {
                add(referenceError("Unknown Timeline: ${validTime.timeline}", sourcePath, documentId))
            }
            val from = validTime.from?.timecode
            val to = validTime.to?.timecode
            if (from != null && to != null && from > to) {
                add(Diagnostic(
                    DiagnosticCategory.ConstraintError,
                    Severity.Warning,
                    "validTime.from is after validTime.to on ${validTime.timeline}",
                    SourceInfo(sourcePath, documentId),
                ))
            }
        }
    }

    private fun validateNestedValidTimes(
        value: NormalizedValue,
        timelineById: Map<String, NormalizedTimeline>,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ) {
        when (value) {
            is ArrayValue -> value.elements.forEach { element ->
                diagnostics += validateValidTimes(element.validTime, timelineById, sourcePath, documentId)
                validateNestedValidTimes(element.value, timelineById, sourcePath, documentId, diagnostics)
            }
            is ObjectValue -> value.members.values.forEach { member ->
                diagnostics += validateValidTimes(member.validTime, timelineById, sourcePath, documentId)
                validateNestedValidTimes(member.value, timelineById, sourcePath, documentId, diagnostics)
            }
            is TextValue -> value.memberEntries.values.forEach { member ->
                diagnostics += validateValidTimes(member.validTime, timelineById, sourcePath, documentId)
                validateNestedValidTimes(member.value, timelineById, sourcePath, documentId, diagnostics)
            }
            else -> Unit
        }
    }

    private fun resolveTimelines(docs: List<TimelineDocument>, diagnostics: MutableList<Diagnostic>): List<NormalizedTimeline> {
        val byId = docs.associateBy { it.id }
        val resolved = mutableMapOf<String, NormalizedTimeline>()
        val visiting = mutableSetOf<String>()

        fun resolve(id: String): NormalizedTimeline? {
            resolved[id]?.let { return it }
            val doc = byId[id] ?: return null
            if (!visiting.add(id)) {
                diagnostics += schemaError("Cyclic Timeline inheritance: $id", doc.sourcePath, id)
                return null
            }
            val parents = doc.extends.mapNotNull { parentId ->
                resolve(parentId) ?: run {
                    diagnostics += referenceError("Unknown parent Timeline: $parentId", doc.sourcePath, id)
                    null
                }
            }
            if (doc.mappings.isNotEmpty() && doc.timecode == null) {
                diagnostics += schemaError("Timeline with mappings requires timecode", doc.sourcePath, id)
            }
            validateTimelineExtends(doc, parents, diagnostics)
            val parentProps = linkedMapOf<String, NormalizedValue>()
            parents.forEach { parent ->
                parent.props.forEach { (key, value) -> if (key !in parentProps) parentProps[key] = value }
            }
            val timeline = NormalizedTimeline(
                id = doc.id,
                timecode = doc.timecode ?: parents.firstNotNullOfOrNull { it.timecode },
                mappings = doc.mappings,
                props = parentProps + doc.props.mapValues { normalizeSchemalessValue(it.value) },
                ancestorIds = parents.flatMap { it.ancestorIds + it.id }.toSet(),
                source = SourceInfo(doc.sourcePath),
            )
            visiting.remove(id)
            resolved[id] = timeline
            return timeline
        }

        docs.forEach { resolve(it.id) }
        return docs.mapNotNull { resolved[it.id] }
    }

    private fun resolveTimelineMappings(
        timelines: List<NormalizedTimeline>,
        docs: List<TimelineDocument>,
        diagnostics: MutableList<Diagnostic>,
    ): List<NormalizedTimeline> {
        data class Edge(val to: String, val offset: Double)
        val ids = timelines.map { it.id }.toSet()
        val edges = ids.associateWith { mutableListOf<Edge>() }.toMutableMap()

        fun connect(from: String, to: String, offset: Double, sourcePath: String) {
            if (to !in ids) {
                diagnostics += referenceError("Unknown mapped Timeline: $to", sourcePath, from)
                return
            }
            edges.getValue(from) += Edge(to, offset)
            edges.getValue(to) += Edge(from, -offset)
        }

        docs.forEach { doc ->
            doc.extends.forEach { parent -> if (parent in ids) connect(doc.id, parent, 0.0, doc.sourcePath) }
            doc.mappings.forEach { mapping ->
                if (mapping is OffsetTimelineMapping) {
                    when {
                        (mapping.to == null) == (mapping.from == null) ->
                            diagnostics += schemaError("offset mapping requires exactly one of from or to", doc.sourcePath, doc.id)
                        !mapping.offset.isFinite() ->
                            diagnostics += schemaError("mapping.offset MUST be finite", doc.sourcePath, doc.id)
                        mapping.to != null -> connect(doc.id, mapping.to, mapping.offset, doc.sourcePath)
                        mapping.from != null -> connect(mapping.from, doc.id, mapping.offset, doc.sourcePath)
                    }
                }
            }
        }

        return timelines.map { timeline ->
            val offsets = linkedMapOf(timeline.id to 0.0)
            val queue = ArrayDeque<String>()
            queue.addLast(timeline.id)
            val reported = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val currentOffset = offsets.getValue(current)
                edges.getValue(current).forEach { edge ->
                    val candidate = currentOffset + edge.offset
                    val existing = offsets[edge.to]
                    if (existing == null) {
                        offsets[edge.to] = candidate
                        queue.addLast(edge.to)
                    } else if (abs(existing - candidate) > 1e-9 && reported.add(edge.to)) {
                        diagnostics += Diagnostic(
                            DiagnosticCategory.ConstraintError,
                            Severity.Warning,
                            "Inconsistent offset mapping from ${timeline.id} to ${edge.to}: $existing != $candidate",
                            timeline.source,
                        )
                    }
                }
            }
            timeline.copy(mappedOffsets = offsets - timeline.id)
        }
    }

    private fun validateTimelineExtends(
        doc: TimelineDocument,
        parents: List<NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
    ) {
        if (parents.isEmpty()) return
        parents.zipWithNext().forEach { (left, right) ->
            if (left.timecode != right.timecode) {
                diagnostics += schemaError(
                    "Timeline extends must stay on the same time axis; parent timelines are incompatible for ${doc.id}",
                    doc.sourcePath,
                    doc.id,
                )
            }
        }
        parents.forEach { parent ->
            if (doc.timecode != null && parent.timecode != null && doc.timecode != parent.timecode) {
                diagnostics += schemaError("Timeline extends cannot change timecode schema; use mapping for ${doc.id}", doc.sourcePath, doc.id)
            }
        }
    }

    private fun resolveNodeTypes(
        docs: List<NodeTypeDocument>,
        timelines: List<NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
    ): List<NormalizedNodeType> {
        val byId = docs.associateBy { it.id }
        val timelineById = timelines.associateBy { it.id }
        val resolved = mutableMapOf<String, NormalizedNodeType>()
        val visiting = mutableSetOf<String>()

        fun resolve(id: String): NormalizedNodeType? {
            resolved[id]?.let { return it }
            val doc = byId[id] ?: return null
            if (!visiting.add(id)) {
                diagnostics += schemaError("Cyclic NodeType inheritance: $id", doc.sourcePath, id)
                return null
            }
            val props = linkedMapOf<String, ResolvedPropSchema>()
            val parents = mutableListOf<NormalizedNodeType>()
            doc.extends.forEach { parentId ->
                val parent = resolve(parentId)
                if (parent == null) {
                    diagnostics += referenceError("Unknown parent NodeType: $parentId", doc.sourcePath, id)
                } else {
                    parents += parent
                    parent.props.forEach { (name, schema) ->
                        val existing = props[name]
                        if (existing != null && existing.type != schema.type) {
                            diagnostics += schemaWarning("Incompatible inherited prop schemas for $name", doc.sourcePath, id)
                        } else {
                            props[name] = existing?.copy(required = existing.required && schema.required) ?: schema
                        }
                    }
                }
            }
            doc.props.forEach { (name, schema) ->
                val parent = props[name]
                val resolvedSchema = resolveSchema(schema, timelineById, diagnostics, doc.sourcePath, id, name)
                if (parent != null && !isCompatibleRefinement(parent, resolvedSchema, timelineById)) {
                    diagnostics += schemaError("Invalid refinement for prop $name", doc.sourcePath, id)
                }
                props[name] = parent?.copy(required = parent.required && resolvedSchema.required) ?: resolvedSchema
            }
            visiting.remove(id)
            return NormalizedNodeType(
                doc.id,
                props,
                parents.flatMap { it.ancestorIds + it.id }.toSet(),
                SourceInfo(doc.sourcePath),
            ).also { resolved[id] = it }
        }

        docs.forEach { resolve(it.id) }
        return docs.mapNotNull { resolved[it.id] }
    }

    private fun resolveRelTypes(
        docs: List<RelTypeDocument>,
        timelines: List<NormalizedTimeline>,
        nodeTypes: List<NormalizedNodeType>,
        diagnostics: MutableList<Diagnostic>,
    ): List<NormalizedRelType> {
        val byId = docs.associateBy { it.id }
        val timelineById = timelines.associateBy { it.id }
        val nodeTypeById = nodeTypes.associateBy { it.id }
        val resolved = mutableMapOf<String, NormalizedRelType>()
        val visiting = mutableSetOf<String>()

        fun resolve(id: String): NormalizedRelType? {
            resolved[id]?.let { return it }
            val doc = byId[id] ?: return null
            if (!visiting.add(id)) {
                diagnostics += schemaError("Cyclic RelType inheritance: $id", doc.sourcePath, id)
                return null
            }
            val inheritedProps = linkedMapOf<String, ResolvedPropSchema>()
            var inheritedFrom: List<String>? = null
            var inheritedTo: List<String>? = null
            doc.extends.forEach { parentId ->
                val parent = resolve(parentId)
                if (parent == null) {
                    diagnostics += referenceError("Unknown parent RelType: $parentId", doc.sourcePath, id)
                } else {
                    parent.props.forEach { (name, schema) ->
                        val existing = inheritedProps[name]
                        if (existing != null && !isCompatibleRefinement(existing, schema, timelineById)) {
                            diagnostics += schemaWarning("Incompatible inherited prop schemas for $name", doc.sourcePath, id)
                        }
                        inheritedProps[name] = existing?.copy(required = existing.required && schema.required) ?: schema
                    }
                    val previousFrom = inheritedFrom
                    val previousTo = inheritedTo
                    inheritedFrom = intersectConstraints(previousFrom, parent.from, nodeTypeById)
                    inheritedTo = intersectConstraints(previousTo, parent.to, nodeTypeById)
                    if (previousFrom != null && parent.from != null && inheritedFrom.isNullOrEmpty()) {
                        diagnostics += schemaWarning("Inherited from constraints have an empty intersection", doc.sourcePath, id)
                    }
                    if (previousTo != null && parent.to != null && inheritedTo.isNullOrEmpty()) {
                        diagnostics += schemaWarning("Inherited to constraints have an empty intersection", doc.sourcePath, id)
                    }
                }
            }
            doc.props.forEach { (name, schema) ->
                val resolvedSchema = resolveSchema(schema, timelineById, diagnostics, doc.sourcePath, id, name)
                val parent = inheritedProps[name]
                if (parent != null && !isCompatibleRefinement(parent, resolvedSchema, timelineById)) {
                    diagnostics += schemaError("Invalid refinement for prop $name", doc.sourcePath, id)
                }
                inheritedProps[name] = parent?.copy(required = parent.required && resolvedSchema.required) ?: resolvedSchema
            }
            val finalFrom = combineConstraint(inheritedFrom, doc.from, nodeTypeById, diagnostics, doc.sourcePath, id, "from")
            val finalTo = combineConstraint(inheritedTo, doc.to, nodeTypeById, diagnostics, doc.sourcePath, id, "to")
            visiting.remove(id)
            return NormalizedRelType(doc.id, finalFrom, finalTo, inheritedProps, SourceInfo(doc.sourcePath)).also { resolved[id] = it }
        }

        docs.forEach { resolve(it.id) }
        return docs.mapNotNull { resolved[it.id] }
    }

    private fun narrowConstraint(current: List<String>?, next: List<String>?): List<String>? {
        return current?.let { currentValue ->
            next?.let { nextValue ->
                currentValue.intersect(nextValue.toSet()).toList()
            } ?: currentValue
        } ?: next
    }

    private fun combineConstraint(
        inherited: List<String>?,
        child: List<String>?,
        nodeTypeById: Map<String, NormalizedNodeType>,
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        field: String,
    ): List<String>? {
        if (child == null) return inherited
        if (inherited == null) return child
        val intersection = intersectConstraints(inherited, child, nodeTypeById).orEmpty()
        if (intersection.isEmpty()) diagnostics += schemaWarning("Inherited and child $field constraints have an empty intersection", sourcePath, documentId)
        return intersection
    }

    private fun intersectConstraints(
        left: List<String>?,
        right: List<String>?,
        nodeTypeById: Map<String, NormalizedNodeType>,
    ): List<String>? {
        if (left == null) return right
        if (right == null) return left
        val result = linkedSetOf<String>()
        left.forEach { leftId ->
            right.forEach { rightId ->
                when {
                    leftId == rightId -> result += leftId
                    nodeTypeById[leftId]?.ancestorIds?.contains(rightId) == true -> result += leftId
                    nodeTypeById[rightId]?.ancestorIds?.contains(leftId) == true -> result += rightId
                }
            }
        }
        return result.toList()
    }

    private fun resolveSchema(
        schema: PropSchema,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        propName: String,
    ): ResolvedPropSchema {
        if (schema.timeline != null && schema.timelines != null) {
            diagnostics += schemaError("timeline and timelines MUST NOT be used together for $propName", sourcePath, documentId)
        }
        fun checkSelector(selector: TimelineSelector) {
            when (selector) {
                is TimelineSelector.Id -> if (selector.id !in timelineById) {
                    diagnostics += referenceError("Unknown Timeline: ${selector.id}", sourcePath, documentId)
                }
                is TimelineSelector.Mapped -> if (selector.to !in timelineById) {
                    diagnostics += referenceError("Unknown Timeline: ${selector.to}", sourcePath, documentId)
                }
            }
        }
        schema.timeline?.let(::checkSelector)
        schema.timelines.orEmpty().forEach(::checkSelector)
        return ResolvedPropSchema(
            type = schema.type,
            required = schema.required,
            index = schema.index,
            timeline = schema.timeline,
            timelines = schema.timelines,
            items = schema.items?.let { resolveSchema(it, timelineById, diagnostics, sourcePath, documentId, "$propName[]") },
        )
    }

    private fun isCompatibleRefinement(
        parent: ResolvedPropSchema,
        child: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
    ): Boolean = parent.type == child.type && timelineSelectorsCompatible(parent, child, timelineById)

    private fun timelineSelectorsCompatible(
        parent: ResolvedPropSchema,
        child: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
    ): Boolean {
        val parentSelectors = parent.timelines ?: parent.timeline?.let(::listOf) ?: return true
        val childSelectors = child.timelines ?: child.timeline?.let(::listOf) ?: return true
        return childSelectors.all { childSelector ->
            parentSelectors.any { parentSelector ->
                when (parentSelector) {
                    is TimelineSelector.Id -> when (childSelector) {
                        is TimelineSelector.Id -> childSelector.id == parentSelector.id ||
                            timelineById[childSelector.id]?.ancestorIds?.contains(parentSelector.id) == true
                        is TimelineSelector.Mapped -> false
                    }
                    is TimelineSelector.Mapped -> when (childSelector) {
                        is TimelineSelector.Id -> childSelector.id == parentSelector.to ||
                            timelineById[childSelector.id]?.ancestorIds?.contains(parentSelector.to) == true ||
                            timelineById[childSelector.id]?.mappedOffsets?.containsKey(parentSelector.to) == true
                        is TimelineSelector.Mapped -> childSelector.to == parentSelector.to
                    }
                }
            }
        }
    }

    private fun normalizePropEntries(
        ownerLabel: String,
        rawProps: Map<String, RawValue>,
        schema: Map<String, ResolvedPropSchema>,
        inheritedValidTime: List<ValidTime>,
        sourcePath: String,
        documentId: String,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, List<NormalizedPropEntry>> {
        val result = linkedMapOf<String, List<NormalizedPropEntry>>()
        rawProps.forEach { (key, rawValue) ->
            val propSchema = schema[key]
            if (propSchema == null && options.emitUnknownPropertyWarnings) {
                diagnostics += Diagnostic(
                    DiagnosticCategory.TypeError,
                    severityForUnknown(),
                    "Unknown property $key on $ownerLabel",
                    SourceInfo(sourcePath, documentId),
                )
            }
            val candidates = propertyEntryCandidates(rawValue, propSchema, inheritedValidTime, diagnostics, sourcePath, documentId, key)
            val normalized = candidates.mapNotNull { candidate ->
                val value = if (propSchema != null) {
                    normalizeValue(candidate.first, propSchema, sourcePath, documentId, timelineById, diagnostics, key, candidate.second)
                } else {
                    normalizeSchemalessTimed(candidate.first, candidate.second, sourcePath, documentId, key, diagnostics)
                }
                value?.let { NormalizedPropEntry(it, candidate.second) }
            }
            if (normalized.isNotEmpty()) result[key] = normalized
        }
        schema.forEach { (key, propSchema) ->
            if (key !in result) {
                when {
                    propSchema.required -> diagnostics += constraintError(
                        "Required property missing after normalization: $key",
                        SourceInfo(sourcePath, documentId),
                    )
                }
            }
        }
        return result
    }

    private fun mergePropertyRaw(
        existing: RawValue?,
        incoming: RawValue,
        schema: ResolvedPropSchema?,
    ): RawValue {
        if (existing == null || schema?.type == PropType.array) return incoming
        fun entries(value: RawValue): List<RawValue> =
            (value as? RawArray)?.values?.takeIf { values -> values.all { it is RawObject && "value" in it.values } }
                ?: listOf(value)
        fun signature(value: RawValue): String {
            val obj = value as? RawObject
            val validTime = obj?.values?.get("validTime") ?: return "<fallback>"
            val entries = (validTime as? RawArray)?.values ?: return rawValueToJsonString(validTime)
            return entries.map { entry ->
                val time = entry as? RawObject ?: return@map rawValueToJsonString(entry)
                fun point(name: String): String {
                    val point = time.values[name] as? RawObject ?: return ""
                    return listOf("value", "timecode").joinToString(";") { key ->
                        point.values[key]?.let(::rawValueToJsonString) ?: ""
                    }
                }
                "${(time.values["timeline"] as? RawString)?.value}|${point("from")}|${point("to")}"
            }.sorted().joinToString("||")
        }
        val merged = entries(existing).toMutableList()
        entries(incoming).forEach { candidate ->
            val index = merged.indexOfFirst { signature(it) == signature(candidate) }
            if (index >= 0) merged[index] = candidate else merged += candidate
        }
        return if (merged.size == 1 && signature(merged.single()) == "<fallback>") merged.single() else RawArray(merged)
    }

    private fun propertyEntryCandidates(
        rawValue: RawValue,
        schema: ResolvedPropSchema?,
        inheritedValidTime: List<ValidTime>,
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        propName: String,
    ): List<Pair<RawValue, List<ValidTime>>> {
        if (rawValue !is RawArray || schema?.type == PropType.array) return listOf(rawValue to inheritedValidTime)
        val explicitEntries = rawValue.values.all { value -> value is RawObject && "value" in value.values }
        if (explicitEntries && rawValue.values.count { value ->
                value is RawObject && "validTime" !in value.values
            } > 1
        ) {
            diagnostics += typeError("$propName Property entries may contain at most one entry without validTime", sourcePath, documentId)
        }
        if (schema == null && !explicitEntries) {
            diagnostics += Diagnostic(
                DiagnosticCategory.TypeError,
                Severity.Warning,
                "$propName array is ambiguous without a PropSchema; interpreting it as Property time variants",
                SourceInfo(sourcePath, documentId),
            )
        }
        if (schema != null && !explicitEntries) return listOf(rawValue to inheritedValidTime)
        return rawValue.values.map { entry ->
            val obj = entry as? RawObject
            if (obj == null || "value" !in obj.values) return@map entry to inheritedValidTime
            val unknown = obj.values.keys - setOf("value", "validTime")
            if (unknown.isNotEmpty()) {
                diagnostics += typeError("$propName entry has unknown fields: ${unknown.joinToString()}", sourcePath, documentId)
            }
            val validTime = obj.values["validTime"]?.let {
                parseRawValidTimes(it, "$propName.validTime", sourcePath, documentId, diagnostics)
            } ?: inheritedValidTime
            obj.values.getValue("value") to validTime
        }
    }

    private fun parseRawValidTimes(
        raw: RawValue,
        field: String,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): List<ValidTime> {
        val values = (raw as? RawArray)?.values ?: run {
            diagnostics += typeError("$field must be a non-empty array", sourcePath, documentId)
            return emptyList()
        }
        if (values.isEmpty()) diagnostics += typeError("$field must be a non-empty array", sourcePath, documentId)
        return values.mapNotNull { value ->
            val obj = value as? RawObject ?: run {
                diagnostics += typeError("$field entries must be objects", sourcePath, documentId)
                return@mapNotNull null
            }
            val timeline = (obj.values["timeline"] as? RawString)?.value ?: run {
                diagnostics += typeError("$field.timeline must be string", sourcePath, documentId)
                return@mapNotNull null
            }
            if (timeline.isEmpty()) diagnostics += typeError("$field.timeline must be non-empty", sourcePath, documentId)
            val unknown = obj.values.keys - setOf("timeline", "from", "to")
            if (unknown.isNotEmpty()) diagnostics += typeError("$field has unknown fields: ${unknown.joinToString()}", sourcePath, documentId)
            ValidTime(
                timeline,
                parseRawTimePoint(obj.values["from"], "$field.from", sourcePath, documentId, diagnostics),
                parseRawTimePoint(obj.values["to"], "$field.to", sourcePath, documentId, diagnostics),
            )
        }
    }

    private fun parseRawTimePoint(
        raw: RawValue?,
        field: String,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): TimePoint? {
        if (raw == null) return null
        val obj = raw as? RawObject ?: run {
            diagnostics += typeError("$field must be an object", sourcePath, documentId)
            return null
        }
        val timecode = when (val value = obj.values["timecode"]) {
            is RawInteger -> value.value.toDouble()
            is RawNumber -> value.value
            else -> {
                diagnostics += typeError("$field.timecode must be number", sourcePath, documentId)
                return null
            }
        }
        val unknown = obj.values.keys - setOf("value", "timecode")
        if (unknown.isNotEmpty()) diagnostics += typeError("$field has unknown fields: ${unknown.joinToString()}", sourcePath, documentId)
        if (!timecode.isFinite()) {
            diagnostics += typeError("$field.timecode must be finite", sourcePath, documentId)
            return null
        }
        return TimePoint(timecode, (obj.values["value"] as? RawString)?.value)
    }

    private fun normalizeValue(
        rawValue: RawValue,
        schema: ResolvedPropSchema,
        sourcePath: String,
        documentId: String,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
        propName: String,
        inheritedValidTime: List<ValidTime> = emptyList(),
    ): NormalizedValue? {
        fun fail(message: String): NormalizedValue? {
            diagnostics += typeError(message, sourcePath, documentId)
            return null
        }

        return when (schema.type) {
            PropType.string -> (rawValue as? RawString)?.let { StringValue(it.value) } ?: fail("$propName must be string")
            PropType.text -> when (rawValue) {
                is RawString -> TextValue(mapOf("default" to NormalizedPropEntry(StringValue(rawValue.value), inheritedValidTime)))
                is RawObject -> {
                    val textMap = rawValue.values.mapValues { (key, value) ->
                        normalizeSchemalessEntry(value, inheritedValidTime, sourcePath, documentId, "$propName.$key", diagnostics)
                    }
                    TextValue(textMap)
                }
                else -> fail("$propName must be text")
            }
            PropType.number -> when (rawValue) {
                is RawNumber -> NumberValue(rawValue.value)
                is RawInteger -> NumberValue(rawValue.value.toDouble())
                else -> fail("$propName must be number")
            }
            PropType.array -> {
                val array = rawValue as? RawArray ?: return fail("$propName must be array")
                val elements = array.values.mapNotNull { rawElement ->
                    val entry = rawElement as? RawObject
                    val isTimedEntry = entry != null && "value" in entry.values && entry.values.keys.all { it in setOf("value", "validTime") }
                    val elementRaw = if (isTimedEntry) entry.values.getValue("value") else rawElement
                    val elementValidTime = if (isTimedEntry) {
                        entry.values["validTime"]?.let {
                            parseRawValidTimes(it, "$propName[].validTime", sourcePath, documentId, diagnostics)
                        } ?: inheritedValidTime
                    } else inheritedValidTime
                    val normalized = schema.items?.let {
                        normalizeValue(elementRaw, it, sourcePath, documentId, timelineById, diagnostics, "$propName[]", elementValidTime)
                    } ?: normalizeSchemalessTimed(elementRaw, elementValidTime, sourcePath, documentId, "$propName[]", diagnostics)
                    NormalizedArrayElement(normalized, elementValidTime)
                }
                ArrayValue(elements.map { it.value }, elements)
            }
            PropType.instant -> normalizeInstant(rawValue, schema, timelineById, diagnostics, sourcePath, documentId, propName)
            PropType.duration -> normalizeDuration(rawValue, schema, timelineById, diagnostics, sourcePath, documentId, propName)
        }
    }

    private fun normalizeInstant(
        rawValue: RawValue,
        schema: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        propName: String,
    ): NormalizedValue? {
        val obj = rawValue as? RawObject
        val timeline = (obj?.values?.get("timeline") as? RawString)?.value
        if (timeline != null && timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (timeline != null && !timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        val timecode = parseNumberTimecode(
            obj?.values?.get("timecode") ?: rawValue.takeIf { it is RawInteger || it is RawNumber },
            "$propName.timecode",
            sourcePath,
            documentId,
            diagnostics,
        ) ?: return null
        val value = (obj?.values?.get("value") as? RawString)?.value
        val unknown = obj?.values?.keys.orEmpty() - setOf("timeline", "value", "timecode")
        if (unknown.isNotEmpty()) diagnostics += typeError("$propName instant has unknown fields: ${unknown.joinToString()}", sourcePath, documentId)
        return InstantValue(timeline = timeline, value = value, timecode = NumberTimecode(timecode))
    }

    private fun normalizeDuration(
        rawValue: RawValue,
        schema: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        propName: String,
    ): NormalizedValue? {
        val obj = rawValue as? RawObject
        if (obj == null) {
            diagnostics += typeError("$propName must be duration object", sourcePath, documentId)
            return null
        }
        val timeline = (obj.values["timeline"] as? RawString)?.value
        if (timeline != null && timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (timeline != null && !timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        val from = normalizeTemporalPoint(obj.values["from"], "$propName.from", timelineById, sourcePath, documentId, diagnostics)
        val to = normalizeTemporalPoint(obj.values["to"], "$propName.to", timelineById, sourcePath, documentId, diagnostics)
        if (from == null && to == null) {
            diagnostics += constraintError("$propName duration must define from or to", SourceInfo(sourcePath, documentId))
            return null
        }
        listOfNotNull(from?.timeline, to?.timeline).forEach { endpointTimeline ->
            if (timeline != null && !timelinesMapped(timeline, endpointTimeline, timelineById)) {
                diagnostics += constraintError(
                    "$propName duration timeline $timeline is not mapped to endpoint timeline $endpointTimeline",
                    SourceInfo(sourcePath, documentId),
                )
            }
        }
        val unknown = obj.values.keys - setOf("timeline", "from", "to")
        if (unknown.isNotEmpty()) diagnostics += typeError("$propName duration has unknown fields: ${unknown.joinToString()}", sourcePath, documentId)
        return DurationValue(timeline, from, to)
    }

    private fun normalizeTemporalPoint(
        raw: RawValue?,
        field: String,
        timelineById: Map<String, NormalizedTimeline>,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): TemporalPoint? {
        if (raw == null) return null
        if (raw is RawInteger) return TemporalPoint(raw.value.toDouble())
        if (raw is RawNumber) return TemporalPoint(raw.value)
        val obj = raw as? RawObject ?: run {
            diagnostics += typeError("$field must be a number or timePoint object", sourcePath, documentId)
            return null
        }
        val timecode = parseNumberTimecode(obj.values["timecode"], "$field.timecode", sourcePath, documentId, diagnostics) ?: return null
        val timeline = (obj.values["timeline"] as? RawString)?.value
        if (timeline != null && timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        val unknown = obj.values.keys - setOf("timeline", "value", "timecode")
        if (unknown.isNotEmpty()) diagnostics += typeError("$field has unknown fields: ${unknown.joinToString()}", sourcePath, documentId)
        return TemporalPoint(timecode, (obj.values["value"] as? RawString)?.value, timeline)
    }

    private fun parseNumberTimecode(
        raw: RawValue?,
        field: String,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Double? = when (raw) {
        is RawInteger -> raw.value.toDouble()
        is RawNumber -> raw.value
        else -> {
            diagnostics += typeError("$field must be number", sourcePath, documentId)
            null
        }
    }?.takeIf { it.isFinite() } ?: run {
        if (raw is RawNumber && !raw.value.isFinite()) diagnostics += typeError("$field must be finite", sourcePath, documentId)
        null
    }

    private fun timelinesMapped(
        left: String,
        right: String,
        timelineById: Map<String, NormalizedTimeline>,
    ): Boolean = left == right || timelineById[left]?.mappedOffsets?.containsKey(right) == true

    private fun timelineAllowed(
        timeline: String,
        schema: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
    ): Boolean {
        fun TimelineSelector.matches(): Boolean = when (this) {
            is TimelineSelector.Id -> id == timeline ||
                timelineById[timeline]?.ancestorIds?.contains(id) == true
            is TimelineSelector.Mapped -> to == timeline ||
                timelineById[timeline]?.ancestorIds?.contains(to) == true ||
                timelineById[timeline]?.mappedOffsets?.containsKey(to) == true
        }
        val selectors = schema.timelines ?: schema.timeline?.let(::listOf)
        return selectors.isNullOrEmpty() || selectors.any { it.matches() }
    }

    private fun mappingTarget(mapping: TimelineMapping): String? =
        (mapping as OffsetTimelineMapping).to ?: mapping.from

    private fun nodeTypeAllowed(
        type: String,
        allowed: List<String>,
        nodeTypeById: Map<String, NormalizedNodeType>,
    ): Boolean {
        if (type in allowed) return true
        val ancestors = nodeTypeById[type]?.ancestorIds ?: return false
        return ancestors.any { it in allowed }
    }

    private fun normalizeSchemalessValue(rawValue: RawValue): NormalizedValue = when (rawValue) {
        is RawString -> StringValue(rawValue.value)
        is RawInteger -> IntegerValue(rawValue.value)
        is RawNumber -> NumberValue(rawValue.value)
        is RawBoolean -> BooleanValue(rawValue.value)
        RawNull -> NullValue
        is RawArray -> ArrayValue(rawValue.values.map { normalizeSchemalessValue(it) })
        is RawObject -> ObjectValue(rawValue.values.mapValues { normalizeSchemalessValue(it.value) })
    }

    private fun normalizeSchemalessTimed(
        rawValue: RawValue,
        inheritedValidTime: List<ValidTime>,
        sourcePath: String,
        documentId: String,
        field: String,
        diagnostics: MutableList<Diagnostic>,
    ): NormalizedValue = when (rawValue) {
        is RawArray -> {
            val entries = rawValue.values.map { value ->
                normalizeSchemalessEntry(value, inheritedValidTime, sourcePath, documentId, "$field[]", diagnostics)
            }
            ArrayValue(entries.map { it.value }, entries.map { NormalizedArrayElement(it.value, it.validTime) })
        }
        is RawObject -> {
            val members = rawValue.values.mapValues { (key, value) ->
                normalizeSchemalessEntry(value, inheritedValidTime, sourcePath, documentId, "$field.$key", diagnostics)
            }
            ObjectValue(members.mapValues { it.value.value }, members)
        }
        else -> normalizeSchemalessValue(rawValue)
    }

    private fun normalizeSchemalessEntry(
        rawValue: RawValue,
        inheritedValidTime: List<ValidTime>,
        sourcePath: String,
        documentId: String,
        field: String,
        diagnostics: MutableList<Diagnostic>,
    ): NormalizedPropEntry {
        val wrapper = rawValue as? RawObject
        val isEntry = wrapper != null && "value" in wrapper.values && wrapper.values.keys.all { it in setOf("value", "validTime") }
        if (!isEntry) {
            return NormalizedPropEntry(
                normalizeSchemalessTimed(rawValue, inheritedValidTime, sourcePath, documentId, field, diagnostics),
                inheritedValidTime,
            )
        }
        val validTime = wrapper.values["validTime"]?.let {
            parseRawValidTimes(it, "$field.validTime", sourcePath, documentId, diagnostics)
        } ?: inheritedValidTime
        return NormalizedPropEntry(
            normalizeSchemalessTimed(wrapper.values.getValue("value"), validTime, sourcePath, documentId, field, diagnostics),
            validTime,
        )
    }


    private fun schemaError(message: String, sourcePath: String, documentId: String): Diagnostic =
        Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun schemaWarning(message: String, sourcePath: String, documentId: String): Diagnostic =
        Diagnostic(DiagnosticCategory.SchemaError, Severity.Warning, message, SourceInfo(sourcePath, documentId))

    private fun referenceError(message: String, sourcePath: String, documentId: String, range: SourceRange? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.ReferenceError, Severity.Error, message, SourceInfo(sourcePath, documentId, range))

    private fun typeError(message: String, sourcePath: String, documentId: String): Diagnostic =
        Diagnostic(DiagnosticCategory.TypeError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun constraintError(message: String, source: SourceInfo): Diagnostic =
        Diagnostic(DiagnosticCategory.ConstraintError, Severity.Error, message, source)

    private fun severityForUnknown(): Severity =
        if (options.mode == ValidationMode.Strict) Severity.Error else Severity.Warning
}
