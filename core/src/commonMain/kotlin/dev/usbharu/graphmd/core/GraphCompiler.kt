package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

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

        val timelines = resolveTimelines(timelineDocs, diagnostics)
        val nodeTypes = resolveNodeTypes(nodeTypeDocs, timelines, diagnostics)
        val relTypes = resolveRelTypes(relTypeDocs, timelines, diagnostics)

        val timelineById = timelines.associateBy { it.id }
        val nodeTypeById = nodeTypes.associateBy { it.id }
        val relTypeById = relTypes.associateBy { it.id }
        val nodeDocById = nodeDocs.associateBy { it.id }

        val normalizedNodes = mutableListOf<NormalizedNode>()
        val relationSeeds = mutableListOf<NormalizedRelation>()

        for (document in nodeDocs) {
            diagnostics += validateNodeTopLevelFields(document)
            val nodeSchema = nodeTypeById[document.type]
            if (nodeSchema == null) {
                diagnostics += referenceError("Unknown NodeType: ${document.type}", document.sourcePath, document.id)
            }
            val extraction = extractor.extract(document.body, document.sourcePath, document.id)
            diagnostics += extraction.diagnostics

            val mergedProps = LinkedHashMap<String, RawValue>()
            mergedProps.putAll(document.props)
            extraction.propsBlocks.forEach { mergedProps.putAll(it.props) }

            val props = normalizeProps(
                ownerLabel = "Node ${document.id}",
                rawProps = mergedProps,
                schema = nodeSchema?.props.orEmpty(),
                sourcePath = document.sourcePath,
                documentId = document.id,
                timelineById = timelineById,
                diagnostics = diagnostics,
            )
            normalizedNodes += NormalizedNode(
                id = document.id,
                type = document.type,
                props = props,
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
                val normalizedProps = normalizeProps(
                    ownerLabel = "Relation ${document.id}->${relation.target}:${relation.relType}",
                    rawProps = relation.props,
                    schema = relSchema?.props.orEmpty(),
                    sourcePath = document.sourcePath,
                    documentId = document.id,
                    timelineById = timelineById,
                    diagnostics = diagnostics,
                )
                relationSeeds += NormalizedRelation(
                    from = document.id,
                    to = relation.target,
                    type = relation.relType,
                    props = normalizedProps,
                    sourceLabel = relation.label,
                    source = SourceInfo(document.sourcePath, document.id, relation.range),
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
        reserved.intersect(document.topLevelFields).forEach {
            diagnostics += Diagnostic(
                DiagnosticCategory.SchemaError,
                Severity.Error,
                "Node MUST NOT define top-level field: $it",
                SourceInfo(document.sourcePath, document.id),
            )
        }
        val allowed = setOf("id", "kind", "type", "props")
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
            if (doc.timecode?.type == TimecodeType.tuple && doc.timecode.direction != null) {
                diagnostics += schemaError("timecode.direction is only valid for timecode.type: number", doc.sourcePath, id)
            }
            validateTimelineExtends(doc, parents, diagnostics)
            val parent = parents.lastOrNull()
            val timeline = NormalizedTimeline(
                id = doc.id,
                timecode = doc.timecode ?: parent?.timecode,
                mappings = if (doc.mappings.isNotEmpty()) doc.mappings else parent?.mappings.orEmpty(),
                props = parent?.props.orEmpty() + doc.props.mapValues { normalizeSchemalessValue(it.value) },
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
                            diagnostics += schemaError("Incompatible inherited prop schemas for $name", doc.sourcePath, id)
                        } else {
                            if (!props.containsKey(name)) {
                                props[name] = schema
                            }
                        }
                    }
                }
            }
            doc.props.forEach { (name, schema) ->
                val parent = props[name]
                val resolvedSchema = resolveSchema(schema, timelineById, diagnostics, doc.sourcePath, id, name)
                if (parent != null && !isCompatibleRefinement(parent, resolvedSchema)) {
                    diagnostics += schemaError("Invalid refinement for prop $name", doc.sourcePath, id)
                }
                props[name] = resolvedSchema
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
        diagnostics: MutableList<Diagnostic>,
    ): List<NormalizedRelType> {
        val byId = docs.associateBy { it.id }
        val timelineById = timelines.associateBy { it.id }
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
                    parent.props.forEach { (name, schema) -> if (!inheritedProps.containsKey(name)) inheritedProps[name] = schema }
                    inheritedFrom = narrowConstraint(inheritedFrom, parent.from)
                    inheritedTo = narrowConstraint(inheritedTo, parent.to)
                }
            }
            doc.props.forEach { (name, schema) ->
                val resolvedSchema = resolveSchema(schema, timelineById, diagnostics, doc.sourcePath, id, name)
                val parent = inheritedProps[name]
                if (parent != null && !isCompatibleRefinement(parent, resolvedSchema)) {
                    diagnostics += schemaError("Invalid refinement for prop $name", doc.sourcePath, id)
                }
                inheritedProps[name] = resolvedSchema
            }
            val finalFrom = combineConstraint(inheritedFrom, doc.from, diagnostics, doc.sourcePath, id, "from")
            val finalTo = combineConstraint(inheritedTo, doc.to, diagnostics, doc.sourcePath, id, "to")
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
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        field: String,
    ): List<String>? {
        if (child == null) return inherited
        if (inherited == null) return child
        val childSet = child.toSet()
        val inheritedSet = inherited.toSet()
        if (!childSet.all { it in inheritedSet }) {
            diagnostics += schemaError("Child $field constraint must be equal to or narrower than inherited constraint", sourcePath, documentId)
        }
        return child
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
                TimelineSelector.Any -> {}
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
        val resolvedWithoutDefault = ResolvedPropSchema(
            type = schema.type,
            required = schema.required,
            default = null,
            index = schema.index ?: defaultIndexFor(schema.type),
            timeline = schema.timeline,
            timelines = schema.timelines,
            items = schema.items?.let { resolveSchema(it, timelineById, diagnostics, sourcePath, documentId, "$propName[]") },
            properties = schema.properties.mapValues { (key, value) ->
                resolveSchema(value, timelineById, diagnostics, sourcePath, documentId, "$propName.$key")
            },
        )
        val resolvedDefault = schema.default?.let {
            normalizeValue(it, resolvedWithoutDefault, sourcePath, documentId, timelineById, diagnostics, "default:$propName")
        }
        if (schema.default != null && resolvedDefault == null) {
            diagnostics += typeError("Default value for $propName does not conform to schema", sourcePath, documentId)
        }
        return resolvedWithoutDefault.copy(default = resolvedDefault)
    }

    private fun isCompatibleRefinement(parent: ResolvedPropSchema, child: ResolvedPropSchema): Boolean {
        return parent.type == child.type && (!parent.required || child.required)
    }

    private fun normalizeProps(
        ownerLabel: String,
        rawProps: Map<String, RawValue>,
        schema: Map<String, ResolvedPropSchema>,
        sourcePath: String,
        documentId: String,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
    ): Map<String, NormalizedValue> {
        val result = linkedMapOf<String, NormalizedValue>()
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
            val normalized = if (propSchema != null) {
                normalizeValue(rawValue, propSchema, sourcePath, documentId, timelineById, diagnostics, key)
            } else {
                normalizeSchemalessValue(rawValue)
            }
            if (normalized != null) {
                result[key] = normalized
            }
        }
        schema.forEach { (key, propSchema) ->
            if (key !in result) {
                when {
                    propSchema.default != null -> result[key] = propSchema.default
                    propSchema.required -> diagnostics += constraintError(
                        "Required property missing after normalization: $key",
                        SourceInfo(sourcePath, documentId),
                    )
                }
            }
        }
        return result
    }

    private fun normalizeValue(
        rawValue: RawValue,
        schema: ResolvedPropSchema,
        sourcePath: String,
        documentId: String,
        timelineById: Map<String, NormalizedTimeline>,
        diagnostics: MutableList<Diagnostic>,
        propName: String,
    ): NormalizedValue? {
        fun fail(message: String): NormalizedValue? {
            diagnostics += typeError(message, sourcePath, documentId)
            return null
        }

        return when (schema.type) {
            PropType.string -> (rawValue as? RawString)?.let { StringValue(it.value) } ?: fail("$propName must be string")
            PropType.text -> when (rawValue) {
                is RawString -> TextValue(mapOf("default" to rawValue.value))
                is RawObject -> {
                    val textMap = rawValue.values.mapValues { (_, value) ->
                        (value as? RawString)?.value ?: return fail("$propName text map values must be string")
                    }
                    if ("default" !in textMap) return fail("$propName text map must define default")
                    TextValue(textMap)
                }
                else -> fail("$propName must be text")
            }
            PropType.integer -> (rawValue as? RawInteger)?.let { IntegerValue(it.value) } ?: fail("$propName must be integer")
            PropType.number -> when (rawValue) {
                is RawNumber -> NumberValue(rawValue.value)
                is RawInteger -> NumberValue(rawValue.value.toDouble())
                else -> fail("$propName must be number")
            }
            PropType.boolean -> (rawValue as? RawBoolean)?.let { BooleanValue(it.value) } ?: fail("$propName must be boolean")
            PropType.array -> {
                val array = rawValue as? RawArray ?: return fail("$propName must be array")
                ArrayValue(array.values.mapNotNull { value ->
                    schema.items?.let { normalizeValue(value, it, sourcePath, documentId, timelineById, diagnostics, "$propName[]") }
                        ?: normalizeSchemalessValue(value)
                })
            }
            PropType.`object` -> {
                val obj = rawValue as? RawObject ?: return fail("$propName must be object")
                val values = linkedMapOf<String, NormalizedValue>()
                obj.values.forEach { (key, value) ->
                    val propertySchema = schema.properties[key]
                    val normalized = if (propertySchema != null) {
                        normalizeValue(value, propertySchema, sourcePath, documentId, timelineById, diagnostics, "$propName.$key")
                    } else {
                        normalizeSchemalessValue(value)
                    }
                    if (normalized != null) values[key] = normalized
                }
                schema.properties.forEach { (key, propertySchema) ->
                    if (key !in values && propertySchema.required) {
                        diagnostics += constraintError("Required property missing after normalization: $propName.$key", SourceInfo(sourcePath, documentId))
                    }
                }
                ObjectValue(values)
            }
            PropType.instant -> normalizeInstant(rawValue, schema, timelineById, diagnostics, sourcePath, documentId, propName)
            PropType.interval -> normalizeInterval(rawValue, schema, timelineById, diagnostics, sourcePath, documentId, propName)
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
        val shortcutValue = (rawValue as? RawString)?.value
        val obj = rawValue as? RawObject
        val schemaTimeline = (schema.timeline as? TimelineSelector.Id)?.id
        val timeline = (obj?.values?.get("timeline") as? RawString)?.value ?: schemaTimeline
        if (timeline == null) {
            diagnostics += typeError("$propName instant missing timeline", sourcePath, documentId)
            return null
        }
        if (timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (!timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        val value = shortcutValue ?: (obj?.values?.get("value") as? RawString)?.value
        if (value == null) {
            diagnostics += typeError("$propName instant missing value", sourcePath, documentId)
            return null
        }
        val normalizedLiteral = normalizeTemporalLiteral(value)
        val explicitPrecision = (obj?.values?.get("precision") as? RawString)?.value
        val timecode = parseTimecode(
            obj?.values?.get("timecode"),
            "$propName.timecode",
            sourcePath,
            documentId,
            diagnostics,
        )
        return InstantValue(timeline, normalizedLiteral, explicitPrecision, timecode)
    }

    private fun normalizeInterval(
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
            diagnostics += typeError("$propName must be interval object", sourcePath, documentId)
            return null
        }
        val schemaTimeline = (schema.timeline as? TimelineSelector.Id)?.id
        val timeline = (obj.values["timeline"] as? RawString)?.value ?: schemaTimeline
        if (timeline == null) {
            diagnostics += typeError("$propName interval missing timeline", sourcePath, documentId)
            return null
        }
        if (timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (!timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        val fromEndpoint = parseIntervalEndpoint(obj.values["from"], "$propName.from", sourcePath, documentId, diagnostics)
        val toEndpoint = parseIntervalEndpoint(obj.values["to"], "$propName.to", sourcePath, documentId, diagnostics)
        val from = fromEndpoint?.value ?: (obj.values["from"] as? RawString)?.value
        val to = toEndpoint?.value ?: (obj.values["to"] as? RawString)?.value
        val fromTimecode = fromEndpoint?.timecode
            ?: parseTimecode(obj.values["fromTimecode"], "$propName.fromTimecode", sourcePath, documentId, diagnostics)
        val toTimecode = toEndpoint?.timecode
            ?: parseTimecode(obj.values["toTimecode"], "$propName.toTimecode", sourcePath, documentId, diagnostics)
        val fromPrecision = fromEndpoint?.precision ?: (obj.values["fromPrecision"] as? RawString)?.value
        val toPrecision = toEndpoint?.precision ?: (obj.values["toPrecision"] as? RawString)?.value
        if (from == null && to == null) {
            diagnostics += Diagnostic(
                DiagnosticCategory.ConstraintError,
                severityForUnknown(),
                "$propName interval should define at least one bound",
                SourceInfo(sourcePath, documentId),
            )
        }
        return IntervalValue(
            timeline = timeline,
            from = from,
            to = to,
            fromInclusive = (obj.values["fromInclusive"] as? RawBoolean)?.value ?: true,
            toInclusive = (obj.values["toInclusive"] as? RawBoolean)?.value ?: false,
            fromPrecision = fromPrecision,
            toPrecision = toPrecision,
            fromTimecode = fromTimecode,
            toTimecode = toTimecode,
        )
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
        val unit = (obj.values["unit"] as? RawString)?.value
        if (unit == null) {
            diagnostics += typeError("$propName duration missing unit", sourcePath, documentId)
            return null
        }
        val value = when (val raw = obj.values["value"]) {
            is RawNumber -> raw.value
            is RawInteger -> raw.value.toDouble()
            else -> {
                diagnostics += typeError("$propName duration missing numeric value", sourcePath, documentId)
                return null
            }
        }
        val timeline = (obj.values["timeline"] as? RawString)?.value ?: (schema.timeline as? TimelineSelector.Id)?.id
        if (timeline != null && timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (timeline != null && !timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        return DurationValue(unit, value, timeline)
    }

    private fun timelineAllowed(
        timeline: String,
        schema: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
    ): Boolean {
        fun TimelineSelector.matches(): Boolean = when (this) {
            TimelineSelector.Any -> true
            is TimelineSelector.Id -> id == timeline ||
                timelineById[timeline]?.ancestorIds?.contains(id) == true
            is TimelineSelector.Mapped -> to == timeline ||
                timelineById[timeline]?.ancestorIds?.contains(to) == true ||
                timelineById[timeline]?.mappings?.any { mappingTarget(it) == to } == true
        }
        val selectors = schema.timelines ?: schema.timeline?.let(::listOf)
        return selectors.isNullOrEmpty() || selectors.any { it.matches() }
    }

    private fun mappingTarget(mapping: TimelineMapping): String? = when (mapping) {
        is OffsetTimelineMapping -> mapping.to
        is TableTimelineMapping -> mapping.to
        is NoTimelineMapping -> null
    }

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

    private fun parseTimecode(
        rawValue: RawValue?,
        propName: String,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): TimecodeValue? {
        return when (rawValue) {
            null -> null
            is RawInteger -> NumberTimecode(rawValue.value.toDouble())
            is RawNumber -> NumberTimecode(rawValue.value)
            is RawArray -> {
                val values = rawValue.values.mapNotNull { item ->
                    when (item) {
                        is RawInteger -> item.value.toDouble()
                        is RawNumber -> item.value
                        else -> {
                            diagnostics += typeError("$propName must contain only numeric tuple elements", sourcePath, documentId)
                            null
                        }
                    }
                }
                if (values.size == rawValue.values.size) TupleTimecode(values) else null
            }
            else -> {
                diagnostics += typeError("$propName must be number or number tuple", sourcePath, documentId)
                null
            }
        }?.takeIf { timecode ->
            when (timecode) {
                is NumberTimecode -> timecode.value.isFinite()
                is TupleTimecode -> timecode.values.all(Double::isFinite)
            }.also { valid ->
                if (!valid) diagnostics += typeError("$propName must be finite", sourcePath, documentId)
            }
        }
    }

    private fun parseIntervalEndpoint(
        rawValue: RawValue?,
        propName: String,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): IntervalEndpoint? {
        val obj = rawValue as? RawObject ?: return null
        val value = (obj.values["value"] as? RawString)?.value ?: run {
            diagnostics += typeError("$propName.value must be string", sourcePath, documentId)
            return null
        }
        return IntervalEndpoint(
            value = value,
            precision = (obj.values["precision"] as? RawString)?.value,
            timecode = parseTimecode(obj.values["timecode"], "$propName.timecode", sourcePath, documentId, diagnostics),
        )
    }

    private data class IntervalEndpoint(
        val value: String,
        val precision: String?,
        val timecode: TimecodeValue?,
    )

    private fun normalizeTemporalLiteral(value: String): String = value

    private fun defaultIndexFor(type: PropType): PropIndex = when (type) {
        PropType.string -> PropIndex.exact
        PropType.text -> PropIndex.fulltext
        PropType.integer, PropType.number, PropType.instant, PropType.interval, PropType.duration -> PropIndex.range
        PropType.boolean -> PropIndex.exact
        PropType.array, PropType.`object` -> PropIndex.none
    }

    private fun schemaError(message: String, sourcePath: String, documentId: String): Diagnostic =
        Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun referenceError(message: String, sourcePath: String, documentId: String, range: SourceRange? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.ReferenceError, Severity.Error, message, SourceInfo(sourcePath, documentId, range))

    private fun typeError(message: String, sourcePath: String, documentId: String): Diagnostic =
        Diagnostic(DiagnosticCategory.TypeError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun constraintError(message: String, source: SourceInfo): Diagnostic =
        Diagnostic(DiagnosticCategory.ConstraintError, Severity.Error, message, source)

    private fun severityForUnknown(): Severity =
        if (options.mode == ValidationMode.Strict) Severity.Error else Severity.Warning
}
