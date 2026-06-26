package dev.usbharu.graphmd.core.model

data class ExtractedPropsBlock(
    val props: Map<String, RawValue>,
    val range: SourceRange,
)

data class ExtractedRelation(
    val target: String,
    val relType: String,
    val label: String,
    val props: Map<String, RawValue>,
    val range: SourceRange,
)
