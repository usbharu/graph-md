package dev.usbharu.graphmd.query.ir

import dev.usbharu.graphmd.core.model.ExtractedBodyBlock
import dev.usbharu.graphmd.core.model.SourceRange
import dev.usbharu.graphmd.core.model.ValidTime

internal data class ExtractedText(
    val kind: TextKind,
    val text: String,
    val range: SourceRange,
    val validTime: List<ValidTime> = emptyList(),
)

/**
 * A deliberately small Markdown fragmenter. It does not interpret GraphMD
 * semantics; it only preserves meaningful searchable blocks and source ranges.
 */
internal object MarkdownTextExtractor {
    fun extract(
        source: String,
        bodyBlocks: List<ExtractedBodyBlock> = emptyList(),
    ): List<ExtractedText> {
        val bodyOffset = bodyStart(source)
        val markerLineStarts = buildSet {
            bodyBlocks.forEach { block ->
                add(bodyOffset + block.range.start)
                add(bodyOffset + block.contentRange.end)
            }
        }

        fun validTimeAt(absoluteOffset: Int): List<ValidTime> {
            val relativeBodyOffset = absoluteOffset - bodyOffset
            return bodyBlocks.asSequence()
                .filter { block ->
                    block.validTime.isNotEmpty() &&
                        relativeBodyOffset >= block.contentRange.start &&
                        relativeBodyOffset < block.contentRange.end
                }
                .maxByOrNull { it.contentRange.start }
                ?.validTime
                .orEmpty()
        }

        fun insideEmbed(absoluteOffset: Int): Boolean {
            val relativeBodyOffset = absoluteOffset - bodyOffset
            return bodyBlocks.any { block ->
                block.embed != null && relativeBodyOffset >= block.contentRange.start && relativeBodyOffset < block.contentRange.end
            }
        }

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
                result += ExtractedText(
                    TextKind.PARAGRAPH,
                    text,
                    SourceRange(paragraphStart, end),
                    validTimeAt(paragraphStart),
                )
            }
            paragraphStart = -1
            paragraph.clear()
        }

        fun flushCode(end: Int) {
            if (codeStart < 0) return
            val text = code.toString().trimEnd()
            if (text.isNotEmpty()) {
                result += ExtractedText(
                    TextKind.CODE,
                    text,
                    SourceRange(codeStart, end),
                    validTimeAt(codeStart),
                )
            }
            codeStart = -1
            code.clear()
        }

        var offset = bodyOffset
        while (offset < source.length) {
            val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
            val rawLine = source.substring(offset, lineEnd)
            val trimmed = rawLine.trim()
            if (offset in markerLineStarts || insideEmbed(offset)) {
                flushParagraph(offset)
            } else if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
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
                val heading = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""").matchEntire(trimmed)
                when {
                    heading != null -> {
                        flushParagraph(offset)
                        val text = heading.groupValues[2]
                        val textStart = offset + rawLine.indexOf(text)
                        result += ExtractedText(
                            if (heading.groupValues[1].length == 1) TextKind.TITLE else TextKind.HEADING,
                            text,
                            SourceRange(textStart, textStart + text.length),
                            validTimeAt(textStart),
                        )
                    }
                    trimmed.isEmpty() -> flushParagraph(offset)
                    else -> {
                        if (paragraphStart < 0) paragraphStart = offset
                        if (paragraph.isNotEmpty()) paragraph.append('\n')
                        paragraph.append(rawLine.trim())
                    }
                }
            }
            offset = (lineEnd + 1).coerceAtMost(source.length)
            if (lineEnd == source.length) break
        }
        if (inCode) flushCode(source.length) else flushParagraph(source.length)
        return result
    }

    fun bodyStart(source: String): Int {
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
}
