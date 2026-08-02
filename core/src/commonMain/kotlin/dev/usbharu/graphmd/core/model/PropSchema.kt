package dev.usbharu.graphmd.core.model

enum class PropType {
    string,
    text,
    number,
    instant,
    duration,
    array,
}

data class PropSchema(
    val type: PropType,
    val required: Boolean = false,
    val timeline: TimelineSelector? = null,
    val timelines: List<TimelineSelector>? = null,
    val items: PropSchema? = null,
    val enumValues: List<RawValue>? = null,
)

data class ResolvedPropSchema(
    val type: PropType,
    val required: Boolean = false,
    val timeline: TimelineSelector? = null,
    val timelines: List<TimelineSelector>? = null,
    val items: ResolvedPropSchema? = null,
    val enumValues: List<RawValue>? = null,
)
