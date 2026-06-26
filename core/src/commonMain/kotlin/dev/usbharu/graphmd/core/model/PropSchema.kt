package dev.usbharu.graphmd.core.model

enum class PropType {
    string,
    text,
    integer,
    number,
    boolean,
    instant,
    interval,
    duration,
    array,
    `object`,
}

enum class PropIndex {
    none,
    exact,
    fulltext,
    range,
}

data class PropSchema(
    val type: PropType,
    val required: Boolean = false,
    val default: RawValue? = null,
    val index: PropIndex? = null,
    val timeline: TimelineSelector? = null,
    val timelines: List<TimelineSelector>? = null,
    val items: PropSchema? = null,
    val properties: Map<String, PropSchema> = emptyMap(),
)

data class ResolvedPropSchema(
    val type: PropType,
    val required: Boolean = false,
    val default: NormalizedValue? = null,
    val index: PropIndex,
    val timeline: TimelineSelector? = null,
    val timelines: List<TimelineSelector>? = null,
    val items: ResolvedPropSchema? = null,
    val properties: Map<String, ResolvedPropSchema> = emptyMap(),
)
