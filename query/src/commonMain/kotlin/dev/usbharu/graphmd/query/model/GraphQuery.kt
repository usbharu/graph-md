package dev.usbharu.graphmd.query.model

import dev.usbharu.graphmd.core.model.NormalizedValue

sealed interface TemporalWindow {
    val timelineId: TimelineId

    data class At(
        override val timelineId: TimelineId,
        val instant: Double,
    ) : TemporalWindow {
        init {
            require(instant.isFinite()) { "A query instant must be finite" }
        }
    }

    /**
     * A half-open range: [start, endExclusive).
     */
    data class Range(
        override val timelineId: TimelineId,
        val start: Double? = null,
        val endExclusive: Double? = null,
    ) : TemporalWindow {
        init {
            require(start?.isFinite() != false && endExclusive?.isFinite() != false) {
                "Query range boundaries must be finite"
            }
        }
    }

    /**
     * A GraphMD-style closed range: [start, endInclusive].
     */
    data class ClosedRange(
        override val timelineId: TimelineId,
        val start: Double? = null,
        val endInclusive: Double? = null,
    ) : TemporalWindow {
        init {
            require(start?.isFinite() != false && endInclusive?.isFinite() != false) {
                "Query range boundaries must be finite"
            }
        }
    }

    fun toIntervalSet(catalog: TimelineCatalog): IntervalSet {
        val interval = when (this) {
            is At -> catalog.assertedInterval(
                timelineId,
                IntervalBoundary(instant, inclusive = true),
                IntervalBoundary(instant, inclusive = true),
            )
            is Range -> {
                if (start != null && endExclusive != null && start >= endExclusive) return IntervalSet.empty()
                catalog.assertedInterval(
                    timelineId,
                    start?.let { IntervalBoundary(it, inclusive = true) },
                    endExclusive?.let { IntervalBoundary(it, inclusive = false) },
                )
            }
            is ClosedRange -> {
                if (start != null && endInclusive != null && start > endInclusive) return IntervalSet.empty()
                catalog.assertedInterval(
                    timelineId,
                    start?.let { IntervalBoundary(it, inclusive = true) },
                    endInclusive?.let { IntervalBoundary(it, inclusive = true) },
                )
            }
        }
        return IntervalSet.of(interval)
    }
}

enum class TemporalOperator {
    AT,
    OVERLAPS,
    ASSERTION_CONTAINS_QUERY,
    QUERY_CONTAINS_ASSERTION,
}

data class NodePattern(
    val id: NodeId? = null,
    val typeId: NodeTypeId? = null,
    val includeDerivedTypes: Boolean = true,
)

enum class ValueOperator {
    EQUALS,
    NOT_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    CONTAINS,
}

data class PropertyPredicate(
    val path: PropertyPath,
    val operator: ValueOperator,
    val value: NormalizedValue,
)

enum class RelationDirection {
    OUTGOING,
    INCOMING,
    EITHER,
}

enum class TextMatchMode {
    CONTAINS,
    PHRASE,
    ALL_TERMS,
    ANY_TERM,
}

data class TextPredicate(
    val text: String,
    val mode: TextMatchMode = TextMatchMode.CONTAINS,
    val caseSensitive: Boolean = false,
) {
    init {
        require(text.isNotBlank()) { "Text predicates must not be blank" }
    }
}

data class RelationPattern(
    val typeId: RelationTypeId? = null,
    val includeDerivedTypes: Boolean = true,
    val direction: RelationDirection = RelationDirection.OUTGOING,
    val target: NodePattern = NodePattern(),
    val targetVariable: VariableId? = null,
    val relationProperties: List<PropertyPredicate> = emptyList(),
    val label: TextPredicate? = null,
    val targetExpression: GraphQueryExpression = GraphQueryExpression.MatchAll,
)

sealed interface GraphQueryExpression {
    data object MatchAll : GraphQueryExpression
    data object MatchNone : GraphQueryExpression

    data class And(
        val operands: List<GraphQueryExpression>,
    ) : GraphQueryExpression

    data class Or(
        val operands: List<GraphQueryExpression>,
    ) : GraphQueryExpression

    data class Not(
        val operand: GraphQueryExpression,
    ) : GraphQueryExpression

    data class Property(
        val predicate: PropertyPredicate,
    ) : GraphQueryExpression

    data class Relation(
        val pattern: RelationPattern,
    ) : GraphQueryExpression

    data class Text(
        val predicate: TextPredicate,
    ) : GraphQueryExpression
}

data class GraphQuery(
    val root: NodePattern = NodePattern(),
    val rootVariable: VariableId = VariableId("root"),
    val expression: GraphQueryExpression = GraphQueryExpression.MatchAll,
    val temporalWindow: TemporalWindow? = null,
    val temporalOperator: TemporalOperator = if (temporalWindow is TemporalWindow.At) {
        TemporalOperator.AT
    } else {
        TemporalOperator.OVERLAPS
    },
    val offset: Int = 0,
    val limit: Int = 100,
) {
    init {
        require(offset >= 0) { "Query offset must not be negative" }
        require(limit > 0) { "Query limit must be positive" }
    }
}
