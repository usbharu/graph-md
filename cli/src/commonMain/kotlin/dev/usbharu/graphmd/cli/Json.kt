package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.gmql.*
import dev.usbharu.graphmd.query.model.*

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
internal fun jsonNumber(value: Number): JsonValue = JsonValue.NumberValue(
    when (value) {
        is Double -> value.graphNumberText()
        is Float -> value.toDouble().graphNumberText()
        else -> value.toString()
    },
)
internal fun jsonBoolean(value: Boolean): JsonValue = JsonValue.BooleanValue(value)
internal fun jsonNullableString(value: String?): JsonValue = value?.let(::jsonString) ?: JsonValue.Null
internal fun Double.graphNumberText(): String {
    val rendered = toString()
    return if (isFinite() && this % 1.0 == 0.0 && '.' !in rendered && 'e' !in rendered.lowercase()) {
        "$rendered.0"
    } else {
        rendered
    }
}
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
    "coordinate" to coordinate.toJson(),
    "value" to jsonNullableString(value),
)

private fun TemporalPoint.toJson(): JsonValue = jsonObject(
    "coordinate" to coordinate.toJson(),
    "value" to jsonNullableString(value),
    "timeline" to jsonNullableString(timeline),
)

internal fun RawValue.toJson(): JsonValue = when (this) {
    is RawString -> jsonString(value)
    is RawInteger -> jsonNumber(value)
    is RawNumber -> jsonNumber(value)
    is RawBoolean -> jsonBoolean(value)
    RawNull -> JsonValue.Null
    is RawArray -> jsonArray(values.map(RawValue::toJson))
    is RawObject -> JsonValue.Object(values.sortedByKey().mapValues { (_, value) -> value.toJson() })
}

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
            "fallback" to jsonBoolean(it.isFallback),
        )
    })
    is ObjectValue -> JsonValue.Object(members.sortedByKey().mapValues { (_, entry) -> entry.toJson() })
    is InstantValue -> jsonObject(
        "timeline" to jsonNullableString(timeline),
        "value" to jsonNullableString(value),
        "coordinate" to coordinate.toJson(),
    )
    is DurationValue -> jsonObject(
        "timeline" to jsonNullableString(timeline),
        "from" to (from?.toJson() ?: JsonValue.Null),
        "to" to (to?.toJson() ?: JsonValue.Null),
    )
}

internal fun ExactRational.toJson(): JsonValue = jsonObject(
    "numerator" to jsonNumber(numerator),
    "denominator" to jsonNumber(denominator),
)

internal fun TemporalCoordinate.toJson(): JsonValue = when (this) {
    is TemporalCoordinate.Rational -> value.toJson()
    is TemporalCoordinate.CalendarDate -> jsonString(
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}",
    )
    is TemporalCoordinate.CalendarPattern -> jsonString(calendarPatternText(fields))
    is TemporalCoordinate.EraDate -> jsonString(
        "$era $year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}",
    )
    is TemporalCoordinate.FrameIndex -> jsonObject(
        "numerator" to jsonNumber(value),
        "denominator" to jsonNumber(1),
    )
    is TemporalCoordinate.Timecode -> jsonString(
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:" +
            "${seconds.toString().padStart(2, '0')}:${frames.toString().padStart(2, '0')}",
    )
    is TemporalCoordinate.Label -> jsonString(value)
}

internal fun NormalizedPropEntry.toJson(): JsonValue = jsonObject(
    "value" to value.toJson(),
    "validTime" to jsonArray(validTime.map(ValidTime::toJson)),
    "fallback" to jsonBoolean(isFallback),
)

internal fun propertyEntriesToJson(
    entries: Map<String, List<NormalizedPropEntry>>,
    ownerId: String? = null,
    ownerVisibility: String? = null,
): JsonValue =
    jsonArray(entries.sortedByKey().flatMap { (name, values) ->
        values.map { entry ->
            JsonValue.Object(
                linkedMapOf<String, JsonValue>().apply {
                    ownerId?.let { put("ownerId", jsonString(it)) }
                    ownerVisibility?.let { put("ownerVisibility", jsonString(it)) }
                    put("name", jsonString(name))
                    put("value", entry.value.toJson())
                    put("validTime", jsonArray(entry.validTime.map(ValidTime::toJson)))
                    put("fallback", jsonBoolean(entry.isFallback))
                },
            )
        }
    })

internal fun ResolvedPropSchema.toJson(): JsonValue = jsonObject(
    "type" to jsonString(type.name),
    "required" to jsonBoolean(required),
    "timeline" to (timeline?.toJson() ?: JsonValue.Null),
    "timelines" to (timelines?.let { jsonArray(it.map(TimelineSelector::toJson)) } ?: JsonValue.Null),
    "items" to (items?.toJson() ?: JsonValue.Null),
    "enum" to (enumValues?.let { jsonArray(it.map(RawValue::toJson)) } ?: JsonValue.Null),
)

private fun TimelineSelector.toJson(): JsonValue = when (this) {
    is TimelineSelector.Id -> jsonObject("kind" to jsonString("id"), "id" to jsonString(id))
    is TimelineSelector.Mapped -> jsonObject("kind" to jsonString("mapped"), "to" to jsonString(to))
}

internal fun GmqlDiagnostic.toJson(): JsonValue = jsonObject(
    "code" to jsonString(code),
    "kind" to jsonString(kind.name.lowercase()),
    "message" to jsonString(message),
    "range" to (range?.let {
        jsonObject("start" to jsonNumber(it.start), "end" to jsonNumber(it.end))
    } ?: JsonValue.Null),
)

internal fun GmqlValue.toJson(): JsonValue = when (this) {
    is GmqlValue.StringValue -> jsonString(value)
    is GmqlValue.IntegerValue -> jsonNumber(value)
    is GmqlValue.DecimalValue -> jsonNumber(value)
    is GmqlValue.BooleanValue -> jsonBoolean(value)
    GmqlValue.NullValue -> JsonValue.Null
    is GmqlValue.NodeValue -> jsonObject(
        "kind" to jsonString("node"),
        "id" to jsonString(id.value),
    )
    is GmqlValue.RelationValue -> jsonObject(
        "kind" to jsonString("relation"),
        "assertionId" to jsonNumber(id.value),
    )
    is GmqlValue.TypeRefValue -> jsonObject(
        "kind" to jsonString(if (relation) "relation-type" else "node-type"),
        "name" to jsonString(name),
    )
    is GmqlValue.CollectionValue -> jsonArray(values.map(GmqlValue::toJson))
    is GmqlValue.TemporalValue -> jsonArray(entries.map { entry ->
        jsonObject(
            "value" to entry.value.toJson(),
            "validTime" to entry.validTime.toJson(),
        )
    })
    is GmqlValue.TemporalExtentValue -> value.toJson()
}

private fun IntervalSet.toJson(): JsonValue = when {
    isUniversal -> jsonObject("universal" to jsonBoolean(true), "intervals" to jsonArray(emptyList()))
    else -> jsonObject(
        "universal" to jsonBoolean(false),
        "intervals" to jsonArray(intervals.map { interval ->
            jsonObject(
                "timeline" to jsonString(interval.timelineId.value),
                "start" to interval.start.toJson(),
                "end" to interval.end.toJson(),
            )
        }),
    )
}

private fun IntervalBoundary?.toJson(): JsonValue = this?.let {
    jsonObject("value" to exactValue.toJson(), "inclusive" to jsonBoolean(inclusive))
} ?: JsonValue.Null

internal fun TemporalCoordinateSpec.toJson(): JsonValue = when (this) {
    TemporalCoordinateSpec.Number -> jsonString("number")
    is TemporalCoordinateSpec.Calendar -> jsonObject(
        "kind" to jsonString("calendar"),
        "calendar" to jsonString(calendar.name.lowercase()),
    )
    is TemporalCoordinateSpec.CalendarPattern -> jsonObject(
        "kind" to jsonString("calendar-pattern"),
        "calendar" to jsonString(calendar.name.lowercase()),
        "fields" to jsonArray(fields.map { jsonString(it.name.replaceFirstChar(Char::lowercase)) }),
        "granularity" to jsonString(granularity.name.lowercase()),
        "repeatsEvery" to (repeatsEvery?.let { jsonString(it.name.lowercase()) } ?: JsonValue.Null),
        "format" to (format?.let(::jsonString) ?: JsonValue.Null),
        "quarterStartMonth" to jsonNumber(quarterStartMonth),
        "quarterYearLabel" to jsonString(quarterYearLabel.name.lowercase()),
    )
    is TemporalCoordinateSpec.Frame -> jsonObject("kind" to jsonString("frame"), "start" to jsonNumber(start))
    is TemporalCoordinateSpec.Timecode -> jsonObject(
        "kind" to jsonString("timecode"),
        "actualFps" to actualFps.toJson(),
        "nominalFps" to jsonNumber(nominalFps),
        "dropFrame" to jsonBoolean(dropFrame),
        "wrapHours" to (wrapHours?.let(::jsonNumber) ?: JsonValue.Null),
    )
    is TemporalCoordinateSpec.Era -> jsonObject(
        "kind" to jsonString("era"),
        "periods" to jsonArray(periods.map { jsonString(it.name) }),
    )
}

private fun calendarPatternText(fields: Map<CalendarField, Long>): String =
    fields.entries.sortedBy { it.key.ordinal }.joinToString("-") { (field, value) ->
        when (field) {
            CalendarField.Year, CalendarField.WeekYear -> value.toString().padStart(4, '0')
            CalendarField.Month, CalendarField.Day -> value.toString().padStart(2, '0')
            CalendarField.Quarter -> "Q$value"
            CalendarField.Week -> "W${value.toString().padStart(2, '0')}"
        }
    }

internal fun AxisLineage.toJson(): JsonValue = jsonObject(
    "sourceAxis" to jsonString(sourceAxisId),
    "derivedAxis" to jsonString(derivedAxisId),
    "sourceTimeline" to jsonString(sourceTimelineId),
    "kind" to jsonString(kind.name.lowercase()),
)

internal fun TemporalMappingInstance.toJson(): JsonValue = jsonObject(
    "id" to jsonString(id),
    "timeline" to jsonString(targetTimelineId),
    "kind" to jsonString(kind.name.lowercase()),
    "precision" to jsonString(precision.kind.name.lowercase()),
    "scale" to scale.toJson(),
    "offset" to offset.toJson(),
    "traits" to jsonObject(
        "cardinality" to jsonString(traits.cardinality.name),
        "totality" to jsonString(traits.totality.name),
        "order" to jsonString(traits.orderBehavior.name),
        "invertibility" to jsonString(traits.invertibility.name),
        "continuity" to jsonString(traits.continuity.name),
    ),
)

internal fun TimelineMapping.toJson(): JsonValue = when (this) {
    is OffsetTimelineMapping -> jsonObject(
        "kind" to jsonString(kind),
        "to" to jsonNullableString(to),
        "from" to jsonNullableString(from),
        "offset" to jsonNumber(offset),
    )
}
