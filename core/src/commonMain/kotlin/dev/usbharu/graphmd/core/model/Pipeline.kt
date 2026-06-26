package dev.usbharu.graphmd.core.model

enum class ValidationMode {
    Default,
    Strict,
}

data class CompileOptions(
    val mode: ValidationMode = ValidationMode.Default,
    val emitUnknownPropertyWarnings: Boolean = true,
)

data class SourceDocument(
    val text: String,
    val sourcePath: String,
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
