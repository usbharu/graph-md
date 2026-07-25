package dev.usbharu.graphmd.query

import dev.usbharu.graphmd.core.model.GraphCompilationResult
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.index.SearchIndex
import dev.usbharu.graphmd.query.index.SearchIndexBuilder
import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.ir.QueryableGraphBuilder
import dev.usbharu.graphmd.query.model.GraphQuery
import dev.usbharu.graphmd.query.model.QueryResult
import dev.usbharu.graphmd.query.persistence.SearchIndexFormatOptions
import dev.usbharu.graphmd.query.persistence.StaticSearchBundle
import dev.usbharu.graphmd.query.persistence.StaticSearchIndexCodec
import dev.usbharu.graphmd.query.runtime.IndexedQueryExecutor
import dev.usbharu.graphmd.query.runtime.ScanQueryExecutor

class GraphSearchEngine private constructor(
    val index: SearchIndex,
) {
    val graph: QueryableGraph
        get() = index.graph

    private val executor = IndexedQueryExecutor(index)

    suspend fun search(query: GraphQuery): QueryResult = executor.execute(query)

    /**
     * Runs the intentionally slow semantic reference implementation.
     * Useful for differential tests and index diagnostics.
     */
    suspend fun scan(query: GraphQuery): QueryResult =
        ScanQueryExecutor().execute(graph, query)

    fun exportStatic(
        options: SearchIndexFormatOptions = SearchIndexFormatOptions(),
    ): StaticSearchBundle = StaticSearchIndexCodec.encode(index, options)

    companion object {
        fun build(
            compilation: GraphCompilationResult,
            sourceDocuments: List<SourceDocument> = emptyList(),
        ): GraphSearchEngine {
            val graph = QueryableGraphBuilder(sourceDocuments).build(compilation)
            return fromGraph(graph)
        }

        fun fromGraph(graph: QueryableGraph): GraphSearchEngine =
            GraphSearchEngine(SearchIndexBuilder().build(graph))

        fun fromIndex(index: SearchIndex): GraphSearchEngine = GraphSearchEngine(index)

        fun loadStatic(bundle: StaticSearchBundle): GraphSearchEngine =
            GraphSearchEngine(StaticSearchIndexCodec.decode(bundle))
    }
}
