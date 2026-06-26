package dev.usbharu.graphmd.core.model

sealed interface NormalizedValue

data class StringValue(val value: String) : NormalizedValue
data class TextValue(val values: Map<String, String>) : NormalizedValue
data class IntegerValue(val value: Long) : NormalizedValue
data class NumberValue(val value: Double) : NormalizedValue
data class BooleanValue(val value: Boolean) : NormalizedValue
data object NullValue : NormalizedValue
data class ArrayValue(val values: List<NormalizedValue>) : NormalizedValue
data class ObjectValue(val values: Map<String, NormalizedValue>) : NormalizedValue
data class InstantValue(
    val timeline: String,
    val value: String,
    val precision: String? = null,
    val timecode: TimecodeValue? = null,
) : NormalizedValue
data class IntervalValue(
    val timeline: String,
    val from: String?,
    val to: String?,
    val fromInclusive: Boolean,
    val toInclusive: Boolean,
    val fromPrecision: String? = null,
    val toPrecision: String? = null,
    val fromTimecode: TimecodeValue? = null,
    val toTimecode: TimecodeValue? = null,
) : NormalizedValue
data class DurationValue(
    val unit: String,
    val value: Double,
    val timeline: String? = null,
) : NormalizedValue
