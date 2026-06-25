package dev.usbharu.graphmd.core

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
            if (fromNode != null && relType.from != null && fromNode.type !in relType.from) {
                diagnostics += constraintError("Relation source type ${fromNode.type} is not allowed for ${relation.type}", relation.source)
            }
            if (toNode != null && relType.to != null && toNode.type !in relType.to) {
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
            validateTimelineExtends(doc, parents, diagnostics)
            val parent = parents.lastOrNull()
            val timeline = NormalizedTimeline(
                id = doc.id,
                calendarType = doc.calendarType.ifBlank { parent?.calendarType ?: "opaque" },
                continuous = doc.continuous ?: parent?.continuous,
                yearZero = doc.yearZero ?: parent?.yearZero,
                defaultEra = doc.defaultEra ?: parent?.defaultEra,
                eras = parent?.eras.orEmpty() + doc.eras,
                units = if (doc.units.isEmpty()) parent?.units.orEmpty() else doc.units,
                ancestorIds = parents.flatMap { it.ancestorIds + it.id }.toSet(),
                mapping = doc.mapping ?: parent?.mapping,
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
            if (left.calendarType != right.calendarType || left.continuous != right.continuous || left.yearZero != right.yearZero || left.units != right.units) {
                diagnostics += schemaError(
                    "Timeline extends must stay on the same time axis; parent timelines are incompatible for ${doc.id}",
                    doc.sourcePath,
                    doc.id,
                )
            }
        }
        parents.forEach { parent ->
            if (doc.calendarType.isNotBlank() && doc.calendarType != parent.calendarType) {
                diagnostics += schemaError("Timeline extends cannot change calendar semantics; use mapping for ${doc.id}", doc.sourcePath, doc.id)
            }
            if (doc.continuous != null && parent.continuous != null && doc.continuous != parent.continuous) {
                diagnostics += schemaError("Timeline extends cannot change time direction/continuity; use mapping for ${doc.id}", doc.sourcePath, doc.id)
            }
            if (doc.yearZero != null && parent.yearZero != null && doc.yearZero != parent.yearZero) {
                diagnostics += schemaError("Timeline extends cannot change yearZero semantics; use mapping for ${doc.id}", doc.sourcePath, doc.id)
            }
            if (doc.units.isNotEmpty() && parent.units.isNotEmpty() && doc.units != parent.units) {
                diagnostics += schemaError("Timeline extends cannot change unit structure; use mapping for ${doc.id}", doc.sourcePath, doc.id)
            }
            if (doc.defaultEra != null && parent.defaultEra != null && doc.defaultEra != parent.defaultEra) {
                diagnostics += schemaError("Timeline extends cannot change baseline/default era; use mapping for ${doc.id}", doc.sourcePath, doc.id)
            }
            doc.eras.forEach { (eraId, era) ->
                val parentEra = parent.eras[eraId] ?: return@forEach
                if (parentEra != era) {
                    diagnostics += schemaError("Timeline extends cannot redefine era semantics; use mapping for ${doc.id}", doc.sourcePath, doc.id)
                }
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
            doc.extends.forEach { parentId ->
                val parent = resolve(parentId)
                if (parent == null) {
                    diagnostics += referenceError("Unknown parent NodeType: $parentId", doc.sourcePath, id)
                } else {
                    parent.props.forEach { (name, schema) ->
                        val existing = props[name]
                        if (existing != null && existing.type != schema.type) {
                            diagnostics += schemaError("Incompatible inherited prop schemas for $name", doc.sourcePath, id)
                        } else {
                            props.putIfAbsent(name, schema)
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
            return NormalizedNodeType(doc.id, props, SourceInfo(doc.sourcePath)).also { resolved[id] = it }
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
                    parent.props.forEach { (name, schema) -> inheritedProps.putIfAbsent(name, schema) }
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
        if (schema.timeline != null && schema.timeline != "any" && schema.timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: ${schema.timeline}", sourcePath, documentId)
        }
        schema.timelines.orEmpty().filterNot { it in timelineById }.forEach {
            diagnostics += referenceError("Unknown Timeline: $it", sourcePath, documentId)
        }
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
        val timeline = ((obj?.values?.get("timeline") as? RawString)?.value ?: schema.timeline)?.takeIf { it != "any" }
            ?: return typeError("$propName instant missing timeline", sourcePath, documentId).let { null }
        if (timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (!timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        val value = shortcutValue ?: (obj?.values?.get("value") as? RawString)?.value
            ?: return typeError("$propName instant missing value", sourcePath, documentId).let { null }
        val normalizedLiteral = normalizeTemporalLiteral(value, timelineById.getValue(timeline), diagnostics, sourcePath, documentId, propName)
        val explicitPrecision = (obj?.values?.get("precision") as? RawString)?.value
        val inferredPrecision = inferPrecision(normalizedLiteral)
        if (explicitPrecision != null && explicitPrecision != inferredPrecision) {
            diagnostics += constraintError("invalid temporal precision for $propName", SourceInfo(sourcePath, documentId))
            return null
        }
        return InstantValue(timeline, normalizedLiteral, explicitPrecision ?: inferredPrecision)
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
        val obj = rawValue as? RawObject ?: return typeError("$propName must be interval object", sourcePath, documentId).let { null }
        val timeline = ((obj.values["timeline"] as? RawString)?.value ?: schema.timeline)?.takeIf { it != "any" }
            ?: return typeError("$propName interval missing timeline", sourcePath, documentId).let { null }
        if (timeline !in timelineById) {
            diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            return null
        }
        if (!timelineAllowed(timeline, schema, timelineById)) {
            diagnostics += constraintError("$propName timeline $timeline is not allowed", SourceInfo(sourcePath, documentId))
            return null
        }
        val from = (obj.values["from"] as? RawString)?.value
        val to = (obj.values["to"] as? RawString)?.value
        if (from == null && to == null) {
            diagnostics += Diagnostic(
                DiagnosticCategory.ConstraintError,
                severityForUnknown(),
                "$propName interval should define at least one bound",
                SourceInfo(sourcePath, documentId),
            )
        }
        from?.let { normalizeTemporalLiteral(it, timelineById.getValue(timeline), diagnostics, sourcePath, documentId, "$propName.from") }
        to?.let { normalizeTemporalLiteral(it, timelineById.getValue(timeline), diagnostics, sourcePath, documentId, "$propName.to") }
        return IntervalValue(
            timeline = timeline,
            from = from,
            to = to,
            fromInclusive = (obj.values["fromInclusive"] as? RawBoolean)?.value ?: true,
            toInclusive = (obj.values["toInclusive"] as? RawBoolean)?.value ?: false,
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
        val obj = rawValue as? RawObject ?: return typeError("$propName must be duration object", sourcePath, documentId).let { null }
        val unit = (obj.values["unit"] as? RawString)?.value
            ?: return typeError("$propName duration missing unit", sourcePath, documentId).let { null }
        val value = when (val raw = obj.values["value"]) {
            is RawNumber -> raw.value
            is RawInteger -> raw.value.toDouble()
            else -> return typeError("$propName duration missing numeric value", sourcePath, documentId).let { null }
        }
        val timeline = (obj.values["timeline"] as? RawString)?.value ?: schema.timeline?.takeIf { it != "any" }
        if (timeline != null) {
            val definition = timelineById[timeline]
            if (definition == null) {
                diagnostics += referenceError("Unknown Timeline: $timeline", sourcePath, documentId)
            } else if (definition.units.isNotEmpty() && unit !in definition.units) {
                diagnostics += Diagnostic(
                    DiagnosticCategory.ConstraintError,
                    Severity.Warning,
                    "$propName duration unit $unit is not declared by timeline $timeline",
                    SourceInfo(sourcePath, documentId),
                )
            }
        }
        return DurationValue(unit, value, timeline)
    }

    private fun timelineAllowed(
        timeline: String,
        schema: ResolvedPropSchema,
        timelineById: Map<String, NormalizedTimeline>,
    ): Boolean {
        fun matches(required: String): Boolean {
            return required == timeline || timelineById[timeline]?.ancestorIds?.contains(required) == true
        }
        return schema.timeline == "any" ||
            schema.timeline?.let(::matches) ?:
            schema.timelines?.let { allowed -> allowed.any(::matches) } ?:
            true
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

    private fun normalizeTemporalLiteral(
        value: String,
        timeline: NormalizedTimeline,
        diagnostics: MutableList<Diagnostic>,
        sourcePath: String,
        documentId: String,
        propName: String,
    ): String {
        if (timeline.calendarType == "opaque" || timeline.calendarType == "custom") {
            return value
        }
        val regex = Regex("^(?:(?<era>[A-Za-z_][A-Za-z0-9_.:-]*)\\s+)?(?<year>[+-]?[0-9]+)(?:-(?<month>[0-9]{2})(?:-(?<day>[0-9]{2})(?:T(?<time>[0-9]{2}:[0-9]{2}:[0-9]{2}(?:Z|[+-][0-9]{2}:[0-9]{2})?))?)?)?$")
        val match = regex.matchEntire(value)
        if (match == null) {
            diagnostics += typeError("Invalid temporal literal for $propName", sourcePath, documentId)
            return value
        }
        val era = match.groups["era"]?.value ?: timeline.defaultEra
        if (match.groups["era"] == null && timeline.eras.isNotEmpty() && timeline.defaultEra == null) {
            diagnostics += constraintError("$propName requires an era", SourceInfo(sourcePath, documentId))
        }
        val year = match.groups["year"]!!.value.toInt()
        if (timeline.yearZero == false && year == 0) {
            diagnostics += constraintError("$propName uses year 0 on a no-year-zero timeline", SourceInfo(sourcePath, documentId))
        }
        return buildString {
            if (era != null) append("$era ")
            append(match.groups["year"]!!.value)
            match.groups["month"]?.value?.let { append("-$it") }
            match.groups["day"]?.value?.let { append("-$it") }
            match.groups["time"]?.value?.let { append("T$it") }
        }
    }

    private fun inferPrecision(value: String): String = when {
        'T' in value -> "time"
        value.count { it == '-' } >= 2 -> "day"
        value.count { it == '-' } == 1 -> "month"
        else -> "year"
    }

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
