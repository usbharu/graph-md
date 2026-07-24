package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

enum class ReferenceTargetKind {
    Node,
    NodeType,
    RelType,
    Timeline,
}

data class SymbolDefinition(
    val id: String,
    val kind: ReferenceTargetKind,
    val range: SourceRange,
)

data class SymbolReference(
    val targetId: String,
    val kind: ReferenceTargetKind,
    val field: String,
    val range: SourceRange,
)

data class GraphDocumentAnalysis(
    val text: String,
    val parsed: ParsedGraphDocumentResult,
    val frontMatterEndOffset: Int,
    val definitions: List<SymbolDefinition>,
    val references: List<SymbolReference>,
)

class GraphDocumentAnalyzer {
    private val parser = GraphDocumentParser()

    fun analyze(text: String, sourcePath: String): GraphDocumentAnalysis {
        val normalized = text.replace("\r\n", "\n")
        val parsed = parser.parseDocument(normalized, sourcePath)
        val document = parsed.document
        val lines = normalized.split('\n')
        if (lines.firstOrNull() != "---") {
            return GraphDocumentAnalysis(normalized, parsed, 0, emptyList(), emptyList())
        }
        val endLine = lines.drop(1).indexOfFirst { it == "---" || it == "..." }.let { if (it >= 0) it + 1 else -1 }
        if (endLine < 0) {
            return GraphDocumentAnalysis(normalized, parsed, 0, emptyList(), emptyList())
        }
        val lineStarts = computeLineStarts(normalized)
        val definitions = mutableListOf<SymbolDefinition>()
        val references = mutableListOf<SymbolReference>()
        var currentListField: String? = null

        for (lineIndex in 1 until endLine) {
            val line = lines[lineIndex]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue

            val inlineList = Regex("""^([A-Za-z][A-Za-z0-9_-]*)\s*:\s*\[(.*)\]\s*$""").matchEntire(line)
            if (inlineList != null) {
                val field = inlineList.groupValues[1]
                currentListField = null
                val kind = listFieldKind(field, document)
                if (kind != null) {
                    val listStart = line.indexOf('[') + 1
                    collectInlineListReferences(
                        rawItems = inlineList.groupValues[2],
                        absoluteListStart = lineStarts[lineIndex] + listStart,
                        field = field,
                        kind = kind,
                        references = references,
                    )
                }
                continue
            }

            val mapping = Regex("""^([A-Za-z][A-Za-z0-9_-]*)\s*:\s*(.*?)\s*$""").matchEntire(line)
            if (mapping != null && !trimmed.startsWith("- ")) {
                val field = mapping.groupValues[1]
                val value = mapping.groupValues[2]
                currentListField = if (value.isEmpty()) field else null
                val colonIndex = line.indexOf(':')
                val valueStartInLine = line.substring(colonIndex + 1).indexOfFirst { !it.isWhitespace() }
                    .takeIf { it >= 0 }?.plus(colonIndex + 1)
                if (valueStartInLine != null) {
                    val range = SourceRange(
                        start = lineStarts[lineIndex] + valueStartInLine,
                        end = lineStarts[lineIndex] + valueStartInLine + value.length,
                    )
                    when (field) {
                        "id" -> document?.id?.let { id ->
                            definitions += SymbolDefinition(id, definitionKind(document), range)
                        }
                        "type" -> if (document is NodeDocument) {
                            references += SymbolReference(stripYamlScalar(value), ReferenceTargetKind.NodeType, field, range)
                        }
                        "extends", "from", "to", "timeline" -> {
                            listFieldKind(field, document)?.let { kind ->
                                references += SymbolReference(stripYamlScalar(value), kind, field, range)
                            }
                        }
                    }
                }
                continue
            }

            if (currentListField != null && Regex("""^\s*-\s+""").containsMatchIn(line)) {
                val match = Regex("""^(\s*-\s+)(.*?)\s*$""").matchEntire(line) ?: continue
                val field = currentListField
                val kind = listFieldKind(field, document) ?: continue
                val rawValue = match.groupValues[2]
                val itemStart = lineStarts[lineIndex] + match.groupValues[1].length
                references += SymbolReference(
                    targetId = stripYamlScalar(rawValue),
                    kind = kind,
                    field = field,
                    range = SourceRange(itemStart, itemStart + rawValue.length),
                )
                continue
            }

            if (line.firstOrNull()?.isWhitespace() == false) {
                currentListField = null
            }
        }

        collectTimelineReferences(lines, lineStarts, endLine, document, references)

        if (document is NodeDocument) {
            val bodyOffset = lineStarts[endLine] + lines[endLine].length + 1
            references += extractBodyReferences(document.body, bodyOffset)
            references += extractInlineTimelineReferences(document.body, bodyOffset)
        }

        val frontMatterEndOffset = lineStarts[endLine] + lines[endLine].length + 1
        return GraphDocumentAnalysis(normalized, parsed, frontMatterEndOffset, definitions, references)
    }

    fun findReferenceAt(analysis: GraphDocumentAnalysis, offset: Int): SymbolReference? {
        return analysis.references.firstOrNull { offset in it.range.start..it.range.end }
    }

    fun findDefinitionAt(analysis: GraphDocumentAnalysis, offset: Int): SymbolDefinition? {
        return analysis.definitions.firstOrNull { offset in it.range.start..it.range.end }
    }

    fun inferCompletionKind(analysis: GraphDocumentAnalysis, offset: Int): ReferenceTargetKind? {
        val document = analysis.parsed.document ?: return null
        return if (offset < analysis.frontMatterEndOffset) {
            inferFrontMatterCompletionKind(analysis.text, offset, document.kind)
        } else {
            inferBodyCompletionKind(analysis.text, offset)
        }
    }

    private fun inferFrontMatterCompletionKind(text: String, offset: Int, kind: DocumentKind): ReferenceTargetKind? {
        val lines = text.split('\n')
        val lineStarts = computeLineStarts(text)
        val lineIndex = lineStarts.indexOfLast { it <= offset }.coerceAtLeast(0)
        val line = lines.getOrNull(lineIndex) ?: return null
        val trimmed = line.trim()
        val mapping = Regex("""^([A-Za-z][A-Za-z0-9_-]*)\s*:\s*(.*?)\s*$""").matchEntire(line)
        if (mapping != null && !trimmed.startsWith("- ")) {
            val field = mapping.groupValues[1]
            val valueStart = lineStarts[lineIndex] + line.indexOf(':') + 1
            if (offset >= valueStart) {
                return when (field) {
                    "type" -> ReferenceTargetKind.NodeType
                    "extends" -> when (kind) {
                        DocumentKind.RelType -> ReferenceTargetKind.RelType
                        DocumentKind.Timeline -> ReferenceTargetKind.Timeline
                        else -> ReferenceTargetKind.NodeType
                    }
                    "from", "to" -> if (kind == DocumentKind.Timeline) ReferenceTargetKind.Timeline else ReferenceTargetKind.NodeType
                    else -> null
                }
            }
        }
        if (Regex("""^\s*-\s*""").containsMatchIn(line)) {
            for (previous in lineIndex - 1 downTo 1) {
                val previousLine = lines.getOrNull(previous) ?: continue
                if (previousLine.trim().isBlank()) continue
                val match = Regex("""^([A-Za-z][A-Za-z0-9_-]*)\s*:\s*$""").matchEntire(previousLine)
                if (match != null) {
                    return when (match.groupValues[1]) {
                        "extends" -> when (kind) {
                            DocumentKind.RelType -> ReferenceTargetKind.RelType
                            DocumentKind.Timeline -> ReferenceTargetKind.Timeline
                            else -> ReferenceTargetKind.NodeType
                        }
                        "from", "to" -> if (kind == DocumentKind.Timeline) ReferenceTargetKind.Timeline else ReferenceTargetKind.NodeType
                        else -> null
                    }
                }
                if (previousLine.firstOrNull()?.isWhitespace() == false) break
            }
        }
        return null
    }

    private fun inferBodyCompletionKind(text: String, offset: Int): ReferenceTargetKind? {
        val textBeforeCursor = text.substring(0, offset.coerceIn(0, text.length))
        val validTimeMarker = Regex("""validTime\s*=\s*""").findAll(textBeforeCursor).lastOrNull()
        if (validTimeMarker != null) {
            val between = text.substring(validTimeMarker.range.last + 1, offset.coerceAtMost(text.length))
            if ('\n' !in between && '}' !in between && between.count { it == '(' } >= between.count { it == ')' }) {
                return ReferenceTargetKind.Timeline
            }
        }
        val openParen = text.lastIndexOf('(', startIndex = offset)
        val closeParen = if (openParen >= 0) text.indexOf(')', startIndex = openParen) else -1
        if (openParen < 0 || closeParen < 0 || offset > closeParen) return null
        val labelOpen = text.lastIndexOf('[', startIndex = openParen)
        if (labelOpen < 0) return null
        val oldRelation = labelOpen > 0 && text[labelOpen - 1] == '@'
        val canonicalStart = text.lastIndexOf("@link", startIndex = labelOpen)
        if (!oldRelation && (canonicalStart < 0 || text.substring(canonicalStart, labelOpen).any { it == '\n' })) return null
        val inner = text.substring(openParen + 1, closeParen)
        val relativeOffset = offset - openParen - 1
        val firstWhitespace = inner.indexOfFirst { it.isWhitespace() }
        return if (firstWhitespace < 0 || relativeOffset <= firstWhitespace) {
            ReferenceTargetKind.Node
        } else {
            ReferenceTargetKind.RelType
        }
    }

    private fun extractBodyReferences(body: String, baseOffset: Int): List<SymbolReference> {
        val refs = mutableListOf<SymbolReference>()
        val masked = maskCodeRegions(body)
        var index = 0
        while (index < masked.length) {
            if (masked[index] == '@' && !isEscaped(masked, index)) {
                val labelOpen = when {
                    masked.getOrNull(index + 1) == '[' -> index + 1
                    masked.startsWith("@link", index) -> canonicalLinkLabelOpen(masked, index)
                    else -> null
                }
                if (labelOpen == null) {
                    index += 1
                    continue
                }
                val closeLabel = findUnescaped(masked, ']', labelOpen + 1)
                if (closeLabel != null && masked.getOrNull(closeLabel + 1) == '(') {
                    val closeParen = findUnescaped(masked, ')', closeLabel + 2)
                    if (closeParen != null) {
                        val raw = body.substring(closeLabel + 2, closeParen)
                        val parsed = RelationTargetParser.parse(raw)
                        if (parsed != null) {
                            val target = parsed.first
                            val relType = parsed.second
                            val targetStart = closeLabel + 2 + raw.indexOf(target)
                            val relTypeToken = raw.substring(raw.indexOfFirst { it == ' ' || it == '\t' }).trim()
                            val relTypeStart = closeLabel + 2 + raw.lastIndexOf(relTypeToken)
                            refs += SymbolReference(target, ReferenceTargetKind.Node, "relation.target", SourceRange(baseOffset + targetStart, baseOffset + targetStart + target.length))
                            refs += SymbolReference(relType, ReferenceTargetKind.RelType, "relation.type", SourceRange(baseOffset + relTypeStart, baseOffset + relTypeStart + relTypeToken.length))
                        }
                        index = closeParen + 1
                        continue
                    }
                }
            }
            index += 1
        }
        return refs
    }

    private fun canonicalLinkLabelOpen(text: String, start: Int): Int? {
        var cursor = start + "@link".length
        if (text.getOrNull(cursor) == '(') {
            cursor = readBalancedEnd(text, cursor, '(', ')') ?: return null
        }
        if (text.getOrNull(cursor) == '{') {
            cursor = readBalancedEnd(text, cursor, '{', '}') ?: return null
        }
        return cursor.takeIf { text.getOrNull(it) == '[' }
    }

    private fun readBalancedEnd(text: String, start: Int, open: Char, close: Char): Int? {
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
                    open -> depth += 1
                    close -> if (--depth == 0) return index + 1
                }
            }
        }
        return null
    }

    private fun maskCodeRegions(body: String): String {
        val chars = body.toCharArray()
        var index = 0
        var lineStart = true
        while (index < chars.size) {
            if (lineStart && body.startsWith("```", index)) {
                val end = body.indexOf("\n```", index + 3).let { if (it >= 0) it + 4 else chars.size }
                for (position in index until minOf(end, chars.size)) chars[position] = ' '
                index = end
                lineStart = true
                continue
            }
            if (chars[index] == '`') {
                val end = body.indexOf('`', index + 1).let { if (it >= 0) it else chars.size - 1 }
                for (position in index..end) chars[position] = ' '
                index = end + 1
                lineStart = false
                continue
            }
            lineStart = chars[index] == '\n'
            index += 1
        }
        return chars.concatToString()
    }

    private fun findUnescaped(text: String, target: Char, start: Int): Int? {
        var escaped = false
        var index = start
        while (index < text.length) {
            val char = text[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '\n') {
                return null
            } else if (char == target) {
                return index
            }
            index += 1
        }
        return null
    }

    private fun isEscaped(text: String, index: Int): Boolean {
        var slashCount = 0
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] == '\\') {
            slashCount += 1
            cursor -= 1
        }
        return slashCount % 2 == 1
    }

    private fun computeLineStarts(text: String): List<Int> {
        val starts = mutableListOf(0)
        text.forEachIndexed { index, char ->
            if (char == '\n') starts += index + 1
        }
        return starts
    }

    private fun collectInlineListReferences(
        rawItems: String,
        absoluteListStart: Int,
        field: String,
        kind: ReferenceTargetKind,
        references: MutableList<SymbolReference>,
    ) {
        var cursor = 0
        rawItems.split(',').forEach { chunk ->
            val value = chunk.trim()
            val rawIndex = rawItems.indexOf(chunk, cursor)
            cursor = rawIndex + chunk.length
            if (value.isEmpty()) return@forEach
            val valueIndex = chunk.indexOf(value)
            val start = absoluteListStart + rawIndex + valueIndex
            references += SymbolReference(stripYamlScalar(value), kind, field, SourceRange(start, start + value.length))
        }
    }

    private fun collectTimelineReferences(
        lines: List<String>,
        lineStarts: List<Int>,
        endLine: Int,
        document: GraphDocument?,
        references: MutableList<SymbolReference>,
    ) {
        for (lineIndex in 1 until endLine) {
            val line = lines[lineIndex]
            val match = Regex("""^(\s*)(?:-\s*)?(timeline|from|to):\s*([A-Za-z_][A-Za-z0-9_.:-]*)\s*$""").matchEntire(line) ?: continue
            val field = match.groupValues[2]
            if (field in setOf("from", "to") && document !is TimelineDocument) continue
            val id = match.groupValues[3]
            val start = lineStarts[lineIndex] + line.lastIndexOf(id)
            if (references.any { it.kind == ReferenceTargetKind.Timeline && it.range.start == start }) continue
            references += SymbolReference(id, ReferenceTargetKind.Timeline, field, SourceRange(start, start + id.length))
        }
    }

    private fun extractInlineTimelineReferences(body: String, baseOffset: Int): List<SymbolReference> {
        val references = mutableListOf<SymbolReference>()
        val masked = maskCodeRegions(body)
        Regex("""validTime\s*=\s*""").findAll(masked).forEach { marker ->
            var cursor = marker.range.last + 1
            val end = when (masked.getOrNull(cursor)) {
                '[' -> readBalancedEnd(masked, cursor, '[', ']') ?: cursor
                else -> {
                    var depth = 0
                    var index = cursor
                    while (index < masked.length) {
                        val char = masked[index]
                        if (char == '(') depth++
                        if (char == ')' && depth-- == 0) break
                        if (depth == 0 && char in setOf(',', '}', '\n')) break
                        index++
                    }
                    index
                }
            }
            val expression = body.substring(cursor, end.coerceAtMost(body.length))
            Regex("""[A-Za-z_][A-Za-z0-9_.:-]*""").findAll(expression).forEach { token ->
                if (token.value in setOf("from", "to", "timecode", "value")) return@forEach
                val absoluteStart = baseOffset + cursor + token.range.first
                references += SymbolReference(
                    token.value,
                    ReferenceTargetKind.Timeline,
                    "validTime.timeline",
                    SourceRange(absoluteStart, absoluteStart + token.value.length),
                )
            }
        }
        Regex("""\btimeline\s*=\s*([A-Za-z_][A-Za-z0-9_.:-]*)""").findAll(masked).forEach { match ->
            val token = match.groups[1] ?: return@forEach
            val tokenStart = match.range.first + match.value.lastIndexOf(token.value)
            val absoluteStart = baseOffset + tokenStart
            if (references.none { it.range.start == absoluteStart }) {
                references += SymbolReference(
                    token.value,
                    ReferenceTargetKind.Timeline,
                    "timeline",
                    SourceRange(absoluteStart, absoluteStart + token.value.length),
                )
            }
        }
        return references
    }

    private fun listFieldKind(field: String, document: GraphDocument?): ReferenceTargetKind? {
        return when (field) {
            "timeline" -> ReferenceTargetKind.Timeline
            "from", "to" -> if (document is TimelineDocument) ReferenceTargetKind.Timeline else ReferenceTargetKind.NodeType
            "extends" -> when (document) {
            is RelTypeDocument -> ReferenceTargetKind.RelType
            is NodeTypeDocument -> ReferenceTargetKind.NodeType
            is TimelineDocument -> ReferenceTargetKind.Timeline
                else -> null
            }
            else -> null
        }
    }

    private fun definitionKind(document: GraphDocument): ReferenceTargetKind {
        return when (document) {
            is NodeDocument -> ReferenceTargetKind.Node
            is NodeTypeDocument -> ReferenceTargetKind.NodeType
            is RelTypeDocument -> ReferenceTargetKind.RelType
            is TimelineDocument -> ReferenceTargetKind.Timeline
        }
    }

    private fun stripYamlScalar(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"' -> trimmed.substring(1, trimmed.length - 1)
            trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'' -> trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
    }
}
