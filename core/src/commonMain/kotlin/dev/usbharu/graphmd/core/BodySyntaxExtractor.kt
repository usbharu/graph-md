package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

data class BodySyntaxExtraction(
    val propsBlocks: List<ExtractedPropsBlock>,
    val relations: List<ExtractedRelation>,
    val diagnostics: List<Diagnostic>,
)

class BodySyntaxExtractor {
    fun extract(body: String, sourcePath: String, documentId: String): BodySyntaxExtraction {
        val diagnostics = mutableListOf<Diagnostic>()
        val propsBlocks = mutableListOf<ExtractedPropsBlock>()
        val relations = mutableListOf<ExtractedRelation>()
        val masked = maskCodeRegions(body)
        var index = 0
        while (index < masked.length) {
            if (masked[index] == '@' && !isEscaped(masked, index)) {
                when {
                    masked.startsWith("@props", index) -> {
                        val objectStart = index + "@props".length
                        if (masked.getOrNull(objectStart) == '{') {
                            val range = readBalanced(masked, objectStart, '{', '}')
                            if (range != null) {
                                val text = body.substring(objectStart, range.end)
                                try {
                                    val parsed = InlinePropsParser(text).parseObject()
                                    propsBlocks += ExtractedPropsBlock(parsed.values, SourceRange(index, range.end))
                                } catch (e: InlinePropsParseException) {
                                    diagnostics += syntaxDiagnostic(e.message ?: "Invalid @props", sourcePath, documentId, index, range.end)
                                }
                                index = range.end
                                continue
                            }
                            diagnostics += syntaxDiagnostic("Unclosed @props block", sourcePath, documentId, index, body.length)
                        }
                    }
                    masked.startsWith("@[", index) -> {
                        val relation = parseRelation(masked, body, index, sourcePath, documentId, diagnostics)
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
        return BodySyntaxExtraction(propsBlocks, relations, diagnostics)
    }

    private fun parseRelation(
        masked: String,
        original: String,
        start: Int,
        sourcePath: String,
        documentId: String,
        diagnostics: MutableList<Diagnostic>,
    ): Pair<ExtractedRelation, Int>? {
        val closeLabel = findUnescaped(masked, ']', start + 2) ?: run {
            diagnostics += syntaxDiagnostic("Unclosed relation label", sourcePath, documentId, start, original.length)
            return null
        }
        if (masked.getOrNull(closeLabel + 1) != '(') {
            diagnostics += syntaxDiagnostic("Relation must be followed by (...)", sourcePath, documentId, start, closeLabel + 1)
            return null
        }
        val closeParen = findUnescaped(masked, ')', closeLabel + 2) ?: run {
            diagnostics += syntaxDiagnostic("Unclosed relation target", sourcePath, documentId, start, original.length)
            return null
        }
        val label = unescapeLabel(original.substring(start + 2, closeLabel))
        val targetAndType = original.substring(closeLabel + 2, closeParen).trim()
        val parts = parseRelationTargetAndType(targetAndType)
        if (parts == null) {
            diagnostics += syntaxDiagnostic("Relation target and type must be separated by horizontal spaces", sourcePath, documentId, start, closeParen)
            return null
        }
        var end = closeParen + 1
        val props = if (masked.getOrNull(end) == '{') {
            val range = readBalanced(masked, end, '{', '}')
            if (range == null) {
                diagnostics += syntaxDiagnostic("Unclosed relation props", sourcePath, documentId, start, original.length)
                return null
            }
            end = range.end
            try {
                InlinePropsParser(original.substring(closeParen + 1, range.end)).parseObject().values
            } catch (e: InlinePropsParseException) {
                diagnostics += syntaxDiagnostic(e.message ?: "Invalid relation props", sourcePath, documentId, start, range.end)
                return null
            }
        } else {
            emptyMap()
        }
        return ExtractedRelation(parts.first, parts.second, label, props, SourceRange(start, end)) to end
    }

    private fun maskCodeRegions(body: String): String {
        val chars = body.toCharArray()
        var i = 0
        var lineStart = true
        while (i < chars.size) {
            if (lineStart && body.startsWith("```", i)) {
                val end = body.indexOf("\n```", i + 3).let { if (it >= 0) it + 4 else chars.size }
                for (j in i until minOf(end, chars.size)) chars[j] = ' '
                i = end
                lineStart = true
                continue
            }
            if (lineStart && body.startsWith("    ", i)) {
                var j = i
                while (j < chars.size && chars[j] != '\n') {
                    chars[j] = ' '
                    j++
                }
                i = j
                lineStart = true
                continue
            }
            if (chars[i] == '`') {
                val end = body.indexOf('`', i + 1)
                val actualEnd = if (end >= 0) end else chars.size - 1
                for (j in i..actualEnd) chars[j] = ' '
                i = actualEnd + 1
                lineStart = false
                continue
            }
            lineStart = chars[i] == '\n'
            i++
        }
        return String(chars)
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

    private fun unescapeLabel(label: String): String {
        return label.replace("\\]", "]").replace("\\\\", "\\")
    }

    private fun parseRelationTargetAndType(value: String): Pair<String, String>? {
        val trimmed = value.trim()
        val separator = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (separator <= 0) return null
        val target = trimmed.substring(0, separator)
        val typePart = trimmed.substring(separator).trim()
        if (typePart.isEmpty()) return null
        if (typePart.first() == '"') {
            val relType = parseQuotedRelationType(typePart) ?: return null
            return target to relType
        }
        if (typePart.any { it == ' ' || it == '\t' || it == ')' }) return null
        return target to typePart
    }

    private fun parseQuotedRelationType(value: String): String? {
        val builder = StringBuilder()
        var index = 1
        var escaped = false
        while (index < value.length) {
            val char = value[index]
            if (escaped) {
                builder.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return if (value.substring(index + 1).trim().isEmpty()) builder.toString() else null
            } else {
                builder.append(char)
            }
            index += 1
        }
        return null
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
