package dev.usbharu.graphmd.query.model

data class QueryBinding(
    val variables: Map<VariableId, NodeId>,
    val validTime: IntervalSet,
    val score: Double = 0.0,
    val matchedAssertionIds: Set<AssertionId> = emptySet(),
)

data class QueryMatch(
    val nodeId: NodeId,
    val binding: QueryBinding,
)

enum class QueryDiagnosticCode {
    UNKNOWN_TIMELINE,
    UNKNOWN_NODE_TYPE,
    UNKNOWN_RELATION_TYPE,
    INVALID_TEMPORAL_WINDOW,
    MISSING_TEMPORAL_EXPANSION_WINDOW,
}

data class QueryDiagnostic(
    val code: QueryDiagnosticCode,
    val message: String,
)

data class QueryResult(
    val matches: List<QueryMatch>,
    val diagnostics: List<QueryDiagnostic> = emptyList(),
) {
    val isSuccess: Boolean
        get() = diagnostics.isEmpty()
}
