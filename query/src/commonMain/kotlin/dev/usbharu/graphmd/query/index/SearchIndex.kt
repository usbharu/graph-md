package dev.usbharu.graphmd.query.index

import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.model.*

data class PropertyExactKey(
    val propertyId: PropertyId,
    val valueKey: String,
)

data class PropertySortKey(
    val typeRank: Int,
    val numericValue: Double? = null,
    val textValue: String? = null,
) : Comparable<PropertySortKey> {
    override fun compareTo(other: PropertySortKey): Int {
        val rankComparison = typeRank.compareTo(other.typeRank)
        if (rankComparison != 0) return rankComparison
        val numberComparison = when {
            numericValue == null && other.numericValue == null -> 0
            numericValue == null -> -1
            other.numericValue == null -> 1
            else -> numericValue.compareTo(other.numericValue)
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
    val relationIdsBySource: Map<NodeId, List<AssertionId>>,
    val relationIdsByTarget: Map<NodeId, List<AssertionId>>,
    val relationIdsByTypeAndSource: Map<RelationEndpointKey, List<AssertionId>>,
    val relationIdsByTypeAndTarget: Map<RelationEndpointKey, List<AssertionId>>,
    val textAssertionIdsByOwner: Map<dev.usbharu.graphmd.query.ir.AssertionOwner, List<AssertionId>>,
    val intervalIndex: IntervalIndex,
    val fullTextIndex: FullTextIndex,
)
