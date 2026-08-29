@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package dev.usbharu.graphmd.query

import dev.usbharu.graphmd.query.gmql.*
import dev.usbharu.graphmd.query.persistence.*
import kotlin.coroutines.*
import kotlin.js.JsExport
import kotlin.js.Promise

/** Narrow, JSON-based browser API that keeps Kotlin collection types out of JavaScript callers. */
@JsExport
class GraphMdWebSearchEngine internal constructor(
    private val engine: GraphSearchEngine,
) {
    fun queryGmql(query: String, parametersJson: String = "{}"): Promise<String> = promise {
        val parameters = decodeParameters(parametersJson)
        encodeResult(
            engine.queryGmql(
                query,
                parameters,
                GmqlExecutionOptions(profile = GmqlExecutionProfile.STATIC_WEB),
            ),
        )
    }
}

@JsExport
object GraphMdWebSearch {
    /** `shardsJson` is an object whose values are the unparsed JSON text of each shard. */
    fun load(manifestJson: String, shardsJson: String): GraphMdWebSearchEngine {
        val shards = parseJson(shardsJson).objectValue().mapValues { (_, value) -> value.stringValue() }
        return GraphMdWebSearchEngine(GraphSearchEngine.loadStatic(StaticSearchBundle(manifestJson, shards)))
    }
}

private fun decodeParameters(encoded: String): Map<String, GmqlValue> =
    parseJson(encoded).objectValue().mapValues { (name, value) ->
        when (value) {
            is Json.StringValue -> GmqlValue.StringValue(value.value)
            is Json.BooleanValue -> GmqlValue.BooleanValue(value.value)
            is Json.NumberValue -> if (INTEGER.matches(value.value)) {
                value.value.toLongOrNull()?.let(GmqlValue::IntegerValue)
                    ?: throw IllegalArgumentException("Parameter '$name' is outside the integer range")
            } else {
                value.value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(GmqlValue::DecimalValue)
                    ?: throw IllegalArgumentException("Parameter '$name' is not a finite number")
            }
            Json.Null -> GmqlValue.NullValue
            else -> throw IllegalArgumentException("Parameter '$name' must be a JSON scalar")
        }
    }

private fun encodeResult(result: GmqlQueryResult): String = jsonObject(
    "columns" to jsonArray(result.columns.map { column ->
        jsonObject("name" to jsonString(column.name), "type" to jsonString(column.type.toString()))
    }),
    "rows" to jsonArray(result.rows.map { row -> jsonArray(row.values.map(::encodeValue)) }),
    "diagnostics" to jsonArray(result.diagnostics.map { diagnostic ->
        jsonObject(
            "code" to jsonString(diagnostic.code),
            "message" to jsonString(diagnostic.message),
            "kind" to jsonString(diagnostic.kind.name),
            "start" to (diagnostic.range?.start?.let(::jsonNumber) ?: Json.Null),
            "end" to (diagnostic.range?.end?.let(::jsonNumber) ?: Json.Null),
        )
    }),
).encode()

private fun encodeValue(value: GmqlValue): Json = when (value) {
    is GmqlValue.StringValue -> jsonString(value.value)
    is GmqlValue.IntegerValue -> jsonNumber(value.value)
    is GmqlValue.DecimalValue -> jsonNumber(value.value)
    is GmqlValue.BooleanValue -> jsonBoolean(value.value)
    GmqlValue.NullValue -> Json.Null
    is GmqlValue.NodeValue -> jsonString(value.id.value)
    is GmqlValue.RelationValue -> jsonNumber(value.id.value)
    is GmqlValue.TypeRefValue -> jsonString(value.name)
    is GmqlValue.CollectionValue -> jsonArray(value.values.map(::encodeValue))
    is GmqlValue.TemporalValue -> jsonArray(value.entries.map { encodeValue(it.value) })
    is GmqlValue.TemporalExtentValue -> jsonString(value.value.toString())
}

private fun <T> promise(block: suspend () -> T): Promise<T> = Promise { resolve, reject ->
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) = result.fold(resolve, reject)
    })
}

private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
