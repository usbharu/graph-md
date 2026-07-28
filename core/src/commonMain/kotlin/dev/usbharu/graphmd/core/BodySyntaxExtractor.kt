package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

data class BodySyntaxExtraction(
    val propsBlocks: List<ExtractedPropsBlock>,
    val relations: List<ExtractedRelation>,
    val diagnostics: List<Diagnostic>,
    val propsSyntaxValid: Boolean = true,
)

class BodySyntaxExtractor {
    fun extract(body: String, sourcePath: String, documentId: String): BodySyntaxExtraction {
        val diagnostics = mutableListOf<Diagnostic>()
        val propsBlocks = mutableListOf<ExtractedPropsBlock>()
        val relations = mutableListOf<ExtractedRelation>()
        var propsSyntaxValid = true
        val masked = maskCodeRegions(body)
        var index = 0
        while (index < masked.length) {
            if (masked[index] == '@' && !isEscaped(masked, index)) {
                when {
                    masked.startsWith("@props", index) -> {
                        var objectStart = index + "@props".length
                        var defaultValidTime: RawArray? = null
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
                                val expression = validTimeArgument.groupValues[1]
                                val dummy = InlinePropsParser("{x(validTime=$expression)=0}").parseObject().values.getValue("x") as RawArray
                                ((dummy.values.single() as RawObject).values.getValue("validTime") as RawArray)
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
                    masked.startsWith("@link", index) -> {
                        val relation = parseCanonicalRelation(masked, body, index, sourcePath, documentId, diagnostics)
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
        return BodySyntaxExtraction(propsBlocks, relations, diagnostics, propsSyntaxValid)
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
    ): Pair<ExtractedRelation, Int>? {
        var cursor = start + "@link".length
        var validTime = emptyList<ValidTime>()
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
        val expression = argument.groupValues[1]
        return try {
            val entries = InlinePropsParser("{x(validTime=$expression)=0}").parseObject().values.getValue("x") as RawArray
            val validTime = ((entries.values.single() as RawObject).values.getValue("validTime") as RawArray)
            validTime.values.map { raw ->
                val obj = raw as RawObject
                ValidTime(
                    timeline = (obj.values.getValue("timeline") as RawString).value,
                    from = inlineTimePoint(obj.values["from"]),
                    to = inlineTimePoint(obj.values["to"]),
                )
            }
        } catch (e: Exception) {
            diagnostics += syntaxDiagnostic(e.message ?: "Invalid validTime expression", sourcePath, documentId, start, end)
            null
        }
    }

    private fun inlineTimePoint(raw: RawValue?): TimePoint? {
        if (raw == null) return null
        val obj = raw as RawObject
        val timecode = when (val value = obj.values.getValue("timecode")) {
            is RawInteger -> value.value.toDouble()
            is RawNumber -> value.value
            else -> throw InlinePropsParseException("timePoint.timecode must be number")
        }
        return TimePoint(timecode, (obj.values["value"] as? RawString)?.value)
    }

    private fun splitTopLevel(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        text.forEachIndexed { index, ch ->
            when (ch) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ',' -> if (depth == 0) {
                    result += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += text.substring(start)
        return result
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
        return chars.concatToString()
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

    private fun syntaxDiagnostic(message: String, sourcePath: String, documentId: String, start: Int, end: Int): Diagnostic {
        return Diagnostic(
            category = DiagnosticCategory.SyntaxError,
            severity = Severity.Error,
            message = message,
            source = SourceInfo(sourcePath, documentId, SourceRange(start, end)),
        )
    }
}
