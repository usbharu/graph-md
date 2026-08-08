package dev.usbharu.graphmd.core.model

enum class DocumentKind {
    Node,
    Media,
    NodeType,
    RelType,
    Timeline,
}

sealed interface GraphDocument {
    val id: String
    val kind: DocumentKind
    val body: String
    val sourcePath: String
}

data class NodeDocument(
    override val id: String,
    val type: String,
    val props: Map<String, RawValue> = emptyMap(),
    val url: String? = null,
    val validTime: List<ValidTime> = emptyList(),
    override val body: String = "",
    override val sourcePath: String,
    val topLevelFields: Set<String> = setOf("id", "kind", "type", "props"),
    val documentKind: DocumentKind = DocumentKind.Node,
) : GraphDocument {
    override val kind: DocumentKind = documentKind
}

data class NodeTypeDocument(
    override val id: String,
    val extends: List<String> = emptyList(),
    val props: Map<String, PropSchema> = emptyMap(),
    override val body: String = "",
    override val sourcePath: String,
) : GraphDocument {
    override val kind: DocumentKind = DocumentKind.NodeType
}

data class RelTypeDocument(
    override val id: String,
    val extends: List<String> = emptyList(),
    val from: List<String>? = null,
    val to: List<String>? = null,
    val props: Map<String, PropSchema> = emptyMap(),
    override val body: String = "",
    override val sourcePath: String,
) : GraphDocument {
    override val kind: DocumentKind = DocumentKind.RelType
}

data class TimelineDocument(
    override val id: String,
    @Deprecated("Timeline extends is replaced by sameAxisAs")
    val extends: List<String> = emptyList(),
    @Deprecated("Timeline timecode is replaced by coordinate")
    val timecode: TimecodeSchema? = null,
    @Deprecated("Timeline mappings are replaced by mapsTo")
    val mappings: List<TimelineMapping> = emptyList(),
    val props: Map<String, RawValue> = emptyMap(),
    override val body: String = "",
    override val sourcePath: String,
    val sameAxisAs: String? = null,
    val scale: ExactRational = ExactRational.ONE,
    val offset: ExactRational = ExactRational.ZERO,
    val coordinate: TemporalCoordinateSpec? = null,
    val domain: String? = null,
    val derivedFrom: DerivedFromSpec? = null,
    val mapsTo: List<TemporalMappingSpec> = emptyList(),
    val aliases: List<String> = emptyList(),
    /** True only for documents parsed from the removed authoring syntax. */
    val usesLegacyTimelineSyntax: Boolean = false,
) : GraphDocument {
    override val kind: DocumentKind = DocumentKind.Timeline
}
