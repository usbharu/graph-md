package dev.usbharu.graphmd.core.model

data class NormalizedNode(
    val id: String,
    val type: String,
    val props: Map<String, NormalizedValue>,
    val kind: DocumentKind = DocumentKind.Node,
    val url: String? = null,
    val validTime: List<ValidTime> = emptyList(),
    val propEntries: Map<String, List<NormalizedPropEntry>> = props.mapValues { listOf(NormalizedPropEntry(it.value, validTime)) },
    val source: SourceInfo,
)

data class NormalizedRelation(
    val from: String,
    val to: String,
    val type: String,
    val props: Map<String, NormalizedValue>,
    val sourceLabel: String,
    val source: SourceInfo,
    val validTime: List<ValidTime> = emptyList(),
    val propEntries: Map<String, List<NormalizedPropEntry>> = props.mapValues { listOf(NormalizedPropEntry(it.value, validTime)) },
    val targetUrl: String? = null,
)

data class NormalizedNodeType(
    val id: String,
    val props: Map<String, ResolvedPropSchema>,
    val ancestorIds: Set<String>,
    val source: SourceInfo,
)

data class NormalizedRelType(
    val id: String,
    val from: List<String>?,
    val to: List<String>?,
    val props: Map<String, ResolvedPropSchema>,
    val source: SourceInfo,
    val ancestorIds: Set<String> = emptySet(),
)

data class NormalizedTimeline(
    val id: String,
    val timecode: TimecodeSchema?,
    val mappings: List<TimelineMapping>,
    val props: Map<String, NormalizedValue>,
    val ancestorIds: Set<String>,
    val mappedOffsets: Map<String, Double> = emptyMap(),
    val source: SourceInfo,
)
