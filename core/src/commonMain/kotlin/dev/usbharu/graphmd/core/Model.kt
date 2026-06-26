package dev.usbharu.graphmd.core

enum class DocumentKind {
    Node,
    NodeType,
    RelType,
    Timeline,
}

enum class Severity {
    Warning,
    Error,
}

enum class DiagnosticCategory {
    SyntaxError,
    SchemaError,
    ReferenceError,
    TypeError,
    ConstraintError,
}

data class SourceRange(
    val start: Int,
    val end: Int,
)

data class SourceInfo(
    val path: String,
    val documentId: String? = null,
    val range: SourceRange? = null,
)

data class Diagnostic(
    val category: DiagnosticCategory,
    val severity: Severity,
    val message: String,
    val source: SourceInfo? = null,
)

data class GraphCompilationResult(
    val nodes: List<NormalizedNode>,
    val relations: List<NormalizedRelation>,
    val nodeTypes: List<NormalizedNodeType>,
    val relTypes: List<NormalizedRelType>,
    val timelines: List<NormalizedTimeline>,
    val diagnostics: List<Diagnostic>,
)

data class ParsedGraphDocumentResult(
    val document: GraphDocument?,
    val diagnostics: List<Diagnostic>,
)

data class SourceDocument(
    val text: String,
    val sourcePath: String,
)

enum class ValidationMode {
    Default,
    Strict,
}

data class CompileOptions(
    val mode: ValidationMode = ValidationMode.Default,
    val emitUnknownPropertyWarnings: Boolean = true,
)

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
    override val body: String = "",
    override val sourcePath: String,
    val topLevelFields: Set<String> = setOf("id", "kind", "type", "props"),
) : GraphDocument {
    override val kind: DocumentKind = DocumentKind.Node
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
    val extends: List<String> = emptyList(),
    val timecode: TimecodeSchema? = null,
    val mappings: List<TimelineMapping> = emptyList(),
    val props: Map<String, RawValue> = emptyMap(),
    override val body: String = "",
    override val sourcePath: String,
) : GraphDocument {
    override val kind: DocumentKind = DocumentKind.Timeline
}

sealed interface RawValue

data class RawString(val value: String) : RawValue
data class RawInteger(val value: Long) : RawValue
data class RawNumber(val value: Double) : RawValue
data class RawBoolean(val value: Boolean) : RawValue
data object RawNull : RawValue
data class RawArray(val values: List<RawValue>) : RawValue
data class RawObject(val values: Map<String, RawValue>) : RawValue

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

sealed interface NormalizedValue

data class StringValue(val value: String) : NormalizedValue
data class TextValue(val values: Map<String, String>) : NormalizedValue
data class IntegerValue(val value: Long) : NormalizedValue
data class NumberValue(val value: Double) : NormalizedValue
data class BooleanValue(val value: Boolean) : NormalizedValue
data object NullValue : NormalizedValue
data class ArrayValue(val values: List<NormalizedValue>) : NormalizedValue
data class ObjectValue(val values: Map<String, NormalizedValue>) : NormalizedValue
data class InstantValue(
    val timeline: String,
    val value: String,
    val precision: String? = null,
    val timecode: TimecodeValue? = null,
) : NormalizedValue
data class IntervalValue(
    val timeline: String,
    val from: String?,
    val to: String?,
    val fromInclusive: Boolean,
    val toInclusive: Boolean,
    val fromPrecision: String? = null,
    val toPrecision: String? = null,
    val fromTimecode: TimecodeValue? = null,
    val toTimecode: TimecodeValue? = null,
) : NormalizedValue
data class DurationValue(
    val unit: String,
    val value: Double,
    val timeline: String? = null,
) : NormalizedValue

data class NormalizedNode(
    val id: String,
    val type: String,
    val props: Map<String, NormalizedValue>,
    val source: SourceInfo,
)

data class NormalizedRelation(
    val from: String,
    val to: String,
    val type: String,
    val props: Map<String, NormalizedValue>,
    val sourceLabel: String,
    val source: SourceInfo,
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
)

data class NormalizedTimeline(
    val id: String,
    val timecode: TimecodeSchema?,
    val mappings: List<TimelineMapping>,
    val props: Map<String, NormalizedValue>,
    val ancestorIds: Set<String>,
    val source: SourceInfo,
)

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
