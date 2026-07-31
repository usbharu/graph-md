package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

data class BodySyntaxExtraction(
    val propsBlocks: List<ExtractedPropsBlock>,
    val relations: List<ExtractedRelation>,
    val diagnostics: List<Diagnostic>,
    val propsSyntaxValid: Boolean = true,
    val blocks: List<ExtractedBodyBlock> = emptyList(),
)

class BodySyntaxExtractor {
    fun extract(body: String, sourcePath: String, documentId: String): BodySyntaxExtraction {
        val diagnostics = mutableListOf<Diagnostic>()
        val propsBlocks = mutableListOf<ExtractedPropsBlock>()
        val relations = mutableListOf<ExtractedRelation>()
        var propsSyntaxValid = true
        val masking = CommonMarkCodeMasker.analyze(body)
        val masked = masking.masked
        val blockParsing = BodyBlockParser.parse(
            body,
            masked,
            sourcePath,
            documentId,
            masking.rootLineStarts,
        )
        diagnostics += blockParsing.diagnostics
        val blocks = blockParsing.blocks

        fun inheritedBlockValidTime(index: Int): List<ValidTime> =
            blocks.asSequence()
                .filter { block ->
                    block.validTime.isNotEmpty() &&
                        index >= block.contentRange.start &&
                        index < block.contentRange.end
                }
                .maxByOrNull { it.contentRange.start }
                ?.validTime
                .orEmpty()

        var index = 0
        while (index < masked.length) {
            if (masked[index] == '@' && !isEscaped(masked, index)) {
                when {
                    isDirectiveKeywordAt(masked, index, "@props") -> {
                        var objectStart = index + "@props".length
                        var defaultValidTime: RawArray? =
                            inheritedBlockValidTime(index).takeIf { it.isNotEmpty() }?.let(::validTimesToRawArray)
                        if (masked.getOrNull(objectStart) == '(') {
                            val args = readBalanced(masked, objectStart, '(', ')')
                            if (args == null) {
                                propsSyntaxValid = false
                                diagnostics += syntaxDiagnostic("Unclosed @props arguments", sourcePath, documentId, index, body.length)
                                index += 1
                                continue
                            }
                            val argumentText = body.substring(objectStart + 1, args.end - 1).trim()
                            val validTimeArgument = Regex("""^validTime\s*=\s*(.+)$""").matchEntire(argumentText)
                            if (validTimeArgument == null) {
                                propsSyntaxValid = false
                                diagnostics += syntaxDiagnostic("@props only accepts validTime=...", sourcePath, documentId, index, args.end)
                                index = args.end
                                continue
                            }
                            defaultValidTime = try {
                                validTimesToRawArray(parseInlineValidTimeArgument(argumentText).validTime)
                            } catch (e: Exception) {
                                propsSyntaxValid = false
                                diagnostics += syntaxDiagnostic(e.message ?: "Invalid @props validTime", sourcePath, documentId, index, args.end)
                                index = args.end
                                continue
                            }
                            objectStart = args.end
                        }
                        if (masked.getOrNull(objectStart) == '{') {
                            val range = readBalanced(masked, objectStart, '{', '}')
                            if (range != null) {
                                val text = body.substring(objectStart, range.end)
                                try {
                                    val parsed = InlinePropsParser(text).parseObject()
                                    val props = defaultValidTime?.let { applyDefaultValidTime(parsed.values, it) } ?: parsed.values
                                    propsBlocks += ExtractedPropsBlock(props, SourceRange(index, range.end))
                                } catch (e: InlinePropsParseException) {
                                    propsSyntaxValid = false
                                    val errorRange = e.errorRange?.let {
                                        SourceRange(objectStart + it.start, objectStart + it.end)
                                    } ?: SourceRange(index, range.end)
                                    diagnostics += syntaxDiagnostic(
                                        e.message ?: "Invalid @props",
                                        sourcePath,
                                        documentId,
                                        errorRange.start,
                                        errorRange.end,
                                    )
                                }
                                index = range.end
                                continue
                            }
                            propsSyntaxValid = false
                            diagnostics += syntaxDiagnostic("Unclosed @props block", sourcePath, documentId, index, body.length)
                        }
                    }
                    isDirectiveKeywordAt(masked, index, "@link") -> {
                        val relation = parseCanonicalRelation(
                            masked,
                            body,
                            index,
                            sourcePath,
                            documentId,
                            diagnostics,
                            inheritedBlockValidTime(index),
                        )
                        if (relation != null) {
                            relations += relation.first
                            index = relation.second
                            continue
                        }
                    }
                }
            }
            index += 1
        }
        return BodySyntaxExtraction(
            propsBlocks = propsBlocks,
            relations = relations,
            diagnostics = diagnostics,
            propsSyntaxValid = propsSyntaxValid,
            blocks = blocks,
        )
    }

    private fun applyDefaultValidTime(props: Map<String, RawValue>, validTime: RawArray): Map<String, RawValue> =
        props.mapValues { (_, value) ->
            val explicitEntries = (value as? RawArray)?.values?.takeIf { entries ->
                entries.all { it is RawObject && "value" in it.values }
            }
            if (explicitEntries != null) {
                RawArray(explicitEntries.map { entry ->
                    val obj = entry as RawObject
                    if ("validTime" in obj.values) obj else RawObject(obj.values + ("validTime" to validTime))
                })
            } else {
                RawArray(listOf(RawObject(mapOf("value" to value, "validTime" to validTime))))
            }
        }

    private fun parseCanonicalRelation(
        masked: String,
        original: String,
        start: Int,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
        inheritedValidTime: List<ValidTime>,
    ): Pair<ExtractedRelation, Int>? {
        var cursor = start + "@link".length
        var validTime = inheritedValidTime
        if (masked.getOrNull(cursor) == '(') {
            val args = readBalanced(masked, cursor, '(', ')') ?: run {
                diagnostics += syntaxDiagnostic("Unclosed @link arguments", sourcePath, documentId, start, original.length)
                return null
            }
            val argumentText = original.substring(cursor + 1, args.end - 1).trim()
            validTime = parseValidTimeArgument(argumentText, sourcePath, documentId, start, args.end, diagnostics) ?: return null
            cursor = args.end
        }
        val props = if (masked.getOrNull(cursor) == '{') {
            val propsRange = readBalanced(masked, cursor, '{', '}') ?: run {
                diagnostics += syntaxDiagnostic("Unclosed @link property block", sourcePath, documentId, start, original.length)
                return null
            }
            val parsed = try {
                InlinePropsParser(original.substring(cursor, propsRange.end)).parseObject().values
            } catch (e: InlinePropsParseException) {
                diagnostics += syntaxDiagnostic(e.message ?: "Invalid @link properties", sourcePath, documentId, start, propsRange.end)
                return null
            }
            cursor = propsRange.end
            parsed
        } else {
            emptyMap()
        }
        if (masked.getOrNull(cursor) != '[') {
            diagnostics += syntaxDiagnostic("@link must be followed immediately by a link", sourcePath, documentId, start, cursor)
            return null
        }
        val parsed = parseRelation(masked, original, cursor - 1, start, sourcePath, documentId, diagnostics) ?: return null
        val relation = parsed.first.copy(props = props, range = SourceRange(start, parsed.second), validTime = validTime)
        return relation to parsed.second
    }

    private fun parseValidTimeArgument(
        text: String,
        sourcePath: String,
        documentId: String,
        start: Int,
        end: Int,
        diagnostics: MutableList<Diagnostic>,
    ): List<ValidTime>? {
        val argument = Regex("""^validTime\s*=\s*(.+)$""").matchEntire(text)
        if (argument == null) {
            diagnostics += syntaxDiagnostic("@link only accepts validTime=...", sourcePath, documentId, start, end)
            return null
        }
        return try {
            parseInlineValidTimeArgument(text).validTime
        } catch (e: Exception) {
            diagnostics += syntaxDiagnostic(e.message ?: "Invalid validTime expression", sourcePath, documentId, start, end)
            null
        }
    }

    private fun parseRelation(
        masked: String,
        original: String,
        start: Int,
        diagnosticStart: Int,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Pair<ExtractedRelation, Int>? {
        val closeLabel = findUnescaped(masked, ']', start + 2) ?: run {
            diagnostics += syntaxDiagnostic("Unclosed relation label", sourcePath, documentId, diagnosticStart, original.length)
            return null
        }
        if (masked.getOrNull(closeLabel + 1) != '(') {
            diagnostics += syntaxDiagnostic("Relation must be followed by (...)", sourcePath, documentId, diagnosticStart, closeLabel + 1)
            return null
        }
        val closeParen = findUnescaped(masked, ')', closeLabel + 2) ?: run {
            diagnostics += syntaxDiagnostic("Unclosed relation target", sourcePath, documentId, diagnosticStart, original.length)
            return null
        }
        val label = unescapeLabel(original.substring(start + 2, closeLabel))
        val targetAndType = original.substring(closeLabel + 2, closeParen).trim()
        val parts = RelationTargetParser.parse(targetAndType)
        if (parts == null) {
            diagnostics += syntaxDiagnostic(
                "Relation target and type must be separated by horizontal spaces",
                sourcePath,
                documentId,
                diagnosticStart,
                closeParen,
            )
            return null
        }
        var end = closeParen + 1
        val props = if (masked.getOrNull(end) == '{') {
            val range = readBalanced(masked, end, '{', '}')
            if (range == null) {
                diagnostics += syntaxDiagnostic("Unclosed relation props", sourcePath, documentId, diagnosticStart, original.length)
                return null
            }
            end = range.end
            try {
                InlinePropsParser(original.substring(closeParen + 1, range.end)).parseObject().values
            } catch (e: InlinePropsParseException) {
                diagnostics += syntaxDiagnostic(
                    e.message ?: "Invalid relation props",
                    sourcePath,
                    documentId,
                    diagnosticStart,
                    range.end,
                )
                return null
            }
        } else {
            emptyMap()
        }
        return ExtractedRelation(parts.first, parts.second, label, props, SourceRange(start, end)) to end
    }

    private fun readBalanced(text: String, start: Int, open: Char, close: Char): SourceRange? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) {
                            return SourceRange(start, i + 1)
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun findUnescaped(text: String, target: Char, start: Int): Int? {
        var i = start
        var escaped = false
        while (i < text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == target) {
                return i
            } else if (ch == '\n') {
                return null
            }
            i++
        }
        return null
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashCount = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            slashCount++
            i--
        }
        return slashCount % 2 == 1
    }

    private fun isDirectiveKeywordAt(text: String, index: Int, keyword: String): Boolean =
        text.startsWith(keyword, index) &&
            !text.getOrNull(index + keyword.length).isIdentifierContinuation()

    private fun Char?.isIdentifierContinuation(): Boolean =
        this != null && (isLetterOrDigit() || this in setOf('_', '.', ':', '-'))

    private fun unescapeLabel(label: String): String {
        return label.replace("\\]", "]").replace("\\\\", "\\")
    }

    private fun syntaxDiagnostic(message: String, sourcePath: String, documentId: String, start: Int, end: Int): Diagnostic {
        return Diagnostic(
            category = DiagnosticCategory.SyntaxError,
            severity = Severity.Error,
            message = message,
            source = SourceInfo(sourcePath, documentId, SourceRange(start, end)),
        )
    }
}
