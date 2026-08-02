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
    val coordinate: TemporalCoordinate,
) : NormalizedValue {
    constructor(timeline: String? = null, value: String? = null, timecode: TimecodeValue) : this(
        timeline,
        value,
        when (timecode) {
            is NumberTimecode -> TemporalCoordinate.Rational(ExactRational.fromDouble(timecode.value))
        },
    )

    @Deprecated("Use coordinate")
    val timecode: TimecodeValue
        get() = NumberTimecode(
            (coordinate as? TemporalCoordinate.Rational)?.value?.toDouble()
                ?: error("This instant is not a numeric coordinate"),
        )
}
data class DurationValue(
    val timeline: String? = null,
    val from: TemporalPoint? = null,
    val to: TemporalPoint? = null,
) : NormalizedValue

data class TemporalPoint(
    val coordinate: TemporalCoordinate,
    val value: String? = null,
    val timeline: String? = null,
) {
    constructor(timecode: Double, value: String? = null, timeline: String? = null) : this(
        TemporalCoordinate.Rational(ExactRational.fromDouble(timecode)),
        value,
        timeline,
    )

    @Deprecated("Use coordinate")
    val timecode: Double
        get() = (coordinate as? TemporalCoordinate.Rational)?.value?.toDouble()
            ?: error("This temporal point is not numeric")
}

data class NormalizedPropEntry(
    val value: NormalizedValue,
    val validTime: List<ValidTime> = emptyList(),
    val isFallback: Boolean = false,
)
