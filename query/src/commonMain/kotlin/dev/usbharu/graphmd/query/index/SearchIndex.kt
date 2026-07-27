package dev.usbharu.graphmd.query.index

import dev.usbharu.graphmd.query.ir.AssertionOwner
import dev.usbharu.graphmd.query.ir.PropertyAssertion
import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.model.*

data class PropertyExactKey(
    val propertyId: PropertyId,
    val valueKey: String,
)

data class PropertyOwnerPathKey(
    val owner: AssertionOwner,
    val path: PropertyPath,
)

data class PropertySortKey(
    val typeRank: Int,
    val numericValue: Double? = null,
    val integerValue: Long? = null,
    val textValue: String? = null,
) : Comparable<PropertySortKey> {
    override fun compareTo(other: PropertySortKey): Int {
        val rankComparison = typeRank.compareTo(other.typeRank)
        if (rankComparison != 0) return rankComparison
        val numberComparison = when {
            integerValue != null && other.integerValue != null -> integerValue.compareTo(other.integerValue)
            integerValue != null && other.numericValue != null -> compareLongToDouble(integerValue, other.numericValue)
            numericValue != null && other.integerValue != null -> -compareLongToDouble(other.integerValue, numericValue)
            numericValue != null && other.numericValue != null -> numericValue.compareTo(other.numericValue)
            integerValue == null && numericValue == null && other.integerValue == null && other.numericValue == null -> 0
            integerValue == null && numericValue == null -> -1
            else -> 1
        }
        if (numberComparison != 0) return numberComparison
        return (textValue ?: "").compareTo(other.textValue ?: "")
    }
}

data class PropertyValuePosting(
    val assertionId: AssertionId,
    val sortKey: PropertySortKey,
)

data class RelationEndpointKey(
    val relationTypeId: RelationTypeId,
    val nodeId: NodeId,
)

data class IntervalEntry(
    val start: IntervalBoundary?,
    val end: IntervalBoundary?,
    val assertionId: AssertionId,
)

data class IntervalIndex(
    val entriesByTimeline: Map<TimelineId, List<IntervalEntry>>,
    val universalAssertionIds: Set<AssertionId>,
    val assertionTimes: Map<AssertionId, IntervalSet>,
) {
    fun candidates(
        window: IntervalSet,
        operator: TemporalOperator = TemporalOperator.OVERLAPS,
    ): Set<AssertionId> {
        if (window.isUniversal) return assertionTimes.keys
        if (window.isEmpty) return emptySet()
        val coarse = buildSet {
            addAll(universalAssertionIds)
            window.intervals.forEach { queryInterval ->
                entriesByTimeline[queryInterval.timelineId].orEmpty()
                    .asSequence()
                    .takeWhile { entry ->
                        val queryEnd = queryInterval.end
                        val entryStart = entry.start
                        queryEnd == null ||
                            entryStart == null ||
                            entryStart.value < queryEnd.value ||
                            entryStart.value == queryEnd.value && entryStart.inclusive && queryEnd.inclusive
                    }
                    .forEach { add(it.assertionId) }
            }
        }
        return coarse.filterTo(linkedSetOf()) { id ->
            val asserted = assertionTimes.getValue(id)
            asserted.isUniversal || when (operator) {
                TemporalOperator.AT, TemporalOperator.OVERLAPS -> !(asserted intersect window).isEmpty
                TemporalOperator.ASSERTION_CONTAINS_QUERY -> asserted.contains(window)
                TemporalOperator.QUERY_CONTAINS_ASSERTION -> window.contains(asserted)
            }
        }
    }
}

data class TermPosting(
    val assertionId: AssertionId,
    val termFrequency: Int,
    val positions: List<Int>,
)

data class FullTextIndex(
    val postingsByTerm: Map<String, List<TermPosting>>,
    val documentLengths: Map<AssertionId, Int>,
    val averageDocumentLength: Double,
)

data class SearchIndex(
    val graph: QueryableGraph,
    val nodeIdsByType: Map<NodeTypeId, List<NodeId>>,
    val propertyExactPostings: Map<PropertyExactKey, List<AssertionId>>,
    val propertyValuePostings: Map<PropertyId, List<PropertyValuePosting>>,
    val propertyIdsByOwnerAndPath: Map<PropertyOwnerPathKey, List<AssertionId>>,
    val relationIdsBySource: Map<NodeId, List<AssertionId>>,
    val relationIdsByTarget: Map<NodeId, List<AssertionId>>,
    val relationIdsByTypeAndSource: Map<RelationEndpointKey, List<AssertionId>>,
    val relationIdsByTypeAndTarget: Map<RelationEndpointKey, List<AssertionId>>,
    val textAssertionIdsByOwner: Map<dev.usbharu.graphmd.query.ir.AssertionOwner, List<AssertionId>>,
    val intervalIndex: IntervalIndex,
    val fullTextIndex: FullTextIndex,
)

internal fun buildPropertyOwnerPathPostings(
    assertions: Iterable<PropertyAssertion>,
): Map<PropertyOwnerPathKey, List<AssertionId>> =
    assertions.groupByTo(
        linkedMapOf(),
        keySelector = { PropertyOwnerPathKey(it.owner, it.path) },
        valueTransform = { it.id },
    ).mapValues { (_, ids) -> ids.distinct().sortedBy(AssertionId::value) }
