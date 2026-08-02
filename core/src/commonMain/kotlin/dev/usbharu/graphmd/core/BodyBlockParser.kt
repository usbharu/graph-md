package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

internal data class ParsedBodyBlockHeader(
    val names: List<String>,
    val validTime: List<ValidTime>,
    val timelineReferences: List<InlineTimelineReference>,
    val embed: EmbedDirective? = null,
)

internal data class BodyBlockParsing(
    val blocks: List<ExtractedBodyBlock>,
    val diagnostics: List<Diagnostic>,
)

internal data class BodyBlockLineMarker(
    val fenceLength: Int,
    val header: String?,
    val headerStart: Int?,
)

internal object BodyBlockHeaderParser {
    private val validTimeAssignment = Regex("""(?:^|[ \t])validTime[ \t]*=[ \t]*""")

    fun parse(header: String): ParsedBodyBlockHeader {
        if (header.trimStart().startsWith("embed:")) return parseEmbedHeader(header)
        val names = mutableListOf<String>()
        var validTime = emptyList<ValidTime>()
        var timelineReferences = emptyList<InlineTimelineReference>()
        var index = 0

        fun skipHorizontal() {
            while (header.getOrNull(index) == ' ' || header.getOrNull(index) == '\t') index++
        }

        skipHorizontal()
        if (index >= header.length) throw InlinePropsParseException("Block header must not be empty")
        while (index < header.length) {
            val nameStart = index
            val first = header.getOrNull(index)
            if (first == null || !(first.isAsciiLetter() || first == '_')) {
                throw InlinePropsParseException("Expected block name or validTime")
            }
            index++
            while (
                header.getOrNull(index)?.let {
                    it.isAsciiLetter() || it in '0'..'9' || it in setOf('_', '.', ':', '-')
                } == true
            ) {
                index++
            }
            val name = header.substring(nameStart, index)
            val afterName = index
            skipHorizontal()
            if (name == "validTime" && header.getOrNull(index) == '=') {
                index++
                skipHorizontal()
                val expressionStart = index
                val expressionEnd = validTimeExpressionEnd(header, expressionStart)
                if (expressionEnd == expressionStart) {
                    throw InlinePropsParseException("Expected validTime expression")
                }
                val argument = "validTime=${header.substring(expressionStart, expressionEnd)}"
                val parsed = parseInlineValidTimeArgument(argument)
                if (parsed.validTime.isEmpty()) {
                    throw InlinePropsParseException("validTime must be non-empty")
                }
                validTime = parsed.validTime
                val shift = expressionStart - "validTime=".length
                timelineReferences = parsed.timelineReferences.map { reference ->
                    reference.copy(
                        range = SourceRange(
                            reference.range.start + shift,
                            reference.range.end + shift,
                        ),
                    )
                }
                index = expressionEnd
            } else {
                names += name
                index = afterName
            }
            if (index < header.length && header[index] != ' ' && header[index] != '\t') {
                throw InlinePropsParseException("Block header entries must be separated by spaces")
            }
            skipHorizontal()
        }
        return ParsedBodyBlockHeader(names, validTime, timelineReferences)
    }

    private fun parseEmbedHeader(header: String): ParsedBodyBlockHeader {
        var index = 0
        var embed: EmbedDirective? = null

        fun skipHorizontal() {
            while (header.getOrNull(index) == ' ' || header.getOrNull(index) == '\t') index++
        }

        fun consume(text: String) {
            if (!header.startsWith(text, index)) throw InlinePropsParseException("Expected $text")
            index += text.length
        }

        fun quotedQuery(): String {
            if (header.getOrNull(index) != '"') throw InlinePropsParseException("embed:query requires a quoted string")
            index++
            val result = StringBuilder()
            while (index < header.length) {
                when (val char = header[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        val escaped = header.getOrNull(index++)
                            ?: throw InlinePropsParseException("Unclosed embed:query string")
                        result.append(
                            when (escaped) {
                                '"' -> '"'
                                '\\' -> '\\'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> throw InlinePropsParseException("Unsupported escape in embed:query: \\$escaped")
                            },
                        )
                    }
                    else -> result.append(char)
                }
            }
            throw InlinePropsParseException("Unclosed embed:query string")
        }

        skipHorizontal()
        while (index < header.length) {
            consume("embed:")
            embed = when {
                header.startsWith("query=", index) -> {
                    index += "query=".length
                    EmbedDirective.Query(quotedQuery())
                }
                header.startsWith("back-link=", index) -> {
                    index += "back-link=".length
                    val start = index
                    val first = header.getOrNull(index)
                    if (first == null || !(first.isAsciiLetter() || first == '_')) {
                        throw InlinePropsParseException("embed:back-link requires a RelType id")
                    }
                    index++
                    while (header.getOrNull(index)?.let { it.isAsciiLetter() || it in '0'..'9' || it in setOf('_', '.', ':', '-') } == true) {
                        index++
                    }
                    EmbedDirective.BackLink(header.substring(start, index))
                }
                else -> throw InlinePropsParseException("Unknown embed attribute")
            }
            if (index < header.length && header[index] != ' ' && header[index] != '\t') {
                throw InlinePropsParseException("Embed attributes must be separated by spaces")
            }
            skipHorizontal()
            if (index < header.length && !header.startsWith("embed:", index)) {
                throw InlinePropsParseException("Embed blocks only accept embed attributes")
            }
        }
        return ParsedBodyBlockHeader(emptyList(), emptyList(), emptyList(), embed)
    }

    fun isTimelineCompletionPosition(header: String, offset: Int): Boolean {
        val safeOffset = offset.coerceIn(0, header.length)
        return validTimeAssignment.findAll(header).any { match ->
            if (!isTopLevelHeaderOffset(header, match.range.first)) return@any false
            val equals = header.indexOf('=', match.range.first)
            val expressionStart = match.range.last + 1
            val expressionEnd = validTimeExpressionEnd(header, expressionStart)
            when {
                safeOffset in (equals + 1)..expressionStart -> true
                safeOffset !in expressionStart..expressionEnd -> false
                else -> isValidTimeTimelineCompletionPosition(
                    header.substring(expressionStart, expressionEnd),
                    safeOffset - expressionStart,
                )
            }
        }
    }

    private fun isTopLevelHeaderOffset(text: String, offset: Int): Boolean {
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var inString = false
        var escaped = false
        for (index in 0 until offset) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '(' -> parentheses++
                    ')' -> parentheses--
                    '[' -> brackets++
                    ']' -> brackets--
                    '{' -> braces++
                    '}' -> braces--
                }
            }
        }
        return !inString && parentheses == 0 && brackets == 0 && braces == 0
    }

    private fun validTimeExpressionEnd(text: String, start: Int): Int {
        var index = start
        var parentheses = 0
        var brackets = 0
        var braces = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '(' -> parentheses++
                    ')' -> parentheses--
                    '[' -> brackets++
                    ']' -> brackets--
                    '{' -> braces++
                    '}' -> braces--
                    ' ', '\t' -> if (parentheses == 0 && brackets == 0 && braces == 0) return index
                }
            }
            index++
        }
        return index
    }
}

internal fun isValidTimeTimelineCompletionPosition(expression: String, offset: Int): Boolean {
    val safeOffset = offset.coerceIn(0, expression.length)
    var index = 0

    fun skipWhitespace() {
        while (expression.getOrNull(index)?.isWhitespace() == true) index++
    }

    var array = false
    skipWhitespace()
    if (expression.getOrNull(index) == '[') {
        array = true
        index++
    }

    while (index <= expression.length) {
        val expectedStart = index
        skipWhitespace()
        if (safeOffset in expectedStart..index && (index >= expression.length || expression[index] != ']')) {
            return true
        }
        if (index >= expression.length || array && expression[index] == ']') return false

        val referenceStart = index
        while (expression.getOrNull(index)?.let { char ->
                !char.isWhitespace() && char !in setOf(',', '(', ')', '[', ']', '{', '}', '=')
            } == true
        ) {
            index++
        }
        if (index == referenceStart) return false
        if (safeOffset in referenceStart..index) return true

        skipWhitespace()
        if (expression.getOrNull(index) == '(') {
            val end = balancedExpressionEnd(expression, index, '(', ')') ?: return false
            if (safeOffset in index until end) return false
            index = end
            skipWhitespace()
        }

        if (!array) return false
        when (expression.getOrNull(index)) {
            ',' -> index++
            ']' -> return false
            else -> return false
        }
    }
    return false
}

private fun balancedExpressionEnd(text: String, start: Int, open: Char, close: Char): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        } else {
            when (char) {
                '"' -> inString = true
                open -> depth++
                close -> if (--depth == 0) return index + 1
            }
        }
    }
    return null
}

internal object BodyBlockParser {
    private data class OpenBlock(
        val names: List<String>,
        val fenceLength: Int,
        val validTime: List<ValidTime>,
        val lineStart: Int,
        val contentStart: Int,
        val embed: EmbedDirective?,
        val completedDescendants: MutableList<ExtractedBodyBlock> = mutableListOf(),
    )

    fun parse(
        body: String,
        masked: String,
        sourcePath: String,
        documentId: String,
        rootLineStarts: Set<Int>? = null,
    ): BodyBlockParsing {
        val diagnostics = mutableListOf<Diagnostic>()
        val blocks = mutableListOf<ExtractedBodyBlock>()
        val stack = mutableListOf<OpenBlock>()
        var lineStart = 0

        while (lineStart < masked.length) {
            val newline = masked.indexOf('\n', lineStart).let { if (it < 0) masked.length else it }
            val contentEnd = if (newline > lineStart && masked[newline - 1] == '\r') newline - 1 else newline
            val nextLine = if (newline < masked.length) newline + 1 else masked.length
            val marker = if (rootLineStarts == null || lineStart in rootLineStarts) {
                parseBodyBlockLineMarker(masked, body, lineStart, contentEnd)
            } else {
                null
            }
            if (marker != null) {
                if (marker.header == null) {
                    val open = stack.lastOrNull()
                    when {
                        open == null -> diagnostics += syntaxDiagnostic(
                            "Unexpected block closing fence",
                            sourcePath,
                            documentId,
                            lineStart,
                            contentEnd,
                        )
                        open.fenceLength != marker.fenceLength -> diagnostics += syntaxDiagnostic(
                            "Block closing fence must match opening fence length ${open.fenceLength}",
                            sourcePath,
                            documentId,
                            lineStart,
                            contentEnd,
                        )
                        else -> {
                            stack.removeAt(stack.lastIndex)
                            val completed = ExtractedBodyBlock(
                                names = open.names,
                                fenceLength = open.fenceLength,
                                validTime = open.validTime,
                                range = SourceRange(open.lineStart, nextLine),
                                contentRange = SourceRange(open.contentStart, lineStart),
                                embed = open.embed,
                            )
                            val completedSubtree = open.completedDescendants + completed
                            val parent = stack.lastOrNull()
                            if (parent == null) {
                                blocks += completedSubtree
                            } else {
                                parent.completedDescendants += completedSubtree
                            }
                        }
                    }
                } else {
                    val parsed = try {
                        BodyBlockHeaderParser.parse(marker.header)
                    } catch (exception: InlinePropsParseException) {
                        diagnostics += syntaxDiagnostic(
                            "Invalid block header: ${exception.message ?: "invalid syntax"}",
                            sourcePath,
                            documentId,
                            lineStart,
                            contentEnd,
                        )
                        null
                    }
                    if (parsed != null) {
                        val parent = stack.lastOrNull()
                        if (parent != null && marker.fenceLength <= parent.fenceLength) {
                            diagnostics += syntaxDiagnostic(
                                "Nested block fence must be longer than parent fence ${parent.fenceLength}",
                                sourcePath,
                                documentId,
                                lineStart,
                                contentEnd,
                            )
                        } else {
                            if (parsed.embed != null && stack.any { it.embed != null }) {
                                diagnostics += syntaxDiagnostic(
                                    "Embed blocks must not be nested inside another embed block",
                                    sourcePath,
                                    documentId,
                                    lineStart,
                                    contentEnd,
                                )
                            }
                            stack += OpenBlock(
                                names = parsed.names,
                                fenceLength = marker.fenceLength,
                                validTime = parsed.validTime,
                                lineStart = lineStart,
                                contentStart = nextLine,
                                embed = parsed.embed,
                            )
                        }
                    }
                }
            }
            if (newline == masked.length) break
            lineStart = nextLine
        }

        stack.forEach { open ->
            diagnostics += syntaxDiagnostic(
                "Unclosed block fence ${open.fenceLength}",
                sourcePath,
                documentId,
                open.lineStart,
                body.length,
            )
        }
        return BodyBlockParsing(
            blocks.sortedWith(compareBy<ExtractedBodyBlock> { it.range.start }.thenByDescending { it.range.end }),
            diagnostics,
        )
    }

    private fun syntaxDiagnostic(
        message: String,
        sourcePath: String,
        documentId: String,
        start: Int,
        end: Int,
    ): Diagnostic = Diagnostic(
        category = DiagnosticCategory.SyntaxError,
        severity = Severity.Error,
        message = message,
        source = SourceInfo(sourcePath, documentId, SourceRange(start, end)),
    )
}

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

internal fun parseBodyBlockLineMarker(
    masked: String,
    original: String,
    lineStart: Int,
    lineEnd: Int,
): BodyBlockLineMarker? {
    var cursor = lineStart
    var spaces = 0
    while (cursor < lineEnd && masked[cursor] == ' ') {
        cursor++
        spaces++
    }
    if (spaces > 3 || masked.getOrNull(cursor) != ':') return null
    val fenceStart = cursor
    while (cursor < lineEnd && masked[cursor] == ':') cursor++
    val fenceLength = cursor - fenceStart
    if (fenceLength < 3) return null
    if (cursor == lineEnd || masked.substring(cursor, lineEnd).isBlank()) {
        return BodyBlockLineMarker(fenceLength, null, null)
    }
    if (masked[cursor] != ' ' && masked[cursor] != '\t') return null
    while (cursor < lineEnd && (masked[cursor] == ' ' || masked[cursor] == '\t')) cursor++
    if (cursor == lineEnd) return BodyBlockLineMarker(fenceLength, null, null)
    val header = original.substring(cursor, lineEnd).trimEnd()
    return BodyBlockLineMarker(fenceLength, header, cursor)
}

internal data class ParsedInlineValidTime(
    val validTime: List<ValidTime>,
    val timelineReferences: List<InlineTimelineReference>,
)

internal fun parseInlineValidTimeArgument(text: String): ParsedInlineValidTime {
    val parser = InlinePropsParser(text)
    val raw = parser.parseValidTimeArgumentValue()
    return ParsedInlineValidTime(
        validTime = raw.values.map(::rawValidTime),
        timelineReferences = parser.timelineReferences,
    )
}

internal fun validTimesToRawArray(validTimes: List<ValidTime>): RawArray =
    RawArray(validTimes.map { validTime ->
        RawObject(
            buildMap {
                put("timeline", RawString(validTime.timeline))
                validTime.from?.let { put("from", rawTimePoint(it)) }
                validTime.to?.let { put("to", rawTimePoint(it)) }
            },
        )
    })

private fun rawValidTime(raw: RawValue): ValidTime {
    val objectValue = raw as? RawObject ?: throw InlinePropsParseException("validTime entry must be an object")
    return ValidTime(
        timeline = (objectValue.values["timeline"] as? RawString)?.value
            ?: throw InlinePropsParseException("validTime timeline must be a string"),
        from = objectValue.values["from"]?.let(::rawTimePoint),
        to = objectValue.values["to"]?.let(::rawTimePoint),
    )
}

private fun rawTimePoint(raw: RawValue): TimePoint {
    val objectValue = raw as? RawObject ?: throw InlinePropsParseException("timePoint must be an object")
    val timecode = when (val value = objectValue.values["timecode"]) {
        is RawInteger -> value.value.toDouble()
        is RawNumber -> value.value
        else -> throw InlinePropsParseException("timePoint.timecode must be number")
    }
    return TimePoint(timecode, (objectValue.values["value"] as? RawString)?.value)
}

private fun rawTimePoint(point: TimePoint): RawObject =
    RawObject(
        buildMap {
            put("timecode", RawNumber(point.timecode))
            point.value?.let { put("value", RawString(it)) }
        },
    )
