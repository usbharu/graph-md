package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.query.index.*
import dev.usbharu.graphmd.query.ir.*
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.text.Bm25Scorer
import dev.usbharu.graphmd.query.text.TextAnalyzer

class IndexedQueryExecutor(
    private val index: SearchIndex,
) : QueryExecutor {
    private val scorer = Bm25Scorer.from(index.fullTextIndex)

    override suspend fun execute(
        graph: QueryableGraph,
        query: GraphQuery,
    ): QueryResult {
        require(graph == index.graph) { "The supplied graph does not match this search index" }
        return execute(query)
    }

    suspend fun execute(query: GraphQuery): QueryResult =
        QuerySemantics(IndexedQueryDataSource(index, query, scorer), query).execute()
}

private class IndexedQueryDataSource(
    private val index: SearchIndex,
    query: GraphQuery,
    private val scorer: Bm25Scorer,
) : QueryDataSource {
    override val graph: QueryableGraph = index.graph
    private val nodeById = graph.nodes.associateBy { it.id }
    private val propertyById = graph.propertyAssertions.associateBy { it.id }
    private val relationById = graph.relationAssertions.associateBy { it.id }
    private val textById = graph.textAssertions.associateBy { it.id }
    private val expansionWindow = query.expansionWindow?.let(graph.timelineCatalog::expansionWindow)
    private val temporalCandidates: Set<AssertionId>? = query.temporalWindow?.let { window ->
        if (window.timelineId !in graph.timelineCatalog) {
            emptySet()
        } else {
            index.intervalIndex.candidates(
                window.toIntervalSet(graph.timelineCatalog, expansionWindow),
                query.temporalOperator,
            )
        }
    }

    override fun rootNodes(pattern: NodePattern): List<QueryNode> {
        val ids = when {
            pattern.id != null -> listOf(pattern.id)
            pattern.typeId != null -> index.nodeIdsByType[pattern.typeId].orEmpty()
            else -> graph.nodes.map { it.id }
        }
        return ids.distinct().mapNotNull(nodeById::get).filter { it.matches(pattern) }
    }

    override fun propertyAssertions(
        owner: AssertionOwner,
        predicate: PropertyPredicate,
    ): List<PropertyAssertion> {
        val ids = propertyCandidateIds(predicate)
        return ids.asSequence()
            .filterTemporally()
            .mapNotNull(propertyById::get)
            .filter { it.owner == owner && it.path == predicate.path && valueMatches(it.value, predicate) }
            .toList()
    }

    private fun propertyCandidateIds(predicate: PropertyPredicate): List<AssertionId> {
        if (predicate.operator == ValueOperator.EQUALS) {
            return index.propertyExactPostings[
                PropertyExactKey(predicate.path.propertyId, normalizedValueKey(predicate.value))
            ].orEmpty()
        }

        val postings = index.propertyValuePostings[predicate.path.propertyId].orEmpty()
        if (predicate.operator == ValueOperator.NOT_EQUALS || predicate.operator == ValueOperator.CONTAINS) {
            return postings.map { it.assertionId }
        }

        val key = propertySortKey(predicate.value)
        val lower = postings.lowerBound(key)
        val upper = postings.upperBound(key)
        val candidates = when (predicate.operator) {
            ValueOperator.LESS_THAN -> postings.subList(0, lower)
            ValueOperator.LESS_THAN_OR_EQUALS -> postings.subList(0, upper)
            ValueOperator.GREATER_THAN -> postings.subList(upper, postings.size)
            ValueOperator.GREATER_THAN_OR_EQUALS -> postings.subList(lower, postings.size)
            ValueOperator.EQUALS, ValueOperator.NOT_EQUALS, ValueOperator.CONTAINS -> postings
        }
        return candidates.map { it.assertionId }
    }

    override fun relationAssertions(
        nodeId: NodeId,
        pattern: RelationPattern,
    ): List<RelationAssertion> {
        fun outgoing(): List<AssertionId> = pattern.typeId?.let {
            index.relationIdsByTypeAndSource[RelationEndpointKey(it, nodeId)]
        } ?: index.relationIdsBySource[nodeId].orEmpty()
        fun incoming(): List<AssertionId> = pattern.typeId?.let {
            index.relationIdsByTypeAndTarget[RelationEndpointKey(it, nodeId)]
        } ?: index.relationIdsByTarget[nodeId].orEmpty()
        val ids = when (pattern.direction) {
            RelationDirection.OUTGOING -> outgoing()
            RelationDirection.INCOMING -> incoming()
            RelationDirection.EITHER -> outgoing() + incoming()
        }
        return ids.asSequence()
            .distinct()
            .filterTemporally()
            .mapNotNull(relationById::get)
            .filter { it.touches(nodeId, pattern.direction) && it.matchesType(pattern) }
            .toList()
    }

    override fun textAssertions(
        owner: AssertionOwner,
        predicate: TextPredicate,
    ): List<ScoredTextAssertion> {
        val ownerIds = index.textAssertionIdsByOwner[owner].orEmpty().toSet()
        val analyzed = TextAnalyzer.analyze(predicate.text, predicate.caseSensitive)
        val postingIds = when {
            predicate.caseSensitive ||
                predicate.mode == TextMatchMode.CONTAINS ||
                predicate.mode == TextMatchMode.PHRASE -> ownerIds
            predicate.mode == TextMatchMode.ALL_TERMS -> {
                analyzed.terms.map { term ->
                    index.fullTextIndex.postingsByTerm[term].orEmpty().mapTo(linkedSetOf()) { it.assertionId }
                }.reduceOrNull(Set<AssertionId>::intersect).orEmpty()
            }
            else -> analyzed.terms.flatMapTo(linkedSetOf()) { term ->
                index.fullTextIndex.postingsByTerm[term].orEmpty().map { it.assertionId }
            }
        }
        return postingIds.asSequence()
            .filter { it in ownerIds }
            .filterTemporally()
            .mapNotNull(textById::get)
            .filter { TextAnalyzer.matches(it.text, predicate) }
            .map { ScoredTextAssertion(it, scorer.score(it.id, predicate)) }
            .toList()
    }

    private fun Sequence<AssertionId>.filterTemporally(): Sequence<AssertionId> =
        temporalCandidates?.let { candidates -> filter { it in candidates } } ?: this
}

private fun List<PropertyValuePosting>.lowerBound(key: PropertySortKey): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].sortKey < key) low = middle + 1 else high = middle
    }
    return low
}

private fun List<PropertyValuePosting>.upperBound(key: PropertySortKey): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].sortKey <= key) low = middle + 1 else high = middle
    }
    return low
}
