package dev.usbharu.graphmd.core.model

sealed interface TimelineSelector {
    data class Id(val id: String) : TimelineSelector
    data class Mapped(val to: String) : TimelineSelector
}

enum class TimecodeType {
    number,
}

data class TimecodeSchema(
    val type: TimecodeType,
)

sealed interface TimecodeValue
data class NumberTimecode(val value: Double) : TimecodeValue

data class TimePoint(
    val coordinate: TemporalCoordinate,
    val value: String? = null,
) {
    constructor(timecode: Double, value: String? = null) : this(
        TemporalCoordinate.Rational(ExactRational.fromDouble(timecode)),
        value,
    )

    @Deprecated("Use coordinate")
    val timecode: Double
        get() = (coordinate as? TemporalCoordinate.Rational)?.value?.toDouble()
            ?: error("This temporal point is not a numeric coordinate")
}

data class ValidTime(
    val timeline: String,
    val from: TimePoint? = null,
    val to: TimePoint? = null,
)

sealed interface TimelineMapping {
    val kind: String
}

data class OffsetTimelineMapping(
    val to: String? = null,
    val from: String? = null,
    val offset: Double,
    override val kind: String = "offset",
) : TimelineMapping {
    constructor(to: String, offset: Int) : this(to = to, offset = offset.toDouble())
}
