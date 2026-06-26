package dev.usbharu.graphmd.core.model

sealed interface TimelineSelector {
    data class Id(val id: String) : TimelineSelector
    data object Any : TimelineSelector
    data class Mapped(val to: String) : TimelineSelector
}

enum class TimecodeType {
    number,
    tuple,
}

enum class TimecodeDirection {
    ascending,
    descending,
}

data class TimecodeSchema(
    val type: TimecodeType,
    val direction: TimecodeDirection? = null,
)

sealed interface TimecodeValue
data class NumberTimecode(val value: Double) : TimecodeValue
data class TupleTimecode(val values: List<Double>) : TimecodeValue

sealed interface TimelineMapping {
    val kind: String
}

data class NoTimelineMapping(override val kind: String = "none") : TimelineMapping
data class OffsetTimelineMapping(
    val to: String,
    val unit: String? = null,
    val offset: Int,
    override val kind: String = "offset",
) : TimelineMapping

data class TableTimelineMappingEntry(
    val from: String,
    val to: String,
    val fromTimecode: TimecodeValue? = null,
    val toTimecode: TimecodeValue? = null,
)

data class TableTimelineMapping(
    val to: String,
    val entries: List<TableTimelineMappingEntry>,
    override val kind: String = "table",
) : TimelineMapping
