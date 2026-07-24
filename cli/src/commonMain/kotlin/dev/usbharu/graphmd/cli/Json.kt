package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.model.*

internal sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val value: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal fun jsonObject(vararg values: Pair<String, JsonValue>): JsonValue =
    JsonValue.Object(linkedMapOf(*values))

internal fun jsonArray(values: Iterable<JsonValue>): JsonValue = JsonValue.Array(values.toList())
internal fun jsonString(value: String): JsonValue = JsonValue.StringValue(value)
internal fun jsonNumber(value: Number): JsonValue = JsonValue.NumberValue(value.toString())
internal fun jsonBoolean(value: Boolean): JsonValue = JsonValue.BooleanValue(value)
internal fun jsonNullableString(value: String?): JsonValue = value?.let(::jsonString) ?: JsonValue.Null
internal fun <V> Map<String, V>.sortedByKey(): Map<String, V> =
    entries.sortedBy { it.key }.associateTo(linkedMapOf()) { it.key to it.value }

internal fun JsonValue.encode(): String = when (this) {
    is JsonValue.Object -> values.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        "${escapeJson(it.key)}:${it.value.encode()}"
    }
    is JsonValue.Array -> values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.encode() }
    is JsonValue.StringValue -> escapeJson(value)
    is JsonValue.NumberValue -> value
    is JsonValue.BooleanValue -> value.toString()
    JsonValue.Null -> "null"
}

private fun escapeJson(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

internal fun Diagnostic.toJson(): JsonValue = jsonObject(
    "severity" to jsonString(severity.name.lowercase()),
    "category" to jsonString(category.name),
    "message" to jsonString(message),
    "source" to (source?.toJson() ?: JsonValue.Null),
)

internal fun SourceInfo.toJson(): JsonValue = jsonObject(
    "path" to jsonString(path),
    "documentId" to jsonNullableString(documentId),
    "range" to (range?.let {
        jsonObject("start" to jsonNumber(it.start), "end" to jsonNumber(it.end))
    } ?: JsonValue.Null),
)

internal fun ValidTime.toJson(): JsonValue = jsonObject(
    "timeline" to jsonString(timeline),
    "from" to (from?.toJson() ?: JsonValue.Null),
    "to" to (to?.toJson() ?: JsonValue.Null),
)

private fun TimePoint.toJson(): JsonValue = jsonObject(
    "timecode" to jsonNumber(timecode),
    "value" to jsonNullableString(value),
)

private fun TemporalPoint.toJson(): JsonValue = jsonObject(
    "timecode" to jsonNumber(timecode),
    "value" to jsonNullableString(value),
    "timeline" to jsonNullableString(timeline),
)

internal fun NormalizedValue.toJson(): JsonValue = when (this) {
    is StringValue -> jsonString(value)
    is IntegerValue -> jsonNumber(value)
    is dev.usbharu.graphmd.core.model.NumberValue -> jsonNumber(value)
    is BooleanValue -> jsonBoolean(value)
    NullValue -> JsonValue.Null
    is TextValue -> JsonValue.Object(memberEntries.sortedByKey().mapValues { (_, entry) -> entry.toJson() })
    is ArrayValue -> jsonArray(elements.map {
        jsonObject(
            "value" to it.value.toJson(),
            "validTime" to jsonArray(it.validTime.map(ValidTime::toJson)),
        )
    })
    is ObjectValue -> JsonValue.Object(members.sortedByKey().mapValues { (_, entry) -> entry.toJson() })
    is InstantValue -> jsonObject(
        "timeline" to jsonNullableString(timeline),
        "value" to jsonNullableString(value),
        "timecode" to when (val code = timecode) {
            is NumberTimecode -> jsonNumber(code.value)
        },
    )
    is DurationValue -> jsonObject(
        "timeline" to jsonNullableString(timeline),
        "from" to (from?.toJson() ?: JsonValue.Null),
        "to" to (to?.toJson() ?: JsonValue.Null),
    )
}

internal fun NormalizedPropEntry.toJson(): JsonValue = jsonObject(
    "value" to value.toJson(),
    "validTime" to jsonArray(validTime.map(ValidTime::toJson)),
)

internal fun propertyEntriesToJson(entries: Map<String, List<NormalizedPropEntry>>): JsonValue =
    jsonArray(entries.sortedByKey().flatMap { (name, values) ->
        values.map { entry ->
            jsonObject(
                "name" to jsonString(name),
                "value" to entry.value.toJson(),
                "validTime" to jsonArray(entry.validTime.map(ValidTime::toJson)),
            )
        }
    })

internal fun ResolvedPropSchema.toJson(): JsonValue = jsonObject(
    "type" to jsonString(type.name),
    "required" to jsonBoolean(required),
    "timeline" to (timeline?.toJson() ?: JsonValue.Null),
    "timelines" to (timelines?.let { jsonArray(it.map(TimelineSelector::toJson)) } ?: JsonValue.Null),
    "items" to (items?.toJson() ?: JsonValue.Null),
)

private fun TimelineSelector.toJson(): JsonValue = when (this) {
    is TimelineSelector.Id -> jsonObject("kind" to jsonString("id"), "id" to jsonString(id))
    is TimelineSelector.Mapped -> jsonObject("kind" to jsonString("mapped"), "to" to jsonString(to))
}

internal fun TimelineMapping.toJson(): JsonValue = when (this) {
    is OffsetTimelineMapping -> jsonObject(
        "kind" to jsonString(kind),
        "to" to jsonNullableString(to),
        "from" to jsonNullableString(from),
        "offset" to jsonNumber(offset),
    )
}
