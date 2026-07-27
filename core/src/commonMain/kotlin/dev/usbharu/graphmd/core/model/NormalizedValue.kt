package dev.usbharu.graphmd.core.model

sealed interface NormalizedValue

data class StringValue(val value: String) : NormalizedValue
data class TextValue(val memberEntries: Map<String, NormalizedPropEntry>) : NormalizedValue {
    val entries: Map<String, NormalizedValue> = memberEntries.mapValues { it.value.value }
    val values: Map<String, String> = entries.mapNotNull { (key, value) ->
        (value as? StringValue)?.value?.let { key to it }
    }.toMap()
}
data class IntegerValue(val value: Long) : NormalizedValue
data class NumberValue(val value: Double) : NormalizedValue
data class BooleanValue(val value: Boolean) : NormalizedValue
data object NullValue : NormalizedValue
data class ArrayValue(
    val values: List<NormalizedValue>,
    val elements: List<NormalizedArrayElement> = values.map { NormalizedArrayElement(it) },
) : NormalizedValue
data class NormalizedArrayElement(
    val value: NormalizedValue,
    val validTime: List<ValidTime> = emptyList(),
    val isFallback: Boolean = false,
)
data class ObjectValue(
    val values: Map<String, NormalizedValue>,
    val members: Map<String, NormalizedPropEntry> = values.mapValues { NormalizedPropEntry(it.value) },
) : NormalizedValue
data class InstantValue(
    val timeline: String? = null,
    val value: String? = null,
    val timecode: TimecodeValue,
) : NormalizedValue
data class DurationValue(
    val timeline: String? = null,
    val from: TemporalPoint? = null,
    val to: TemporalPoint? = null,
) : NormalizedValue

data class TemporalPoint(
    val timecode: Double,
    val value: String? = null,
    val timeline: String? = null,
)

data class NormalizedPropEntry(
    val value: NormalizedValue,
    val validTime: List<ValidTime> = emptyList(),
    val isFallback: Boolean = false,
)
