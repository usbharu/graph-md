package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.query.index.*
import dev.usbharu.graphmd.query.ir.*
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.text.Bm25Scorer
import dev.usbharu.graphmd.query.text.TextAnalyzer

class IndexedQueryExecutor(
    private val index: SearchIndex,
) : QueryExecutor {
    override suspend fun execute(
        graph: QueryableGraph,
        query: GraphQuery,
    ): QueryResult {
        require(graph == index.graph) { "The supplied graph does not match this search index" }
        return execute(query)
    }

    suspend fun execute(query: GraphQuery): QueryResult =
        QuerySemantics(IndexedQueryDataSource(index, query), query).execute()
}

private class IndexedQueryDataSource(
    private val index: SearchIndex,
    query: GraphQuery,
) : QueryDataSource {
    override val graph: QueryableGraph = index.graph
    private val nodeById = graph.nodes.associateBy { it.id }
    private val propertyById = graph.propertyAssertions.associateBy { it.id }
    private val relationById = graph.relationAssertions.associateBy { it.id }
    private val textById = graph.textAssertions.associateBy { it.id }
    private val scorer = Bm25Scorer.from(graph.textAssertions)
    private val temporalCandidates: Set<AssertionId>? = query.temporalWindow?.let { window ->
        if (window.timelineId !in graph.timelineCatalog) {
            emptySet()
        } else {
            index.intervalIndex.candidates(
                window.toIntervalSet(graph.timelineCatalog),
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
        val ids = if (predicate.operator == ValueOperator.EQUALS) {
            index.propertyExactPostings[
                PropertyExactKey(predicate.path.propertyId, normalizedValueKey(predicate.value))
            ].orEmpty()
        } else {
            index.propertyValuePostings[predicate.path.propertyId].orEmpty().map { it.assertionId }
        }
        return ids.asSequence()
            .filterTemporally()
            .mapNotNull(propertyById::get)
            .filter { it.owner == owner && it.path == predicate.path && valueMatches(it.value, predicate) }
            .toList()
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
