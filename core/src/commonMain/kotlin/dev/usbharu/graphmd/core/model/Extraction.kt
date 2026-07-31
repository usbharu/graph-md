package dev.usbharu.graphmd.core.model

data class ExtractedPropsBlock(
    val props: Map<String, RawValue>,
    val range: SourceRange,
)

data class ExtractedBodyBlock(
    val names: List<String>,
    val fenceLength: Int,
    val validTime: List<ValidTime>,
    val range: SourceRange,
    val contentRange: SourceRange,
)

data class ExtractedRelation(
    val target: String,
    val relType: String,
    val label: String,
    val props: Map<String, RawValue>,
    val range: SourceRange,
    val validTime: List<ValidTime> = emptyList(),
)
