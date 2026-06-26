package dev.usbharu.graphmd.core.model

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
