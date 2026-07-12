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
    val timecode: Double,
    val value: String? = null,
)

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
