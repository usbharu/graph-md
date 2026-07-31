package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.model.GraphQuery
import dev.usbharu.graphmd.query.model.QueryResult

class ScanQueryExecutor : QueryExecutor {
    override suspend fun execute(
        graph: QueryableGraph,
        query: GraphQuery,
    ): QueryResult = QuerySemantics(ScanQueryDataSource(graph), query).execute()
}
