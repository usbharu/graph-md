package dev.usbharu.graphmd.query.persistence

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.index.*
import dev.usbharu.graphmd.query.ir.*
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.text.TextAnalyzer

data class SearchIndexFormatOptions(
    val compilerVersion: String = "unknown",
    val maxEntriesPerShard: Int = 1_000,
) {
    init {
        require(maxEntriesPerShard > 0) { "maxEntriesPerShard must be positive" }
    }
}

data class SearchIndexManifest(
    val formatVersion: Int,
    val compilerVersion: String,
    val analyzerVersion: String,
    val graphHash: String,
    val shards: Map<String, List<String>>,
)

data class StaticSearchBundle(
    val manifest: String,
    val shards: Map<String, String>,
) {
    fun files(): Map<String, String> = linkedMapOf(MANIFEST_FILE_NAME to manifest) + shards

    companion object {
        const val MANIFEST_FILE_NAME: String = "manifest.json"
    }
}

object StaticSearchIndexCodec {
    const val FORMAT_VERSION: Int = 3

    fun encode(
        index: SearchIndex,
        options: SearchIndexFormatOptions = SearchIndexFormatOptions(),
    ): StaticSearchBundle {
        val files = linkedMapOf<String, String>()
        val shardManifest = linkedMapOf<String, List<String>>()

        fun addShards(category: String, records: List<Json>) {
            val chunks = records.chunked(options.maxEntriesPerShard).ifEmpty { listOf(emptyList()) }
            val names = chunks.mapIndexed { shardIndex, entries ->
                val name = "$category-${shardIndex.toString().padStart(3, '0')}.json"
                files[name] = jsonArray(entries).encode()
                name
            }
            shardManifest[category] = names
        }

        addShards(
            "metadata",
            listOf(
                jsonObject(
                    "timelines" to jsonArray(index.graph.timelines.sortedBy { it.id.value }.map(::encodeTimeline)),
                    "nodeTypeIds" to jsonArray(index.graph.nodeTypeIds.sortedBy { it.value }.map { jsonString(it.value) }),
                    "relationTypeIds" to jsonArray(
                        index.graph.relationTypeIds.sortedBy { it.value }.map { jsonString(it.value) },
                    ),
                    "nodeTypeSchemas" to jsonArray(
                        index.graph.nodeTypeSchemas.values.sortedBy { it.id.value }.map(::encodeNodeTypeSchema),
                    ),
                    "relationTypeSchemas" to jsonArray(
                        index.graph.relationTypeSchemas.values.sortedBy { it.id.value }.map(::encodeRelationTypeSchema),
                    ),
                ),
            ),
        )
        addShards("nodes", index.graph.nodes.sortedBy { it.id.value }.map(::encodeNode))
        addShards("properties", index.graph.propertyAssertions.sortedBy { it.id.value }.map(::encodeProperty))
        addShards("relations", index.graph.relationAssertions.sortedBy { it.id.value }.map(::encodeRelation))
        addShards("texts", index.graph.textAssertions.sortedBy { it.id.value }.map(::encodeText))

        addShards(
            "node-postings",
            index.nodeIdsByType.entries.sortedBy { it.key.value }.map { (type, ids) ->
                jsonObject(
                    "type" to jsonString(type.value),
                    "nodeIds" to jsonArray(ids.map { jsonString(it.value) }),
                )
            },
        )
        addShards(
            "property-exact-postings",
            index.propertyExactPostings.entries
                .sortedWith(compareBy({ it.key.propertyId.value }, { it.key.valueKey }))
                .map { (key, ids) ->
                    jsonObject(
                        "propertyId" to jsonString(key.propertyId.value),
                        "valueKey" to jsonString(key.valueKey),
                        "assertionIds" to encodeIds(ids),
                    )
                },
        )
        addShards(
            "property-value-postings",
            index.propertyValuePostings.entries.sortedBy { it.key.value }.map { (propertyId, postings) ->
                jsonObject(
                    "propertyId" to jsonString(propertyId.value),
                    "postings" to jsonArray(postings.map(::encodePropertyPosting)),
                )
            },
        )
        addShards("relation-postings", encodeRelationPostings(index))
        addShards(
            "interval-metadata",
            listOf(
                jsonObject(
                    "universalAssertionIds" to encodeIds(index.intervalIndex.universalAssertionIds.sortedBy { it.value }),
                ),
            ),
        )
        addShards(
            "intervals",
            index.intervalIndex.entriesByTimeline.entries.sortedBy { it.key.value }.map { (timeline, entries) ->
                jsonObject(
                    "timelineId" to jsonString(timeline.value),
                    "entries" to jsonArray(entries.map(::encodeIntervalEntry)),
                )
            },
        )
        addShards(
            "fulltext-metadata",
            listOf(
                jsonObject(
                    "averageDocumentLength" to jsonNumber(index.fullTextIndex.averageDocumentLength),
                    "documentLengths" to jsonArray(
                        index.fullTextIndex.documentLengths.entries.sortedBy { it.key.value }.map { (id, length) ->
                            jsonObject("assertionId" to jsonNumber(id.value), "length" to jsonNumber(length))
                        },
                    ),
                ),
            ),
        )
        addShards(
            "fulltext",
            index.fullTextIndex.postingsByTerm.entries.sortedBy { it.key }.map { (term, postings) ->
                jsonObject(
                    "term" to jsonString(term),
                    "postings" to jsonArray(postings.map(::encodeTermPosting)),
                )
            },
        )

        val graphHash = hashFiles(files)
        val manifest = SearchIndexManifest(
            formatVersion = FORMAT_VERSION,
            compilerVersion = options.compilerVersion,
            analyzerVersion = TextAnalyzer.VERSION,
            graphHash = graphHash,
            shards = shardManifest,
        )
        return StaticSearchBundle(encodeManifest(manifest).encode(), files)
    }

    fun decode(bundle: StaticSearchBundle): SearchIndex {
        val manifest = decodeManifest(parseJson(bundle.manifest))
        require(manifest.formatVersion == FORMAT_VERSION) {
            "Unsupported search index format version: ${manifest.formatVersion}"
        }
        require(manifest.analyzerVersion == TextAnalyzer.VERSION) {
            "Unsupported analyzer version: ${manifest.analyzerVersion}"
        }
        val referencedFiles = manifest.shards.values.flatten().associateWith { file ->
            requireNotNull(bundle.shards[file]) { "Missing search index shard: $file" }
        }
        require(hashFiles(referencedFiles) == manifest.graphHash) { "Search index checksum mismatch" }

        fun records(category: String): List<Json> =
            manifest.shards[category].orEmpty().flatMap { file -> parseJson(referencedFiles.getValue(file)).arrayValue() }

        val metadata = records("metadata").single().objectValue()
        val timelines = metadata.required("timelines").arrayValue().map(::decodeTimeline)
        val nodeTypeIds = metadata.required("nodeTypeIds").arrayValue().mapTo(linkedSetOf()) {
            NodeTypeId(it.stringValue())
        }
        val relationTypeIds = metadata.required("relationTypeIds").arrayValue().mapTo(linkedSetOf()) {
            RelationTypeId(it.stringValue())
        }
        val nodeTypeSchemas = metadata.required("nodeTypeSchemas").arrayValue().map(::decodeNodeTypeSchema)
            .associateBy { it.id }
        val relationTypeSchemas = metadata.required("relationTypeSchemas").arrayValue().map(::decodeRelationTypeSchema)
            .associateBy { it.id }
        val nodes = records("nodes").map(::decodeNode)
        val properties = records("properties").map(::decodeProperty)
        val propertyById = properties.associateBy { it.id }
        val relations = records("relations").map { decodeRelation(it, propertyById) }
        val texts = records("texts").map(::decodeText)
        val graph = QueryableGraph(
            nodes,
            properties,
            relations,
            texts,
            timelines,
            nodeTypeIds,
            relationTypeIds,
            nodeTypeSchemas,
            relationTypeSchemas,
        )

        val nodePostings = records("node-postings").associate { record ->
            val value = record.objectValue()
            NodeTypeId(value.required("type").stringValue()) to
                value.required("nodeIds").arrayValue().map { NodeId(it.stringValue()) }
        }
        val exactPostings = records("property-exact-postings").associate { record ->
            val value = record.objectValue()
            PropertyExactKey(
                PropertyId(value.required("propertyId").stringValue()),
                value.required("valueKey").stringValue(),
            ) to decodeIds(value.required("assertionIds"))
        }
        val valuePostings = records("property-value-postings").associate { record ->
            val value = record.objectValue()
            PropertyId(value.required("propertyId").stringValue()) to
                value.required("postings").arrayValue().map(::decodePropertyPosting)
        }
        val relationPostings = decodeRelationPostings(records("relation-postings"))
        val intervalMetadata = records("interval-metadata").single().objectValue()
        val universalIds = decodeIds(intervalMetadata.required("universalAssertionIds")).toSet()
        val intervalEntries = records("intervals").associate { record ->
            val value = record.objectValue()
            TimelineId(value.required("timelineId").stringValue()) to
                value.required("entries").arrayValue().map(::decodeIntervalEntry)
        }
        val assertionTimes = buildMap {
            properties.forEach { put(it.id, it.validTime) }
            relations.forEach { put(it.id, it.validTime) }
            texts.forEach { put(it.id, it.validTime) }
        }
        val fullTextMetadata = records("fulltext-metadata").single().objectValue()
        val documentLengths = fullTextMetadata.required("documentLengths").arrayValue().associate { record ->
            val value = record.objectValue()
            AssertionId(value.required("assertionId").intValue()) to value.required("length").intValue()
        }
        val fullTextPostings = records("fulltext").associate { record ->
            val value = record.objectValue()
            value.required("term").stringValue() to value.required("postings").arrayValue().map(::decodeTermPosting)
        }

        validateAssertionReferences(
            graph = graph,
            postingIds = buildList {
                exactPostings.values.forEach(::addAll)
                valuePostings.values.flatten().mapTo(this) { it.assertionId }
                relationPostings.allIds.forEach(::add)
                intervalEntries.values.flatten().mapTo(this) { it.assertionId }
                fullTextPostings.values.flatten().mapTo(this) { it.assertionId }
            },
        )

        return SearchIndex(
            graph = graph,
            nodeIdsByType = nodePostings,
            propertyExactPostings = exactPostings,
            propertyValuePostings = valuePostings,
            propertyIdsByOwnerAndPath = buildPropertyOwnerPathPostings(properties),
            relationIdsBySource = relationPostings.source,
            relationIdsByTarget = relationPostings.target,
            relationIdsByTypeAndSource = relationPostings.typeSource,
            relationIdsByTypeAndTarget = relationPostings.typeTarget,
            textAssertionIdsByOwner = texts.groupBy({ it.owner }, { it.id }),
            intervalIndex = IntervalIndex(intervalEntries, universalIds, assertionTimes),
            fullTextIndex = FullTextIndex(
                fullTextPostings,
                documentLengths,
                fullTextMetadata.required("averageDocumentLength").doubleValue(),
            ),
        )
    }

    fun readManifest(bundle: StaticSearchBundle): SearchIndexManifest =
        decodeManifest(parseJson(bundle.manifest))
}

private fun encodeManifest(manifest: SearchIndexManifest): Json = jsonObject(
    "formatVersion" to jsonNumber(manifest.formatVersion),
    "compilerVersion" to jsonString(manifest.compilerVersion),
    "analyzerVersion" to jsonString(manifest.analyzerVersion),
    "graphHash" to jsonString(manifest.graphHash),
    "shards" to Json.Object(
        manifest.shards.mapValues { (_, files) -> jsonArray(files.map(::jsonString)) },
    ),
)

private fun decodeManifest(json: Json): SearchIndexManifest {
    val value = json.objectValue()
    return SearchIndexManifest(
        formatVersion = value.required("formatVersion").intValue(),
        compilerVersion = value.required("compilerVersion").stringValue(),
        analyzerVersion = value.required("analyzerVersion").stringValue(),
        graphHash = value.required("graphHash").stringValue(),
        shards = value.required("shards").objectValue().mapValues { (_, files) ->
            files.arrayValue().map(Json::stringValue)
        },
    )
}

private fun encodeTimeline(timeline: QueryTimeline): Json = jsonObject(
    "id" to jsonString(timeline.id.value),
    "canonicalId" to jsonString(timeline.canonicalId.value),
    "offsetToCanonical" to jsonNumber(timeline.offsetToCanonical),
    "assertionScopeId" to jsonString(timeline.assertionScopeId.value),
)

private fun decodeTimeline(json: Json): QueryTimeline {
    val value = json.objectValue()
    return QueryTimeline(
        TimelineId(value.required("id").stringValue()),
        TimelineId(value.required("canonicalId").stringValue()),
        value.required("offsetToCanonical").doubleValue(),
        TimelineId(value["assertionScopeId"]?.stringValue() ?: value.required("id").stringValue()),
    )
}

private fun encodeNode(node: QueryNode): Json = jsonObject(
    "id" to jsonString(node.id.value),
    "typeId" to jsonString(node.typeId.value),
    "ancestorTypeIds" to jsonArray(node.ancestorTypeIds.sortedBy { it.value }.map { jsonString(it.value) }),
    "kind" to jsonString(node.kind.name),
    "url" to jsonNullableString(node.url),
    "validTime" to encodeIntervalSet(node.validTime),
    "source" to encodeSource(node.source),
)

private fun decodeNode(json: Json): QueryNode {
    val value = json.objectValue()
    return QueryNode(
        id = NodeId(value.required("id").stringValue()),
        typeId = NodeTypeId(value.required("typeId").stringValue()),
        ancestorTypeIds = value.required("ancestorTypeIds").arrayValue().mapTo(linkedSetOf()) {
            NodeTypeId(it.stringValue())
        },
        kind = DocumentKind.valueOf(value.required("kind").stringValue()),
        url = value.required("url").nullableStringValue(),
        validTime = decodeIntervalSet(value.required("validTime")),
        source = decodeSource(value.required("source")),
    )
}

private fun encodeProperty(assertion: PropertyAssertion): Json = jsonObject(
    "id" to jsonNumber(assertion.id.value),
    "stableKey" to jsonString(assertion.stableKey.value),
    "owner" to encodeOwner(assertion.owner),
    "propertyId" to jsonString(assertion.propertyId.value),
    "path" to jsonArray(assertion.path.segments.map(::jsonString)),
    "value" to encodeNormalizedValue(assertion.value),
    "validTime" to encodeIntervalSet(assertion.validTime),
    "source" to encodeSource(assertion.source),
    "fallback" to jsonBoolean(assertion.isFallback),
)

private fun decodeProperty(json: Json): PropertyAssertion {
    val value = json.objectValue()
    return PropertyAssertion(
        id = AssertionId(value.required("id").intValue()),
        stableKey = StableAssertionKey(value.required("stableKey").stringValue()),
        owner = decodeOwner(value.required("owner")),
        propertyId = PropertyId(value.required("propertyId").stringValue()),
        path = PropertyPath(value.required("path").arrayValue().map(Json::stringValue)),
        value = decodeNormalizedValue(value.required("value")),
        validTime = decodeIntervalSet(value.required("validTime")),
        source = decodeSource(value.required("source")),
        isFallback = value.required("fallback").booleanValue(),
    )
}

private fun encodeRelation(assertion: RelationAssertion): Json = jsonObject(
    "id" to jsonNumber(assertion.id.value),
    "stableKey" to jsonString(assertion.stableKey.value),
    "sourceNodeId" to jsonString(assertion.sourceNodeId.value),
    "targetNodeId" to jsonString(assertion.targetNodeId.value),
    "relTypeId" to jsonString(assertion.relTypeId.value),
    "ancestorRelTypeIds" to jsonArray(
        assertion.ancestorRelTypeIds.sortedBy { it.value }.map { jsonString(it.value) },
    ),
    "propertyIds" to encodeIds(assertion.properties.map { it.id }),
    "label" to jsonString(assertion.label),
    "validTime" to encodeIntervalSet(assertion.validTime),
    "source" to encodeSource(assertion.source),
)

private fun decodeRelation(
    json: Json,
    propertyById: Map<AssertionId, PropertyAssertion>,
): RelationAssertion {
    val value = json.objectValue()
    return RelationAssertion(
        id = AssertionId(value.required("id").intValue()),
        stableKey = StableAssertionKey(value.required("stableKey").stringValue()),
        sourceNodeId = NodeId(value.required("sourceNodeId").stringValue()),
        targetNodeId = NodeId(value.required("targetNodeId").stringValue()),
        relTypeId = RelationTypeId(value.required("relTypeId").stringValue()),
        ancestorRelTypeIds = value.required("ancestorRelTypeIds").arrayValue().mapTo(linkedSetOf()) {
            RelationTypeId(it.stringValue())
        },
        properties = decodeIds(value.required("propertyIds")).map {
            requireNotNull(propertyById[it]) { "Unknown relation property assertion: ${it.value}" }
        },
        label = value.required("label").stringValue(),
        validTime = decodeIntervalSet(value.required("validTime")),
        source = decodeSource(value.required("source")),
    )
}

private fun encodeText(assertion: TextAssertion): Json = jsonObject(
    "id" to jsonNumber(assertion.id.value),
    "stableKey" to jsonString(assertion.stableKey.value),
    "owner" to encodeOwner(assertion.owner),
    "kind" to jsonString(assertion.kind.name),
    "text" to jsonString(assertion.text),
    "validTime" to encodeIntervalSet(assertion.validTime),
    "source" to encodeSource(assertion.source),
    "sourceRange" to encodeRange(assertion.sourceRange),
    "propertyPath" to (assertion.propertyPath?.let { path ->
        jsonArray(path.segments.map(::jsonString))
    } ?: Json.Null),
)

private fun decodeText(json: Json): TextAssertion {
    val value = json.objectValue()
    return TextAssertion(
        id = AssertionId(value.required("id").intValue()),
        stableKey = StableAssertionKey(value.required("stableKey").stringValue()),
        owner = decodeOwner(value.required("owner")),
        kind = TextKind.valueOf(value.required("kind").stringValue()),
        text = value.required("text").stringValue(),
        validTime = decodeIntervalSet(value.required("validTime")),
        source = decodeSource(value.required("source")),
        sourceRange = decodeRange(value.required("sourceRange")),
        propertyPath = value.required("propertyPath").let { encoded ->
            if (encoded === Json.Null) null else PropertyPath(encoded.arrayValue().map(Json::stringValue))
        },
    )
}

private fun encodeNodeTypeSchema(schema: QueryNodeTypeSchema): Json = jsonObject(
    "id" to jsonString(schema.id.value),
    "ancestors" to jsonArray(schema.ancestorTypeIds.sortedBy { it.value }.map { jsonString(it.value) }),
    "properties" to encodePropertySchemas(schema.properties),
)

private fun decodeNodeTypeSchema(json: Json): QueryNodeTypeSchema {
    val value = json.objectValue()
    return QueryNodeTypeSchema(
        NodeTypeId(value.required("id").stringValue()),
        decodePropertySchemas(value.required("properties")),
        value.required("ancestors").arrayValue().mapTo(linkedSetOf()) { NodeTypeId(it.stringValue()) },
    )
}

private fun encodeRelationTypeSchema(schema: QueryRelationTypeSchema): Json = jsonObject(
    "id" to jsonString(schema.id.value),
    "ancestors" to jsonArray(schema.ancestorTypeIds.sortedBy { it.value }.map { jsonString(it.value) }),
    "sources" to (schema.sourceTypeIds?.let {
        jsonArray(it.sortedBy { id -> id.value }.map { id -> jsonString(id.value) })
    } ?: Json.Null),
    "targets" to (schema.targetTypeIds?.let {
        jsonArray(it.sortedBy { id -> id.value }.map { id -> jsonString(id.value) })
    } ?: Json.Null),
    "properties" to encodePropertySchemas(schema.properties),
)

private fun decodeRelationTypeSchema(json: Json): QueryRelationTypeSchema {
    val value = json.objectValue()
    fun types(name: String): Set<NodeTypeId>? = value.required(name).let { encoded ->
        if (encoded === Json.Null) null else encoded.arrayValue().mapTo(linkedSetOf()) { NodeTypeId(it.stringValue()) }
    }
    return QueryRelationTypeSchema(
        RelationTypeId(value.required("id").stringValue()),
        decodePropertySchemas(value.required("properties")),
        types("sources"),
        types("targets"),
        value.required("ancestors").arrayValue().mapTo(linkedSetOf()) { RelationTypeId(it.stringValue()) },
    )
}

private fun encodePropertySchemas(schemas: Map<String, ResolvedPropSchema>): Json =
    jsonArray(schemas.entries.sortedBy { it.key }.map { (name, schema) ->
        jsonObject("name" to jsonString(name), "schema" to encodePropertySchema(schema))
    })

private fun decodePropertySchemas(json: Json): Map<String, ResolvedPropSchema> =
    json.arrayValue().associateTo(linkedMapOf()) {
        val value = it.objectValue()
        value.required("name").stringValue() to decodePropertySchema(value.required("schema"))
    }

private fun encodePropertySchema(schema: ResolvedPropSchema): Json = jsonObject(
    "type" to jsonString(schema.type.name),
    "required" to jsonBoolean(schema.required),
    "items" to (schema.items?.let(::encodePropertySchema) ?: Json.Null),
    "enum" to (schema.enumValues?.let { jsonArray(it.map(::encodeRawValue)) } ?: Json.Null),
)

private fun decodePropertySchema(json: Json): ResolvedPropSchema {
    val value = json.objectValue()
    return ResolvedPropSchema(
        type = PropType.valueOf(value.required("type").stringValue()),
        required = value.required("required").booleanValue(),
        items = value.required("items").let { if (it === Json.Null) null else decodePropertySchema(it) },
        enumValues = value["enum"]?.let { if (it === Json.Null) null else it.arrayValue().map(::decodeRawValue) },
    )
}

private fun encodeRawValue(value: RawValue): Json = when (value) {
    is RawString -> jsonObject("kind" to jsonString("string"), "value" to jsonString(value.value))
    is RawInteger -> jsonObject("kind" to jsonString("integer"), "value" to jsonNumber(value.value))
    is RawNumber -> jsonObject("kind" to jsonString("number"), "value" to jsonNumber(value.value))
    is RawBoolean -> jsonObject("kind" to jsonString("boolean"), "value" to jsonBoolean(value.value))
    RawNull -> jsonObject("kind" to jsonString("null"))
    is RawArray -> jsonObject(
        "kind" to jsonString("array"),
        "values" to jsonArray(value.values.map(::encodeRawValue)),
    )
    is RawObject -> jsonObject(
        "kind" to jsonString("object"),
        "values" to Json.Object(
            value.values.entries
                .sortedBy { it.key }
                .associateTo(linkedMapOf()) { (key, child) -> key to encodeRawValue(child) },
        ),
    )
}

private fun decodeRawValue(json: Json): RawValue {
    val value = json.objectValue()
    return when (value.required("kind").stringValue()) {
        "string" -> RawString(value.required("value").stringValue())
        "integer" -> RawInteger(value.required("value").longValue())
        "number" -> RawNumber(value.required("value").doubleValue())
        "boolean" -> RawBoolean(value.required("value").booleanValue())
        "null" -> RawNull
        "array" -> RawArray(value.required("values").arrayValue().map(::decodeRawValue))
        "object" -> RawObject(value.required("values").objectValue().mapValues { (_, child) -> decodeRawValue(child) })
        else -> error("Unknown encoded RawValue kind")
    }
}

private fun encodeOwner(owner: AssertionOwner): Json = when (owner) {
    is AssertionOwner.Node -> jsonObject(
        "kind" to jsonString("node"),
        "nodeId" to jsonString(owner.nodeId.value),
    )
    is AssertionOwner.Relation -> jsonObject(
        "kind" to jsonString("relation"),
        "relationAssertionId" to jsonNumber(owner.relationAssertionId.value),
    )
}

private fun decodeOwner(json: Json): AssertionOwner {
    val value = json.objectValue()
    return when (value.required("kind").stringValue()) {
        "node" -> AssertionOwner.Node(NodeId(value.required("nodeId").stringValue()))
        "relation" -> AssertionOwner.Relation(AssertionId(value.required("relationAssertionId").intValue()))
        else -> error("Unknown assertion owner kind")
    }
}

private fun encodeIntervalSet(set: IntervalSet): Json = jsonObject(
    "universal" to jsonBoolean(set.isUniversal),
    "intervals" to jsonArray(set.intervals.map(::encodeInterval)),
)

private fun decodeIntervalSet(json: Json): IntervalSet {
    val value = json.objectValue()
    return if (value.required("universal").booleanValue()) {
        IntervalSet.universal()
    } else {
        IntervalSet.of(value.required("intervals").arrayValue().map(::decodeInterval))
    }
}

private fun encodeInterval(interval: TemporalInterval): Json = jsonObject(
    "timelineId" to jsonString(interval.timelineId.value),
    "start" to encodeBoundary(interval.start),
    "end" to encodeBoundary(interval.end),
)

private fun decodeInterval(json: Json): TemporalInterval {
    val value = json.objectValue()
    return TemporalInterval(
        TimelineId(value.required("timelineId").stringValue()),
        decodeBoundary(value.required("start")),
        decodeBoundary(value.required("end")),
    )
}

private fun encodeBoundary(boundary: IntervalBoundary?): Json = boundary?.let {
    jsonObject("value" to jsonNumber(it.value), "inclusive" to jsonBoolean(it.inclusive))
} ?: Json.Null

private fun decodeBoundary(json: Json): IntervalBoundary? {
    if (json === Json.Null) return null
    val value = json.objectValue()
    return IntervalBoundary(
        value.required("value").doubleValue(),
        value.required("inclusive").booleanValue(),
    )
}

private fun encodeSource(source: SourceInfo): Json = jsonObject(
    "path" to jsonString(source.path),
    "documentId" to jsonNullableString(source.documentId),
    "range" to encodeRange(source.range),
)

private fun decodeSource(json: Json): SourceInfo {
    val value = json.objectValue()
    return SourceInfo(
        value.required("path").stringValue(),
        value.required("documentId").nullableStringValue(),
        decodeRange(value.required("range")),
    )
}

private fun encodeRange(range: SourceRange?): Json = range?.let {
    jsonObject("start" to jsonNumber(it.start), "end" to jsonNumber(it.end))
} ?: Json.Null

private fun decodeRange(json: Json): SourceRange? {
    if (json === Json.Null) return null
    val value = json.objectValue()
    return SourceRange(value.required("start").intValue(), value.required("end").intValue())
}

private fun encodeNormalizedValue(value: NormalizedValue): Json = when (value) {
    is StringValue -> jsonObject("kind" to jsonString("string"), "value" to jsonString(value.value))
    is IntegerValue -> jsonObject("kind" to jsonString("integer"), "value" to jsonNumber(value.value))
    is NumberValue -> jsonObject("kind" to jsonString("number"), "value" to jsonNumber(value.value))
    is BooleanValue -> jsonObject("kind" to jsonString("boolean"), "value" to jsonBoolean(value.value))
    NullValue -> jsonObject("kind" to jsonString("null"))
    is TextValue -> jsonObject(
        "kind" to jsonString("text"),
        "members" to encodeMembers(value.memberEntries),
    )
    is ArrayValue -> jsonObject(
        "kind" to jsonString("array"),
        "elements" to jsonArray(value.elements.map { element ->
            jsonObject(
                "value" to encodeNormalizedValue(element.value),
                "validTime" to encodeValidTimes(element.validTime),
                "fallback" to jsonBoolean(element.isFallback),
            )
        }),
    )
    is ObjectValue -> jsonObject(
        "kind" to jsonString("object"),
        "members" to encodeMembers(value.members),
    )
    is InstantValue -> jsonObject(
        "kind" to jsonString("instant"),
        "timeline" to jsonNullableString(value.timeline),
        "lexicalValue" to jsonNullableString(value.value),
        "timecode" to when (val code = value.timecode) {
            is NumberTimecode -> jsonNumber(code.value)
        },
    )
    is DurationValue -> jsonObject(
        "kind" to jsonString("duration"),
        "timeline" to jsonNullableString(value.timeline),
        "from" to encodeTemporalPoint(value.from),
        "to" to encodeTemporalPoint(value.to),
    )
}

private fun decodeNormalizedValue(json: Json): NormalizedValue {
    val value = json.objectValue()
    return when (value.required("kind").stringValue()) {
        "string" -> StringValue(value.required("value").stringValue())
        "integer" -> IntegerValue(value.required("value").longValue())
        "number" -> NumberValue(value.required("value").doubleValue())
        "boolean" -> BooleanValue(value.required("value").booleanValue())
        "null" -> NullValue
        "text" -> TextValue(decodeMembers(value.required("members")))
        "array" -> {
            val elements = value.required("elements").arrayValue().map { encoded ->
                val element = encoded.objectValue()
                NormalizedArrayElement(
                    decodeNormalizedValue(element.required("value")),
                    decodeValidTimes(element.required("validTime")),
                    element.required("fallback").booleanValue(),
                )
            }
            ArrayValue(elements.map { it.value }, elements)
        }
        "object" -> {
            val members = decodeMembers(value.required("members"))
            ObjectValue(members.mapValues { it.value.value }, members)
        }
        "instant" -> InstantValue(
            timeline = value.required("timeline").nullableStringValue(),
            value = value.required("lexicalValue").nullableStringValue(),
            timecode = NumberTimecode(value.required("timecode").doubleValue()),
        )
        "duration" -> DurationValue(
            timeline = value.required("timeline").nullableStringValue(),
            from = decodeTemporalPoint(value.required("from")),
            to = decodeTemporalPoint(value.required("to")),
        )
        else -> error("Unknown normalized value kind")
    }
}

private fun encodeMembers(members: Map<String, NormalizedPropEntry>): Json =
    jsonArray(members.entries.sortedBy { it.key }.map { (name, entry) ->
        jsonObject(
            "name" to jsonString(name),
            "value" to encodeNormalizedValue(entry.value),
            "validTime" to encodeValidTimes(entry.validTime),
            "fallback" to jsonBoolean(entry.isFallback),
        )
    })

private fun decodeMembers(json: Json): Map<String, NormalizedPropEntry> =
    json.arrayValue().associateTo(linkedMapOf()) { encoded ->
        val value = encoded.objectValue()
        value.required("name").stringValue() to NormalizedPropEntry(
            decodeNormalizedValue(value.required("value")),
            decodeValidTimes(value.required("validTime")),
            value.required("fallback").booleanValue(),
        )
    }

private fun encodeValidTimes(validTimes: List<ValidTime>): Json = jsonArray(validTimes.map { validTime ->
    jsonObject(
        "timeline" to jsonString(validTime.timeline),
        "from" to encodeTimePoint(validTime.from),
        "to" to encodeTimePoint(validTime.to),
    )
})

private fun decodeValidTimes(json: Json): List<ValidTime> = json.arrayValue().map { encoded ->
    val value = encoded.objectValue()
    ValidTime(
        value.required("timeline").stringValue(),
        decodeTimePoint(value.required("from")),
        decodeTimePoint(value.required("to")),
    )
}

private fun encodeTimePoint(point: TimePoint?): Json = point?.let {
    jsonObject("timecode" to jsonNumber(it.timecode), "value" to jsonNullableString(it.value))
} ?: Json.Null

private fun decodeTimePoint(json: Json): TimePoint? {
    if (json === Json.Null) return null
    val value = json.objectValue()
    return TimePoint(value.required("timecode").doubleValue(), value.required("value").nullableStringValue())
}

private fun encodeTemporalPoint(point: TemporalPoint?): Json = point?.let {
    jsonObject(
        "timecode" to jsonNumber(it.timecode),
        "value" to jsonNullableString(it.value),
        "timeline" to jsonNullableString(it.timeline),
    )
} ?: Json.Null

private fun decodeTemporalPoint(json: Json): TemporalPoint? {
    if (json === Json.Null) return null
    val value = json.objectValue()
    return TemporalPoint(
        value.required("timecode").doubleValue(),
        value.required("value").nullableStringValue(),
        value.required("timeline").nullableStringValue(),
    )
}

private fun encodePropertyPosting(posting: PropertyValuePosting): Json = jsonObject(
    "assertionId" to jsonNumber(posting.assertionId.value),
    "sortKey" to jsonObject(
        "typeRank" to jsonNumber(posting.sortKey.typeRank),
        "numericValue" to jsonNullableNumber(posting.sortKey.numericValue),
        "integerValue" to jsonNullableLong(posting.sortKey.integerValue),
        "textValue" to jsonNullableString(posting.sortKey.textValue),
    ),
)

private fun decodePropertyPosting(json: Json): PropertyValuePosting {
    val value = json.objectValue()
    val key = value.required("sortKey").objectValue()
    return PropertyValuePosting(
        AssertionId(value.required("assertionId").intValue()),
        PropertySortKey(
            typeRank = key.required("typeRank").intValue(),
            numericValue = key.required("numericValue").nullableDoubleValue(),
            integerValue = key.required("integerValue").nullableLongValue(),
            textValue = key.required("textValue").nullableStringValue(),
        ),
    )
}

private fun encodeIntervalEntry(entry: IntervalEntry): Json = jsonObject(
    "start" to encodeBoundary(entry.start),
    "end" to encodeBoundary(entry.end),
    "assertionId" to jsonNumber(entry.assertionId.value),
)

private fun decodeIntervalEntry(json: Json): IntervalEntry {
    val value = json.objectValue()
    return IntervalEntry(
        decodeBoundary(value.required("start")),
        decodeBoundary(value.required("end")),
        AssertionId(value.required("assertionId").intValue()),
    )
}

private fun encodeTermPosting(posting: TermPosting): Json = jsonObject(
    "assertionId" to jsonNumber(posting.assertionId.value),
    "termFrequency" to jsonNumber(posting.termFrequency),
    "positions" to jsonArray(posting.positions.map(::jsonNumber)),
)

private fun decodeTermPosting(json: Json): TermPosting {
    val value = json.objectValue()
    return TermPosting(
        AssertionId(value.required("assertionId").intValue()),
        value.required("termFrequency").intValue(),
        value.required("positions").arrayValue().map(Json::intValue),
    )
}

private fun encodeRelationPostings(index: SearchIndex): List<Json> = buildList {
    index.relationIdsBySource.entries.sortedBy { it.key.value }.forEach { (node, ids) ->
        add(encodeRelationPosting("source", node, null, ids))
    }
    index.relationIdsByTarget.entries.sortedBy { it.key.value }.forEach { (node, ids) ->
        add(encodeRelationPosting("target", node, null, ids))
    }
    index.relationIdsByTypeAndSource.entries
        .sortedWith(compareBy({ it.key.relationTypeId.value }, { it.key.nodeId.value }))
        .forEach { (key, ids) ->
            add(encodeRelationPosting("typeSource", key.nodeId, key.relationTypeId, ids))
        }
    index.relationIdsByTypeAndTarget.entries
        .sortedWith(compareBy({ it.key.relationTypeId.value }, { it.key.nodeId.value }))
        .forEach { (key, ids) ->
            add(encodeRelationPosting("typeTarget", key.nodeId, key.relationTypeId, ids))
        }
}

private fun encodeRelationPosting(
    kind: String,
    nodeId: NodeId,
    typeId: RelationTypeId?,
    ids: List<AssertionId>,
): Json = jsonObject(
    "kind" to jsonString(kind),
    "nodeId" to jsonString(nodeId.value),
    "relationTypeId" to jsonNullableString(typeId?.value),
    "assertionIds" to encodeIds(ids),
)

private data class DecodedRelationPostings(
    val source: Map<NodeId, List<AssertionId>>,
    val target: Map<NodeId, List<AssertionId>>,
    val typeSource: Map<RelationEndpointKey, List<AssertionId>>,
    val typeTarget: Map<RelationEndpointKey, List<AssertionId>>,
) {
    val allIds: Set<AssertionId>
        get() = (source.values + target.values + typeSource.values + typeTarget.values).flatten().toSet()
}

private fun decodeRelationPostings(records: List<Json>): DecodedRelationPostings {
    val source = linkedMapOf<NodeId, List<AssertionId>>()
    val target = linkedMapOf<NodeId, List<AssertionId>>()
    val typeSource = linkedMapOf<RelationEndpointKey, List<AssertionId>>()
    val typeTarget = linkedMapOf<RelationEndpointKey, List<AssertionId>>()
    records.forEach { record ->
        val value = record.objectValue()
        val node = NodeId(value.required("nodeId").stringValue())
        val type = value.required("relationTypeId").nullableStringValue()?.let(::RelationTypeId)
        val ids = decodeIds(value.required("assertionIds"))
        when (value.required("kind").stringValue()) {
            "source" -> source[node] = ids
            "target" -> target[node] = ids
            "typeSource" -> typeSource[RelationEndpointKey(requireNotNull(type), node)] = ids
            "typeTarget" -> typeTarget[RelationEndpointKey(requireNotNull(type), node)] = ids
            else -> error("Unknown relation posting kind")
        }
    }
    return DecodedRelationPostings(source, target, typeSource, typeTarget)
}

private fun encodeIds(ids: Iterable<AssertionId>): Json = jsonArray(ids.map { jsonNumber(it.value) })
private fun decodeIds(json: Json): List<AssertionId> = json.arrayValue().map { AssertionId(it.intValue()) }

private fun validateAssertionReferences(graph: QueryableGraph, postingIds: List<AssertionId>) {
    val known = buildSet {
        graph.propertyAssertions.mapTo(this) { it.id }
        graph.relationAssertions.mapTo(this) { it.id }
        graph.textAssertions.mapTo(this) { it.id }
    }
    val unknown = postingIds.filterNot { it in known }.distinct()
    require(unknown.isEmpty()) { "Search index references unknown assertions: $unknown" }
}

private fun hashFiles(files: Map<String, String>): String {
    var hash = 0x811c9dc5u
    files.entries.sortedBy { it.key }.forEach { (name, contents) ->
        "$name\u0000$contents\u0000".forEach { character ->
            hash = (hash xor character.code.toUInt()) * 0x01000193u
        }
    }
    return hash.toString(16).padStart(8, '0')
}
