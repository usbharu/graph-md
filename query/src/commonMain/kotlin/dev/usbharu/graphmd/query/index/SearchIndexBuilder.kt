package dev.usbharu.graphmd.query.index

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.text.TextAnalyzer

class SearchIndexBuilder {
    fun build(graph: QueryableGraph): SearchIndex {
        val nodeIdsByType = linkedMapOf<NodeTypeId, MutableList<NodeId>>()
        graph.nodes.forEach { node ->
            (node.ancestorTypeIds + node.typeId).forEach { typeId ->
                nodeIdsByType.getOrPut(typeId, ::mutableListOf) += node.id
            }
        }

        val exact = linkedMapOf<PropertyExactKey, MutableList<AssertionId>>()
        val values = linkedMapOf<PropertyId, MutableList<PropertyValuePosting>>()
        graph.propertyAssertions.forEach { assertion ->
            exact.getOrPut(
                PropertyExactKey(assertion.propertyId, normalizedValueKey(assertion.value)),
                ::mutableListOf,
            ) += assertion.id
            values.getOrPut(assertion.propertyId, ::mutableListOf) += PropertyValuePosting(
                assertion.id,
                propertySortKey(assertion.value),
            )
        }

        val bySource = linkedMapOf<NodeId, MutableList<AssertionId>>()
        val byTarget = linkedMapOf<NodeId, MutableList<AssertionId>>()
        val byTypeSource = linkedMapOf<RelationEndpointKey, MutableList<AssertionId>>()
        val byTypeTarget = linkedMapOf<RelationEndpointKey, MutableList<AssertionId>>()
        graph.relationAssertions.forEach { relation ->
            bySource.getOrPut(relation.sourceNodeId, ::mutableListOf) += relation.id
            byTarget.getOrPut(relation.targetNodeId, ::mutableListOf) += relation.id
            (relation.ancestorRelTypeIds + relation.relTypeId).forEach { typeId ->
                byTypeSource.getOrPut(RelationEndpointKey(typeId, relation.sourceNodeId), ::mutableListOf) += relation.id
                byTypeTarget.getOrPut(RelationEndpointKey(typeId, relation.targetNodeId), ::mutableListOf) += relation.id
            }
        }

        val byOwner = graph.textAssertions.groupByTo(
            linkedMapOf(),
            keySelector = { it.owner },
            valueTransform = { it.id },
        )

        val assertionTimes = buildMap {
            graph.propertyAssertions.forEach { put(it.id, it.validTime) }
            graph.relationAssertions.forEach { put(it.id, it.validTime) }
            graph.textAssertions.forEach { put(it.id, it.validTime) }
        }
        val universal = assertionTimes.filterValues { it.isUniversal }.keys
        val intervals = linkedMapOf<TimelineId, MutableList<IntervalEntry>>()
        assertionTimes.forEach { (id, set) ->
            set.intervals.forEach { interval ->
                intervals.getOrPut(interval.timelineId, ::mutableListOf) += IntervalEntry(
                    interval.start,
                    interval.end,
                    id,
                )
            }
        }
        val sortedIntervals = intervals.mapValues { (_, entries) ->
            entries.sortedWith(
                compareBy<IntervalEntry> { it.start != null }
                    .thenBy { it.start?.value }
                    .thenByDescending { it.start?.inclusive }
                    .thenBy { it.assertionId.value },
            )
        }

        val postings = linkedMapOf<String, MutableMap<AssertionId, MutableList<Int>>>()
        val documentLengths = linkedMapOf<AssertionId, Int>()
        graph.textAssertions.forEach { assertion ->
            val tokens = TextAnalyzer.analyze(assertion.text).tokens
            documentLengths[assertion.id] = tokens.size
            tokens.forEach { token ->
                postings.getOrPut(token.term, ::linkedMapOf)
                    .getOrPut(assertion.id, ::mutableListOf) += token.position
            }
        }
        val fullTextPostings = postings.mapValues { (_, documents) ->
            documents.map { (id, positions) ->
                TermPosting(id, positions.size, positions)
            }.sortedBy { it.assertionId.value }
        }

        return SearchIndex(
            graph = graph,
            nodeIdsByType = nodeIdsByType.mapValues { it.value.distinct().sortedBy(NodeId::value) },
            propertyExactPostings = exact.mapValues { it.value.sortedBy(AssertionId::value) },
            propertyValuePostings = values.mapValues { (_, postings) ->
                postings.sortedWith(compareBy<PropertyValuePosting> { it.sortKey }.thenBy { it.assertionId.value })
            },
            propertyIdsByOwnerAndPath = buildPropertyOwnerPathPostings(graph.propertyAssertions),
            relationIdsBySource = bySource.sortedPostingValues(),
            relationIdsByTarget = byTarget.sortedPostingValues(),
            relationIdsByTypeAndSource = byTypeSource.sortedPostingValues(),
            relationIdsByTypeAndTarget = byTypeTarget.sortedPostingValues(),
            textAssertionIdsByOwner = byOwner.mapValues { it.value.sortedBy(AssertionId::value) },
            intervalIndex = IntervalIndex(sortedIntervals, universal, assertionTimes),
            fullTextIndex = FullTextIndex(
                postingsByTerm = fullTextPostings,
                documentLengths = documentLengths,
                averageDocumentLength = documentLengths.values.average().takeUnless(Double::isNaN) ?: 0.0,
            ),
        )
    }

    private fun <K> Map<K, MutableList<AssertionId>>.sortedPostingValues(): Map<K, List<AssertionId>> =
        mapValues { it.value.distinct().sortedBy(AssertionId::value) }
}

fun normalizedValueKey(value: NormalizedValue): String = when (value) {
    is StringValue -> "s:${escapeKey(value.value)}"
    is TextValue -> "t:{" + value.memberEntries.entries.sortedBy { it.key }.joinToString(",") {
        "${escapeKey(it.key)}=${normalizedValueKey(it.value.value)}"
    } + "}"
    is IntegerValue -> "i:${value.value}"
    is NumberValue -> value.value.exactLongValueOrNull()?.let { "i:$it" }
        ?: "n:${canonicalNumber(value.value)}"
    is BooleanValue -> "b:${value.value}"
    NullValue -> "z:null"
    is ArrayValue -> "a:[" + value.elements.joinToString(",") { normalizedValueKey(it.value) } + "]"
    is ObjectValue -> "o:{" + value.members.entries.sortedBy { it.key }.joinToString(",") {
        "${escapeKey(it.key)}=${normalizedValueKey(it.value.value)}"
    } + "}"
    is InstantValue -> "i:${escapeKey(value.timeline.orEmpty())}:${escapeKey(value.value.orEmpty())}:${value.timecode}"
    is DurationValue -> "d:${escapeKey(value.timeline.orEmpty())}:${value.from}:${value.to}"
}

fun propertySortKey(value: NormalizedValue): PropertySortKey = when (value) {
    is IntegerValue -> PropertySortKey(0, integerValue = value.value)
    is NumberValue -> PropertySortKey(0, numericValue = value.value)
    is StringValue -> PropertySortKey(1, textValue = value.value)
    is BooleanValue -> PropertySortKey(2, textValue = value.value.toString())
    NullValue -> PropertySortKey(3)
    else -> PropertySortKey(4, textValue = normalizedValueKey(value))
}

private fun escapeKey(value: String): String =
    value.replace("\\", "\\\\").replace(":", "\\:").replace(",", "\\,")

private fun canonicalNumber(value: Double): Double = if (value == 0.0) 0.0 else value
