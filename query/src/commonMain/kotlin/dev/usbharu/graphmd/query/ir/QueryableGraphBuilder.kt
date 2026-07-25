package dev.usbharu.graphmd.query.ir

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.model.*

class QueryableGraphBuilder(
    sourceDocuments: List<SourceDocument> = emptyList(),
) {
    private val sourceTextByPath = sourceDocuments.associate { it.sourcePath to it.text }
    private lateinit var timelineCatalog: TimelineCatalog
    private var nextAssertionId = 0
    private val properties = mutableListOf<PropertyAssertion>()
    private val texts = mutableListOf<TextAssertion>()

    fun build(compilation: GraphCompilationResult): QueryableGraph {
        timelineCatalog = TimelineCatalog.from(compilation.timelines)
        nextAssertionId = 0
        properties.clear()
        texts.clear()

        val nodeTypes = compilation.nodeTypes.associateBy { it.id }
        val nodes = compilation.nodes.map { node ->
            val queryNode = QueryNode(
                id = NodeId(node.id),
                typeId = NodeTypeId(node.type),
                ancestorTypeIds = nodeTypes[node.type]?.ancestorIds.orEmpty().mapTo(linkedSetOf(), ::NodeTypeId),
                kind = node.kind,
                url = node.url,
                validTime = timelineCatalog.fromValidTimes(node.validTime),
                source = node.source,
            )
            buildNodeProperties(node)
            buildBodyText(node, queryNode.validTime)
            queryNode
        }

        var relations = compilation.relations.map { relation ->
            buildRelation(
                relation = relation,
                ancestorTypeIds = compilation.relTypes
                    .firstOrNull { it.id == relation.type }
                    ?.ancestorIds
                    .orEmpty()
                    .mapTo(linkedSetOf(), ::RelationTypeId),
            )
        }

        resolveFallbackProperties()
        val propertyById = properties.associateBy { it.id }
        relations = relations.map { relation ->
            relation.copy(properties = relation.properties.map { propertyById.getValue(it.id) })
        }
        properties.forEach(::addTextValues)

        return QueryableGraph(
            nodes = nodes,
            propertyAssertions = properties.toList(),
            relationAssertions = relations,
            textAssertions = texts.toList(),
            timelines = timelineCatalog.timelines,
            nodeTypeIds = compilation.nodeTypes.mapTo(linkedSetOf()) { NodeTypeId(it.id) },
            relationTypeIds = compilation.relTypes.mapTo(linkedSetOf()) { RelationTypeId(it.id) },
            nodeTypeSchemas = compilation.nodeTypes.associate { type ->
                NodeTypeId(type.id) to QueryNodeTypeSchema(
                    NodeTypeId(type.id),
                    type.props,
                    type.ancestorIds.mapTo(linkedSetOf(), ::NodeTypeId),
                )
            },
            relationTypeSchemas = compilation.relTypes.associate { type ->
                RelationTypeId(type.id) to QueryRelationTypeSchema(
                    RelationTypeId(type.id),
                    type.props,
                    type.from?.mapTo(linkedSetOf(), ::NodeTypeId),
                    type.to?.mapTo(linkedSetOf(), ::NodeTypeId),
                    type.ancestorIds.mapTo(linkedSetOf(), ::RelationTypeId),
                )
            },
        )
    }

    private fun buildNodeProperties(node: NormalizedNode) {
        node.propEntries.forEach { (name, entries) ->
            entries.forEach { entry ->
                addPropertyTree(
                    owner = AssertionOwner.Node(NodeId(node.id)),
                    propertyId = PropertyId(name),
                    path = PropertyPath(name),
                    entry = entry,
                    source = node.source,
                    stablePrefix = "node:${escape(node.id)}:property:${escape(name)}",
                )
            }
        }
    }

    private fun buildRelation(
        relation: NormalizedRelation,
        ancestorTypeIds: Set<RelationTypeId>,
    ): RelationAssertion {
        val relationId = nextId()
        val validTime = timelineCatalog.fromValidTimes(relation.validTime)
        val stableKey = StableAssertionKey(
            "relation:${escape(relation.from)}:${escape(relation.type)}:${escape(relation.to)}:" +
                "${sourceSignature(relation.source)}:${intervalSignature(validTime)}",
        )
        val owner = AssertionOwner.Relation(relationId)
        val relationProperties = buildList {
            relation.propEntries.forEach { (name, entries) ->
                entries.forEach { entry ->
                    add(
                        addPropertyTree(
                            owner = owner,
                            propertyId = PropertyId(name),
                            path = PropertyPath(name),
                            entry = entry,
                            source = relation.source,
                            stablePrefix = "${stableKey.value}:property:${escape(name)}",
                        ),
                    )
                }
            }
        }
        if (relation.sourceLabel.isNotBlank()) {
            texts += TextAssertion(
                id = nextId(),
                stableKey = StableAssertionKey("${stableKey.value}:label"),
                owner = owner,
                kind = TextKind.RELATION_LABEL,
                text = relation.sourceLabel,
                validTime = validTime,
                source = relation.source,
            )
        }
        return RelationAssertion(
            id = relationId,
            stableKey = stableKey,
            sourceNodeId = NodeId(relation.from),
            targetNodeId = NodeId(relation.to),
            relTypeId = RelationTypeId(relation.type),
            ancestorRelTypeIds = ancestorTypeIds,
            properties = relationProperties,
            label = relation.sourceLabel,
            validTime = validTime,
            source = relation.source,
        )
    }

    private fun addPropertyTree(
        owner: AssertionOwner,
        propertyId: PropertyId,
        path: PropertyPath,
        entry: NormalizedPropEntry,
        source: SourceInfo,
        stablePrefix: String,
    ): PropertyAssertion {
        val validTime = timelineCatalog.fromValidTimes(entry.validTime)
        val assertion = PropertyAssertion(
            id = nextId(),
            stableKey = StableAssertionKey(
                "$stablePrefix:${path.segments.joinToString("/") { escape(it) }}:" +
                    "${valueSignature(entry.value)}:${intervalSignature(validTime)}",
            ),
            owner = owner,
            propertyId = propertyId,
            path = path,
            value = entry.value,
            validTime = validTime,
            source = source,
            isFallback = entry.isFallback,
        )
        properties += assertion

        when (val value = entry.value) {
            is TextValue -> value.memberEntries.forEach { (name, member) ->
                addPropertyTree(
                    owner,
                    propertyId,
                    PropertyPath(path.segments + name),
                    inheritTime(member, entry.validTime),
                    source,
                    "$stablePrefix:text:${escape(name)}",
                )
            }
            is ObjectValue -> value.members.forEach { (name, member) ->
                addPropertyTree(
                    owner,
                    propertyId,
                    PropertyPath(path.segments + name),
                    inheritTime(member, entry.validTime),
                    source,
                    "$stablePrefix:object:${escape(name)}",
                )
            }
            is ArrayValue -> value.elements.forEachIndexed { index, element ->
                addPropertyTree(
                    owner,
                    propertyId,
                    PropertyPath(path.segments + index.toString()),
                    NormalizedPropEntry(
                        element.value,
                        element.validTime.ifEmpty { entry.validTime },
                        element.isFallback || element.validTime.isEmpty() && entry.isFallback,
                    ),
                    source,
                    "$stablePrefix:array:$index",
                )
            }
            else -> Unit
        }
        return assertion
    }

    private fun inheritTime(entry: NormalizedPropEntry, inherited: List<ValidTime>): NormalizedPropEntry =
        if (entry.validTime.isEmpty()) entry.copy(validTime = inherited) else entry

    private fun addTextValues(assertion: PropertyAssertion) {
        val value = assertion.value
        val text = when (value) {
            is StringValue -> value.value
            is TextValue, is ObjectValue, is ArrayValue -> null
            else -> null
        } ?: return
        if (text.isBlank()) return
        texts += TextAssertion(
            id = nextId(),
            stableKey = StableAssertionKey("${assertion.stableKey.value}:text"),
            owner = assertion.owner,
            kind = TextKind.PROPERTY_VALUE,
            text = text,
            validTime = assertion.validTime,
            source = assertion.source,
            propertyPath = assertion.path,
        )
    }

    private fun resolveFallbackProperties() {
        val replacements = properties.groupBy { it.owner to it.path }.values.flatMap { group ->
            val timed = group.filterNot { it.isFallback }
            val timedUnion = timed.fold(IntervalSet.empty()) { result, assertion -> result union assertion.validTime }
            group.map { assertion ->
                if (!assertion.isFallback) {
                    assertion
                } else {
                    // The compiler has already inherited the nearest owner/parent extent.
                    // Keeping that scope is essential for fallback members inside a timed
                    // text/object value.
                    val ownerTime = assertion.validTime
                    val fallbackBase = when {
                        !ownerTime.isUniversal -> ownerTime
                        timedUnion.isEmpty -> IntervalSet.universal()
                        else -> IntervalSet.of(
                            timedUnion.intervals.map { TemporalInterval(it.timelineId) }.distinct(),
                        )
                    }
                    assertion.copy(
                        validTime = if (timedUnion.isEmpty) fallbackBase else fallbackBase.subtract(timedUnion),
                    )
                }
            }
        }.associateBy { it.id }
        properties.indices.forEach { index ->
            properties[index] = replacements.getValue(properties[index].id)
        }
    }

    private fun buildBodyText(node: NormalizedNode, nodeValidTime: IntervalSet) {
        val sourceText = sourceTextByPath[node.source.path] ?: return
        MarkdownTextExtractor.extract(sourceText).forEachIndexed { index, fragment ->
            texts += TextAssertion(
                id = nextId(),
                stableKey = StableAssertionKey(
                    "node:${escape(node.id)}:text:${fragment.kind.name}:$index:" +
                        "${fragment.range.start}:${fragment.range.end}",
                ),
                owner = AssertionOwner.Node(NodeId(node.id)),
                kind = fragment.kind,
                text = fragment.text,
                validTime = nodeValidTime,
                source = node.source.copy(range = fragment.range),
                sourceRange = fragment.range,
            )
        }
    }

    private fun nextId(): AssertionId = AssertionId(nextAssertionId++)

    private fun sourceSignature(source: SourceInfo): String =
        "${escape(source.path)}:${source.range?.start ?: -1}:${source.range?.end ?: -1}"

    private fun intervalSignature(intervalSet: IntervalSet): String = when {
        intervalSet.isUniversal -> "*"
        else -> intervalSet.intervals.joinToString("|") {
            "${escape(it.timelineId.value)}:" +
                "${it.start?.value ?: "*"}:${it.start?.inclusive ?: false}:" +
                "${it.end?.value ?: "*"}:${it.end?.inclusive ?: false}"
        }
    }

    private fun valueSignature(value: NormalizedValue): String = when (value) {
        is StringValue -> "s:${escape(value.value)}"
        is TextValue -> "t:${value.memberEntries.entries.sortedBy { it.key }.joinToString(",") {
            "${escape(it.key)}=${valueSignature(it.value.value)}"
        }}"
        is IntegerValue -> "i:${value.value}"
        is NumberValue -> "n:${value.value}"
        is BooleanValue -> "b:${value.value}"
        NullValue -> "null"
        is ArrayValue -> "a:[${value.elements.joinToString(",") { valueSignature(it.value) }}]"
        is ObjectValue -> "o:{${value.members.entries.sortedBy { it.key }.joinToString(",") {
            "${escape(it.key)}=${valueSignature(it.value.value)}"
        }}}"
        is InstantValue -> "instant:${escape(value.timeline.orEmpty())}:${value.timecode}"
        is DurationValue -> "duration:${escape(value.timeline.orEmpty())}:${value.from}:${value.to}"
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace(":", "\\:").replace("|", "\\|")
}
