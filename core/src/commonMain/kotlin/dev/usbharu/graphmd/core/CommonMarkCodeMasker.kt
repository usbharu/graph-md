package dev.usbharu.graphmd.core

/**
 * Replaces CommonMark code blocks and code spans with spaces without changing
 * source length. Newlines are retained so offsets and line structure stay
 * identical to the original document.
 *
 * This is deliberately split into a block phase and an inline phase. The block
 * phase keeps the ordered block quote/list container stack and identifies the
 * leaf blocks that can contain inline syntax. Code spans are then matched only
 * within one such leaf block.
 */
internal object CommonMarkCodeMasker {
    fun mask(source: String): String {
        if (source.isEmpty()) return source

        val masked = BooleanArray(source.length)
        val inlineBlocks = scanBlocks(source, source.linesWithOffsets(), masked)
        inlineBlocks.forEach { maskCodeSpans(source, it, masked) }

        val result = source.toCharArray()
        masked.forEachIndexed { index, isMasked ->
            if (isMasked && result[index] != '\n' && result[index] != '\r') result[index] = ' '
        }
        return result.concatToString()
    }

    private fun scanBlocks(source: String, lines: List<Line>, masked: BooleanArray): List<TextRange> {
        val inlineBlocks = mutableListOf<TextRange>()
        var containers = emptyList<Container>()
        var paragraph: Paragraph? = null
        var fence: Fence? = null
        var indentedCodeContainers: List<Container>? = null
        var nextContainerId = 0
        var lineIndex = 0

        fun finishParagraph() {
            paragraph?.let { inlineBlocks += TextRange(it.start, it.end) }
            paragraph = null
        }

        while (lineIndex < lines.size) {
            val line = lines[lineIndex]

            fence?.let { activeFence ->
                val contentOffset = consumeFenceContainerStack(source, line, activeFence.containers)
                if (contentOffset == null) {
                    fence = null
                    containers = commonContainerPrefix(containers, activeFence.containers)
                    continue
                }
                masked.fill(true, line.start, line.end)
                val markerOffset = skipUpToThreeSpaces(source, contentOffset, line.contentEnd)
                if (isClosingFence(source, markerOffset, line.contentEnd, activeFence)) {
                    fence = null
                    containers = activeFence.containers
                }
                lineIndex++
                continue
            }

            indentedCodeContainers?.let { codeContainers ->
                val contentOffset = consumeContainerStack(source, line, codeContainers)
                when {
                    line.isBlank(source) -> {
                        masked.fill(true, line.start, line.end)
                        lineIndex++
                        continue
                    }
                    contentOffset != null && indentationColumns(source, contentOffset, line.contentEnd) >= 4 -> {
                        masked.fill(true, line.start, line.end)
                        lineIndex++
                        continue
                    }
                    else -> {
                        indentedCodeContainers = null
                        containers = codeContainers
                    }
                }
            }

            if (line.isBlank(source)) {
                finishParagraph()
                lineIndex++
                continue
            }

            val priorContainers = containers
            val priorParagraph = paragraph
            val matched = matchExistingContainers(
                source,
                line,
                priorContainers,
                priorParagraph != null,
            )
            var contentOffset = matched.offset
            var lineContainers = matched.containers
            var canContinueParagraph =
                priorParagraph != null && matched.lazyOrComplete && lineContainers == priorContainers

            while (contentOffset < line.contentEnd) {
                val markerOffset = skipUpToThreeSpaces(source, contentOffset, line.contentEnd)
                if (source.getOrNull(markerOffset) == '>') {
                    finishParagraph()
                    var after = markerOffset + 1
                    if (after < line.contentEnd && (source[after] == ' ' || source[after] == '\t')) after++
                    lineContainers = lineContainers + Container.Quote(nextContainerId++)
                    contentOffset = after
                    canContinueParagraph = false
                    continue
                }

                val listMarker = parseListMarker(source, markerOffset, line.contentEnd)
                val listCanInterrupt = listMarker != null &&
                    (
                        !canContinueParagraph ||
                            (!listMarker.empty && (!listMarker.ordered || listMarker.startNumber == 1))
                        )
                if (listCanInterrupt) {
                    finishParagraph()
                    lineContainers = lineContainers + Container.ListItem(listMarker.indentColumns, nextContainerId++)
                    contentOffset = listMarker.contentOffset
                    canContinueParagraph = false
                    continue
                }
                break
            }

            containers = lineContainers
            if (line.isBlankFrom(source, contentOffset)) {
                finishParagraph()
                lineIndex++
                continue
            }

            val leafOffset = skipUpToThreeSpaces(source, contentOffset, line.contentEnd)
            val openingFence = parseOpeningFence(source, leafOffset, line.contentEnd, lineContainers)
            if (openingFence != null) {
                finishParagraph()
                masked.fill(true, line.start, line.end)
                fence = openingFence
                lineIndex++
                continue
            }

            val indent = indentationColumns(source, contentOffset, line.contentEnd)
            if (indent >= 4 && !canContinueParagraph) {
                finishParagraph()
                masked.fill(true, line.start, line.end)
                indentedCodeContainers = lineContainers
                lineIndex++
                continue
            }

            if (isAtxHeading(source, leafOffset, line.contentEnd)) {
                finishParagraph()
                inlineBlocks += TextRange(leafOffset, line.contentEnd)
                lineIndex++
                continue
            }
            if (canContinueParagraph && priorParagraph != null &&
                isSetextUnderline(source, leafOffset, line.contentEnd)
            ) {
                finishParagraph()
                lineIndex++
                continue
            }
            if (isThematicBreak(source, leafOffset, line.contentEnd)) {
                finishParagraph()
                lineIndex++
                continue
            }

            paragraph = if (canContinueParagraph && priorParagraph != null) {
                priorParagraph.copy(end = line.end)
            } else {
                finishParagraph()
                Paragraph(contentOffset, line.end)
            }
            lineIndex++
        }

        finishParagraph()
        return inlineBlocks
    }

    private fun matchExistingContainers(
        source: String,
        line: Line,
        containers: List<Container>,
        paragraphOpen: Boolean,
    ): ContainerMatch {
        var offset = line.start
        for ((index, container) in containers.withIndex()) {
            val consumed = when (container) {
                is Container.Quote -> consumeQuote(source, offset, line.contentEnd)
                is Container.ListItem -> consumeIndentation(source, offset, line.contentEnd, container.indentColumns)
            }
            if (consumed != null) {
                offset = consumed
                continue
            }
            if (paragraphOpen && !startsInterruptingBlock(source, offset, line.contentEnd)) {
                return ContainerMatch(offset, containers, lazyOrComplete = true)
            }
            return ContainerMatch(offset, containers.take(index), lazyOrComplete = false)
        }
        return ContainerMatch(offset, containers, lazyOrComplete = true)
    }

    private fun startsInterruptingBlock(source: String, start: Int, end: Int): Boolean {
        val offset = skipUpToThreeSpaces(source, start, end)
        if (offset >= end) return true
        if (source[offset] == '>') return true
        if (parseOpeningFence(source, offset, end, emptyList()) != null) return true
        if (isAtxHeading(source, offset, end) || isThematicBreak(source, offset, end)) return true
        val list = parseListMarker(source, offset, end) ?: return false
        return !list.empty && (!list.ordered || list.startNumber == 1)
    }

    private fun consumeContainerStack(source: String, line: Line, containers: List<Container>): Int? {
        var offset = line.start
        for (container in containers) {
            offset = when (container) {
                is Container.Quote -> consumeQuote(source, offset, line.contentEnd)
                is Container.ListItem -> consumeIndentation(source, offset, line.contentEnd, container.indentColumns)
            } ?: return null
        }
        return offset
    }

    private fun consumeFenceContainerStack(source: String, line: Line, containers: List<Container>): Int? {
        var offset = line.start
        for (container in containers) {
            val consumed = when (container) {
                is Container.Quote -> consumeQuote(source, offset, line.contentEnd)
                is Container.ListItem ->
                    consumeIndentation(source, offset, line.contentEnd, container.indentColumns)
                        ?: offset.takeIf { line.isBlankFrom(source, offset) }
            } ?: return null
            offset = consumed
        }
        return offset
    }

    private fun consumeQuote(source: String, start: Int, end: Int): Int? {
        val marker = skipUpToThreeSpaces(source, start, end)
        if (marker >= end || source[marker] != '>') return null
        var after = marker + 1
        if (after < end && (source[after] == ' ' || source[after] == '\t')) after++
        return after
    }

    private fun parseListMarker(source: String, start: Int, end: Int): ListMarker? {
        if (start >= end) return null
        var cursor = start
        var ordered = false
        var startNumber = 1
        when {
            source[cursor] == '-' || source[cursor] == '+' || source[cursor] == '*' -> cursor++
            source[cursor].isDigit() -> {
                ordered = true
                val numberStart = cursor
                while (cursor < end && cursor - numberStart < 9 && source[cursor].isDigit()) cursor++
                if (cursor == numberStart || cursor >= end || (source[cursor] != '.' && source[cursor] != ')')) return null
                startNumber = source.substring(numberStart, cursor).toIntOrNull() ?: return null
                cursor++
            }
            else -> return null
        }
        val markerWidth = cursor - start
        if (cursor == end) {
            return ListMarker(
                contentOffset = end,
                indentColumns = markerWidth + 1,
                ordered = ordered,
                startNumber = startNumber,
                empty = true,
            )
        }
        if (cursor >= end || (source[cursor] != ' ' && source[cursor] != '\t')) return null

        val markerEnd = cursor
        var spaces = 0
        while (cursor < end && source[cursor] == ' ') {
            cursor++
            spaces++
        }
        if (cursor < end && source[cursor] == '\t') cursor++
        val contentOffset = if (spaces > 4) markerEnd + 1 else cursor
        val empty = contentOffset >= end
        return ListMarker(
            contentOffset = contentOffset,
            indentColumns = if (empty) markerWidth + 1 else columnWidth(source, start, contentOffset),
            ordered = ordered,
            startNumber = startNumber,
            empty = empty,
        )
    }

    private fun parseOpeningFence(
        source: String,
        offset: Int,
        end: Int,
        containers: List<Container>,
    ): Fence? {
        val marker = source.getOrNull(offset) ?: return null
        if (marker != '`' && marker != '~') return null
        var cursor = offset
        while (cursor < end && source[cursor] == marker) cursor++
        val length = cursor - offset
        if (length < 3) return null
        if (marker == '`' && source.substring(cursor, end).contains('`')) return null
        return Fence(marker, length, containers)
    }

    private fun isClosingFence(source: String, offset: Int, end: Int, opening: Fence): Boolean {
        if (source.getOrNull(offset) != opening.marker) return false
        var cursor = offset
        while (cursor < end && source[cursor] == opening.marker) cursor++
        if (cursor - offset < opening.length) return false
        while (cursor < end && (source[cursor] == ' ' || source[cursor] == '\t')) cursor++
        return cursor == end
    }

    private fun isAtxHeading(source: String, offset: Int, end: Int): Boolean {
        if (source.getOrNull(offset) != '#') return false
        var cursor = offset
        while (cursor < end && source[cursor] == '#' && cursor - offset < 6) cursor++
        return cursor == end || source[cursor] == ' ' || source[cursor] == '\t'
    }

    private fun isThematicBreak(source: String, offset: Int, end: Int): Boolean {
        val marker = source.getOrNull(offset) ?: return false
        if (marker != '*' && marker != '-' && marker != '_') return false
        var count = 0
        for (index in offset until end) {
            when (source[index]) {
                marker -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    private fun isSetextUnderline(source: String, offset: Int, end: Int): Boolean {
        val marker = source.getOrNull(offset) ?: return false
        if (marker != '=' && marker != '-') return false
        var count = 0
        for (index in offset until end) {
            when (source[index]) {
                marker -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count > 0
    }

    private fun maskCodeSpans(source: String, block: TextRange, masked: BooleanArray) {
        val runs = mutableListOf<BacktickRun>()
        var index = block.start
        while (index < block.end) {
            if (source[index] != '`') {
                index++
                continue
            }
            val runLength = backtickRunLength(source, index, block.end)
            runs += BacktickRun(index, runLength)
            index += runLength
        }

        val nextSameLength = IntArray(runs.size) { -1 }
        val nextByLength = mutableMapOf<Int, Int>()
        for (runIndex in runs.indices.reversed()) {
            val run = runs[runIndex]
            nextSameLength[runIndex] = nextByLength[run.length] ?: -1
            nextByLength[run.length] = runIndex
        }

        var runIndex = 0
        while (runIndex < runs.size) {
            if (isBackslashEscaped(source, runs[runIndex].start)) {
                runIndex++
                continue
            }
            val closingIndex = nextSameLength[runIndex]
            if (closingIndex < 0) {
                runIndex++
                continue
            }
            val opening = runs[runIndex]
            val closing = runs[closingIndex]
            masked.fill(true, opening.start, closing.start + closing.length)
            runIndex = closingIndex + 1
        }
    }

    private fun skipUpToThreeSpaces(source: String, start: Int, end: Int): Int {
        var cursor = start
        var count = 0
        while (cursor < end && source[cursor] == ' ' && count < 3) {
            cursor++
            count++
        }
        return cursor
    }

    private fun indentationColumns(source: String, start: Int, end: Int): Int {
        var columns = 0
        var cursor = start
        while (cursor < end) {
            when (source[cursor]) {
                ' ' -> columns++
                '\t' -> columns += 4 - (columns % 4)
                else -> return columns
            }
            cursor++
        }
        return columns
    }

    private fun consumeIndentation(source: String, start: Int, end: Int, requiredColumns: Int): Int? {
        var columns = 0
        var cursor = start
        while (cursor < end && columns < requiredColumns) {
            when (source[cursor]) {
                ' ' -> columns++
                '\t' -> columns += 4 - (columns % 4)
                else -> return null
            }
            cursor++
        }
        return cursor.takeIf { columns >= requiredColumns }
    }

    private fun columnWidth(source: String, start: Int, end: Int): Int {
        var columns = 0
        for (cursor in start until end) {
            columns = if (source[cursor] == '\t') columns + 4 - (columns % 4) else columns + 1
        }
        return columns
    }

    private fun isBackslashEscaped(source: String, index: Int): Boolean {
        var cursor = index - 1
        var count = 0
        while (cursor >= 0 && source[cursor] == '\\') {
            count++
            cursor--
        }
        return count % 2 == 1
    }

    private fun backtickRunLength(source: String, start: Int, end: Int): Int {
        var cursor = start
        while (cursor < end && source[cursor] == '`') cursor++
        return cursor - start
    }

    private fun commonContainerPrefix(first: List<Container>, second: List<Container>): List<Container> {
        var size = 0
        while (size < first.size && size < second.size && first[size] == second[size]) size++
        return first.take(size)
    }

    private fun String.linesWithOffsets(): List<Line> {
        val result = mutableListOf<Line>()
        var start = 0
        while (start < length) {
            var contentEnd = start
            while (contentEnd < length && this[contentEnd] != '\n' && this[contentEnd] != '\r') contentEnd++
            var end = contentEnd
            if (end < length && this[end] == '\r') end++
            if (end < length && this[end] == '\n') end++
            result += Line(start, contentEnd, end)
            start = end
        }
        return result
    }

    private sealed interface Container {
        val id: Int

        data class Quote(override val id: Int) : Container
        data class ListItem(val indentColumns: Int, override val id: Int) : Container
    }

    private data class Fence(
        val marker: Char,
        val length: Int,
        val containers: List<Container>,
    )

    private data class Paragraph(val start: Int, val end: Int)
    private data class TextRange(val start: Int, val end: Int)
    private data class ContainerMatch(
        val offset: Int,
        val containers: List<Container>,
        val lazyOrComplete: Boolean,
    )

    private data class ListMarker(
        val contentOffset: Int,
        val indentColumns: Int,
        val ordered: Boolean,
        val startNumber: Int,
        val empty: Boolean,
    )

    private data class BacktickRun(val start: Int, val length: Int)

    private data class Line(val start: Int, val contentEnd: Int, val end: Int) {
        fun isBlank(source: String): Boolean = isBlankFrom(source, start)

        fun isBlankFrom(source: String, offset: Int): Boolean =
            (offset until contentEnd).all { source[it] == ' ' || source[it] == '\t' }
    }
}

/**
 * Masks CommonMark code blocks and code spans with spaces while preserving offsets.
 */
fun maskCommonMarkCodeRegions(source: String): String = CommonMarkCodeMasker.mask(source)
