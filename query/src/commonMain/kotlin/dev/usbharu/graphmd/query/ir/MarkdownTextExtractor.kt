package dev.usbharu.graphmd.query.ir

import dev.usbharu.graphmd.core.model.SourceRange

internal data class ExtractedText(
    val kind: TextKind,
    val text: String,
    val range: SourceRange,
)

internal data class MarkdownLinkReplacement(
    val bodyRange: SourceRange,
    val text: String,
)

/**
 * A deliberately small Markdown fragmenter. GraphMD link replacements are
 * supplied by the compiler-backed caller so searchable text matches the
 * visible document while source ranges continue to refer to the original.
 */
internal object MarkdownTextExtractor {
    fun extract(source: String, linkTitles: List<MarkdownLinkReplacement> = emptyList()): List<ExtractedText> {
        val bodyStart = frontMatterEnd(source)
        val replacements = linkTitles.mapNotNull { replacement ->
            val start = bodyStart + replacement.bodyRange.start
            val end = bodyStart + replacement.bodyRange.end
            if (start < bodyStart || start >= end || end > source.length) {
                null
            } else {
                AbsoluteLinkReplacement(start, end, replacement.text)
            }
        }.sortedBy { it.start }
        val result = mutableListOf<ExtractedText>()
        var paragraphStart = -1
        val paragraph = StringBuilder()
        var inCode = false
        var codeStart = -1
        val code = StringBuilder()

        fun flushParagraph(end: Int) {
            if (paragraphStart < 0) return
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) {
                result += ExtractedText(TextKind.PARAGRAPH, text, SourceRange(paragraphStart, end))
            }
            paragraphStart = -1
            paragraph.clear()
        }

        fun flushCode(end: Int) {
            if (codeStart < 0) return
            val text = code.toString().trimEnd()
            if (text.isNotEmpty()) {
                result += ExtractedText(TextKind.CODE, text, SourceRange(codeStart, end))
            }
            codeStart = -1
            code.clear()
        }

        var offset = bodyStart
        while (offset < source.length) {
            val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
            val rawLine = source.substring(offset, lineEnd)
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (inCode) {
                    flushCode(lineEnd)
                    inCode = false
                } else {
                    flushParagraph(offset)
                    inCode = true
                    codeStart = (lineEnd + 1).coerceAtMost(source.length)
                }
            } else if (inCode) {
                code.append(rawLine)
                if (lineEnd < source.length) code.append('\n')
            } else {
                val visibleLine = replaceLinkTitles(source, offset, lineEnd, replacements)
                val visibleTrimmed = visibleLine.trim()
                val heading = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""").matchEntire(visibleTrimmed)
                when {
                    heading != null -> {
                        flushParagraph(offset)
                        val text = heading.groupValues[2]
                        val textStart = offset + visibleLine.indexOf(text).coerceAtLeast(0)
                        result += ExtractedText(
                            if (heading.groupValues[1].length == 1) TextKind.TITLE else TextKind.HEADING,
                            text,
                            SourceRange(textStart, textStart + text.length),
                        )
                    }
                    visibleTrimmed.isEmpty() -> flushParagraph(offset)
                    else -> {
                        if (paragraphStart < 0) paragraphStart = offset
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(visibleLine.trim())
                    }
                }
            }
            offset = (lineEnd + 1).coerceAtMost(source.length)
            if (lineEnd == source.length) break
        }
        if (inCode) flushCode(source.length) else flushParagraph(source.length)
        return result
    }

    private fun replaceLinkTitles(
        source: String,
        lineStart: Int,
        lineEnd: Int,
        replacements: List<AbsoluteLinkReplacement>,
    ): String {
        if (replacements.isEmpty()) return source.substring(lineStart, lineEnd)
        val lineReplacements = replacements.filter { it.end > lineStart && it.start < lineEnd }
        if (lineReplacements.isEmpty()) return source.substring(lineStart, lineEnd)

        val visible = StringBuilder()
        var cursor = lineStart
        lineReplacements.forEach { replacement ->
            val replacementStart = maxOf(replacement.start, lineStart)
            val replacementEnd = minOf(replacement.end, lineEnd)
            if (replacement.start >= lineStart) {
                visible.append(source.substring(cursor, replacementStart))
                visible.append(replacement.text)
            }
            cursor = maxOf(cursor, replacementEnd)
        }
        visible.append(source.substring(cursor, lineEnd))
        return visible.toString()
    }

    private fun frontMatterEnd(source: String): Int {
        if (!source.startsWith("---")) return 0
        val firstLineEnd = source.indexOf('\n')
        if (firstLineEnd < 0 || source.substring(0, firstLineEnd).trim() != "---") return 0
        var offset = firstLineEnd + 1
        while (offset < source.length) {
            val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
            if (source.substring(offset, lineEnd).trim() == "---") {
                return (lineEnd + 1).coerceAtMost(source.length)
            }
            offset = (lineEnd + 1).coerceAtMost(source.length)
        }
        return 0
    }

    private data class AbsoluteLinkReplacement(
        val start: Int,
        val end: Int,
        val text: String,
    )
}
