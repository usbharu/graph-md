package dev.usbharu.graphmd.query.gmql

import dev.usbharu.graphmd.query.model.*

data class GmqlSourceRange(val start: Int, val end: Int)

enum class GmqlDiagnosticKind { SYNTAX, NAME, TYPE, TEMPORAL, LIMIT }

data class GmqlDiagnostic(
    val code: String,
    val message: String,
    val range: GmqlSourceRange? = null,
    val kind: GmqlDiagnosticKind,
)

sealed interface GmqlType {
    data object Any : GmqlType
    data object String : GmqlType
    data object Text : GmqlType
    data object Integer : GmqlType
    data object Decimal : GmqlType
    data object Boolean : GmqlType
    data object Null : GmqlType
    data object Node : GmqlType
    data object Relation : GmqlType
    data object TypeRef : GmqlType
    data object TemporalExtent : GmqlType
    data class Collection(val elementType: GmqlType) : GmqlType
    data class Temporal(val valueType: GmqlType) : GmqlType
}

sealed interface GmqlValue {
    data class StringValue(val value: String) : GmqlValue
    data class IntegerValue(val value: Long) : GmqlValue
    data class DecimalValue(val value: Double) : GmqlValue {
        init {
            require(value.isFinite()) { "A GMQL Decimal must be finite." }
        }
    }
    data class BooleanValue(val value: Boolean) : GmqlValue
    data object NullValue : GmqlValue
    data class NodeValue(val id: NodeId) : GmqlValue
    data class RelationValue(val id: AssertionId) : GmqlValue
    data class TypeRefValue(val name: String, val relation: Boolean = false) : GmqlValue
    data class CollectionValue(val values: List<GmqlValue>) : GmqlValue
    data class TemporalEntry(val value: GmqlValue, val validTime: IntervalSet)
    data class TemporalValue(val entries: List<TemporalEntry>) : GmqlValue
    data class TemporalExtentValue(val value: IntervalSet) : GmqlValue
}

enum class GmqlExecutionProfile { REFERENCE, STATIC_WEB, SERVER }

data class GmqlExecutionOptions(
    val profile: GmqlExecutionProfile = GmqlExecutionProfile.REFERENCE,
    val maxIntermediateBindings: Int = when (profile) {
        GmqlExecutionProfile.STATIC_WEB -> 100_000
        GmqlExecutionProfile.SERVER -> 1_000_000
        GmqlExecutionProfile.REFERENCE -> Int.MAX_VALUE
    },
    val maxResults: Int = when (profile) {
        GmqlExecutionProfile.STATIC_WEB -> 10_000
        GmqlExecutionProfile.SERVER -> 100_000
        GmqlExecutionProfile.REFERENCE -> Int.MAX_VALUE
    },
    val maxOperations: Long = when (profile) {
        GmqlExecutionProfile.STATIC_WEB -> 10_000_000
        GmqlExecutionProfile.SERVER -> 100_000_000
        GmqlExecutionProfile.REFERENCE -> Long.MAX_VALUE
    },
) {
    init {
        require(maxIntermediateBindings > 0)
        require(maxResults >= 0)
        require(maxOperations > 0)
    }
}

data class GmqlColumn(val name: String, val type: GmqlType)
data class GmqlRow(val values: List<GmqlValue>)
data class GmqlQueryResult(
    val columns: List<GmqlColumn> = emptyList(),
    val rows: List<GmqlRow> = emptyList(),
    val diagnostics: List<GmqlDiagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = diagnostics.isEmpty()
}

data class GmqlCompileResult(
    val query: GmqlCompiledQuery? = null,
    val diagnostics: List<GmqlDiagnostic> = emptyList(),
) {
    val isSuccess: Boolean get() = query != null && diagnostics.isEmpty()
}

@ConsistentCopyVisibility
data class GmqlCompiledQuery internal constructor(
    internal val ast: GmqlQueryAst,
    internal val expressionTypes: Map<GmqlExpression, GmqlType>,
    val parameterTypes: Map<String, GmqlType>,
    val columns: List<GmqlColumn>,
)

internal data class GmqlQueryAst(
    val patterns: List<GmqlPattern>,
    val where: GmqlExpression?,
    val valid: GmqlValid?,
    val distinct: Boolean,
    val returns: List<GmqlReturnItem>,
    val orderBy: List<GmqlOrderItem>,
    val offset: GmqlExpression?,
    val limit: GmqlExpression?,
)

internal data class GmqlPattern(val nodes: List<GmqlNodePattern>, val relations: List<GmqlRelationPattern>)
internal data class GmqlNodePattern(val variable: String?, val type: String?, val range: GmqlSourceRange)
internal data class GmqlRelationPattern(
    val variable: String?,
    val type: String?,
    val direction: RelationDirection,
    val range: GmqlSourceRange,
)

internal data class GmqlReturnItem(val expression: GmqlExpression, val alias: String?)
internal data class GmqlOrderItem(val expression: GmqlExpression, val ascending: Boolean)

internal enum class GmqlValidOperator { ANYTIME, AT, OVERLAPS, CONTAINS, DURING }
internal data class GmqlIntervalExpression(
    val start: GmqlExpression?,
    val end: GmqlExpression?,
    val includeStart: Boolean,
    val includeEnd: Boolean,
)
internal data class GmqlValid(
    val timeline: String?,
    val operator: GmqlValidOperator,
    val instant: GmqlExpression? = null,
    val interval: GmqlIntervalExpression? = null,
    val expansionWindow: GmqlIntervalExpression? = null,
    val range: GmqlSourceRange,
)

internal sealed interface GmqlExpression {
    val range: GmqlSourceRange
    data class Variable(val name: String, override val range: GmqlSourceRange) : GmqlExpression
    data class Parameter(val name: String, override val range: GmqlSourceRange) : GmqlExpression
    data class Literal(val value: GmqlValue, override val range: GmqlSourceRange) : GmqlExpression
    data class Property(
        val receiver: GmqlExpression,
        val name: String,
        override val range: GmqlSourceRange,
    ) : GmqlExpression
    data class Call(
        val name: String,
        val arguments: List<GmqlExpression>,
        override val range: GmqlSourceRange,
    ) : GmqlExpression
    data class Unary(
        val operator: String,
        val operand: GmqlExpression,
        override val range: GmqlSourceRange,
    ) : GmqlExpression
    data class Binary(
        val left: GmqlExpression,
        val operator: String,
        val right: GmqlExpression,
        override val range: GmqlSourceRange,
    ) : GmqlExpression
    data class IsTest(
        val operand: GmqlExpression,
        val missing: Boolean,
        val negated: Boolean,
        override val range: GmqlSourceRange,
    ) : GmqlExpression
}
