package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.model.GraphQuery
import dev.usbharu.graphmd.query.model.QueryResult

interface QueryExecutor {
    suspend fun execute(
        graph: QueryableGraph,
        query: GraphQuery,
    ): QueryResult
}
