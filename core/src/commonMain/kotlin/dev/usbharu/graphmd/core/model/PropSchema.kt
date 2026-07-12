package dev.usbharu.graphmd.core.model

enum class PropType {
    string,
    text,
    number,
    instant,
    duration,
    array,
}

enum class PropIndex {
    fulltext,
    range,
}

data class PropSchema(
    val type: PropType,
    val required: Boolean = false,
    val index: PropIndex? = null,
    val timeline: TimelineSelector? = null,
    val timelines: List<TimelineSelector>? = null,
    val items: PropSchema? = null,
)

data class ResolvedPropSchema(
    val type: PropType,
    val required: Boolean = false,
    val index: PropIndex? = null,
    val timeline: TimelineSelector? = null,
    val timelines: List<TimelineSelector>? = null,
    val items: ResolvedPropSchema? = null,
)
