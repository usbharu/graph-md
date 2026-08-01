package dev.usbharu.graphmd.lsp

import dev.usbharu.graphmd.core.model.ResolvedPropSchema
import dev.usbharu.graphmd.query.gmql.GmqlDiagnostic
import dev.usbharu.graphmd.query.gmql.GmqlType
import dev.usbharu.graphmd.query.gmql.GmqlValue
import dev.usbharu.graphmd.query.model.IntervalBoundary
import dev.usbharu.graphmd.query.model.IntervalSet
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

interface GraphMdSearchService {
    @JsonRequest("graphmd/search")
    fun search(params: GraphMdSearchParams): CompletableFuture<GraphMdSearchResponse>

    @JsonRequest("graphmd/searchMetadata")
    fun searchMetadata(): CompletableFuture<GraphMdSearchMetadata>
}

data class GraphMdSearchParams(
    val query: String = "",
    val parameters: Map<String, String> = emptyMap(),
)

data class GraphMdSearchColumn(val name: String, val type: String)

data class GraphMdSearchDiagnostic(
    val code: String,
    val kind: String,
    val message: String,
    val start: Int? = null,
    val end: Int? = null,
)

data class GraphMdSearchLocation(
    val uri: String,
    val range: Range,
)

data class GraphMdSearchRow(
    val values: List<Any?>,
    val location: GraphMdSearchLocation? = null,
)

data class GraphMdSearchResponse(
    val columns: List<GraphMdSearchColumn> = emptyList(),
    val rows: List<GraphMdSearchRow> = emptyList(),
    val diagnostics: List<GraphMdSearchDiagnostic> = emptyList(),
)

data class GraphMdSearchProperty(
    val name: String,
    val type: String,
    val required: Boolean,
)

data class GraphMdSearchNodeType(
    val id: String,
    val properties: List<GraphMdSearchProperty>,
)

data class GraphMdSearchRelationType(
    val id: String,
    val sourceTypes: List<String>?,
    val targetTypes: List<String>?,
    val properties: List<GraphMdSearchProperty>,
)

data class GraphMdSearchMetadata(
    val nodeTypes: List<GraphMdSearchNodeType> = emptyList(),
    val relationTypes: List<GraphMdSearchRelationType> = emptyList(),
    val timelines: List<String> = emptyList(),
)

internal fun ResolvedPropSchema.toSearchProperty(name: String) =
    GraphMdSearchProperty(name, type.name, required)

internal fun GmqlType.wireName(): String = when (this) {
    GmqlType.Any -> "any"
    GmqlType.String -> "string"
    GmqlType.Text -> "text"
    GmqlType.Integer -> "integer"
    GmqlType.Decimal -> "decimal"
    GmqlType.Boolean -> "boolean"
    GmqlType.Null -> "null"
    GmqlType.Node -> "node"
    GmqlType.Relation -> "relation"
    GmqlType.TypeRef -> "type-ref"
    GmqlType.TemporalExtent -> "temporal-extent"
    is GmqlType.Collection -> "collection<${elementType.wireName()}>"
    is GmqlType.Temporal -> "temporal<${valueType.wireName()}>"
}

internal fun GmqlDiagnostic.toSearchDiagnostic() = GraphMdSearchDiagnostic(
    code = code,
    kind = kind.name.lowercase(),
    message = message,
    start = range?.start,
    end = range?.end,
)

internal fun GmqlValue.toWireValue(): Any? = when (this) {
    is GmqlValue.StringValue -> value
    is GmqlValue.IntegerValue -> value
    is GmqlValue.DecimalValue -> value
    is GmqlValue.BooleanValue -> value
    GmqlValue.NullValue -> null
    is GmqlValue.NodeValue -> mapOf("kind" to "node", "id" to id.value)
    is GmqlValue.RelationValue -> mapOf("kind" to "relation", "assertionId" to id.value)
    is GmqlValue.TypeRefValue -> mapOf(
        "kind" to if (relation) "relation-type" else "node-type",
        "name" to name,
    )
    is GmqlValue.CollectionValue -> values.map(GmqlValue::toWireValue)
    is GmqlValue.TemporalValue -> entries.map { entry ->
        mapOf("value" to entry.value.toWireValue(), "validTime" to entry.validTime.toWireValue())
    }
    is GmqlValue.TemporalExtentValue -> value.toWireValue()
}

private fun IntervalSet.toWireValue(): Any = if (isUniversal) {
    mapOf("universal" to true, "intervals" to emptyList<Any>())
} else {
    mapOf(
        "universal" to false,
        "intervals" to intervals.map { interval ->
            mapOf(
                "timeline" to interval.timelineId.value,
                "start" to interval.start.toWireValue(),
                "end" to interval.end.toWireValue(),
            )
        },
    )
}

private fun IntervalBoundary?.toWireValue(): Any? = this?.let {
    mapOf(
        "value" to mapOf(
            "numerator" to exactValue.numerator,
            "denominator" to exactValue.denominator,
        ),
        "inclusive" to inclusive,
    )
}
