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

enum class PropertyOwnerKind {
    NodeType,
    RelType,
}

data class PropertyDefinition(
    val name: String,
    val ownerId: String,
    val ownerKind: PropertyOwnerKind,
    val range: SourceRange,
)

data class PropertyReference(
    val name: String,
    val ownerId: String,
    val ownerKind: PropertyOwnerKind,
    val range: SourceRange,
)

data class GraphDocumentAnalysis(
    val text: String,
    val parsed: ParsedGraphDocumentResult,
    val frontMatterEndOffset: Int,
    val definitions: List<SymbolDefinition>,
    val references: List<SymbolReference>,
    val propertyDefinitions: List<PropertyDefinition> = emptyList(),
    val propertyReferences: List<PropertyReference> = emptyList(),
)

class GraphDocumentAnalyzer {
    private val parser = GraphDocumentParser()

    fun analyze(text: String, sourcePath: String): GraphDocumentAnalysis {
        val parsed = parser.parseDocument(text, sourcePath)
        val document = parsed.document
        val lines = text.split('\n').map { it.removeSuffix("\r") }
        if (lines.firstOrNull() != "---") {
            return GraphDocumentAnalysis(text, parsed, 0, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val endLine = lines.drop(1).indexOfFirst { it == "---" || it == "..." }.let { if (it >= 0) it + 1 else -1 }
        if (endLine < 0) {
            return GraphDocumentAnalysis(text, parsed, 0, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val lineStarts = computeLineStarts(text)
        val definitions = mutableListOf<SymbolDefinition>()
        val references = mutableListOf<SymbolReference>()
        val propertyDefinitions = mutableListOf<PropertyDefinition>()
        val propertyReferences = mutableListOf<PropertyReference>()
        val rootIdScalars = mutableListOf<YamlScalarToken>()
        var currentListField: Pair<String, Int>? = null

        for (lineIndex in 1 until endLine) {
            val line = lines[lineIndex]
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }

            val inlineList = scanYamlInlineList(line)
            if (inlineList != null) {
                val field = inlineList.field
                currentListField = null
                val relevant = indent == 0 || field == "timeline" ||
                    (field in setOf("from", "to") && document is TimelineDocument)
                val kind = if (relevant) listFieldKind(field, document) else null
                if (kind != null) {
                    collectInlineListReferences(
                        rawItems = inlineList.rawItems,
                        absoluteListStart = lineStarts[lineIndex] + inlineList.itemsStart,
                        field = field,
                        kind = kind,
                        references = references,
                    )
                }
                continue
            }

            val mapping = Regex("""^(\s*)(-\s*)?([A-Za-z][A-Za-z0-9_-]*)\s*:(.*)$""").matchEntire(line)
            if (mapping != null) {
                val field = mapping.groupValues[3]
                val colonIndex = line.indexOf(':')
                val scalar = scanYamlScalar(line, colonIndex + 1, lineStarts[lineIndex])
                currentListField = if (mapping.groupValues[2].isEmpty() &&
                    scalar == null && mapping.groupValues[4].substringBefore('#').isBlank()
                ) {
                    field to indent
                } else {
                    null
                }
                if (scalar != null) {
                    when (field) {
                        "id" -> if (indent == 0) rootIdScalars += scalar
                        "type" -> if (indent == 0 && document is NodeDocument) {
                            references += SymbolReference(scalar.decoded, ReferenceTargetKind.NodeType, field, scalar.range)
                        }
                        "extends", "from", "to", "timeline" -> {
                            if (!scalar.isUnquotedFlowValue &&
                                (indent == 0 || field == "timeline" ||
                                    (field in setOf("from", "to") && document is TimelineDocument))
                            ) {
                                listFieldKind(field, document)?.let { kind ->
                                    references += SymbolReference(scalar.decoded, kind, field, scalar.range)
                                }
                            }
                        }
                    }
                }
                continue
            }

            if (currentListField != null && Regex("""^\s*-\s+""").containsMatchIn(line)) {
                val match = Regex("""^(\s*-\s+)(.*)$""").matchEntire(line) ?: continue
                val (field, parentIndent) = currentListField
                if (indent <= parentIndent) {
                    currentListField = null
                    continue
                }
                val kind = listFieldKind(field, document) ?: continue
                val scalar = scanYamlScalar(line, match.groupValues[1].length, lineStarts[lineIndex]) ?: continue
                if (scalar.isUnquotedFlowValue) continue
                references += SymbolReference(
                    targetId = scalar.decoded,
                    kind = kind,
                    field = field,
                    range = scalar.range,
                )
                continue
            }

            if (currentListField?.let { indent <= it.second } == true) {
                currentListField = null
            }
        }

        document?.let {
            rootIdScalars.lastOrNull()?.let { scalar ->
                definitions += SymbolDefinition(scalar.decoded, definitionKind(it), scalar.range)
            }
        }

        val yamlPropertyKeys = extractYamlPropertyKeys(lines, lineStarts, endLine)
        when (document) {
            is NodeDocument -> yamlPropertyKeys.forEach { key ->
                propertyReferences += PropertyReference(
                    key.name,
                    document.type,
                    PropertyOwnerKind.NodeType,
                    key.range,
                )
            }
            is NodeTypeDocument -> yamlPropertyKeys.forEach { key ->
                propertyDefinitions += PropertyDefinition(
                    key.name,
                    document.id,
                    PropertyOwnerKind.NodeType,
                    key.range,
                )
            }
            is RelTypeDocument -> yamlPropertyKeys.forEach { key ->
                propertyDefinitions += PropertyDefinition(
                    key.name,
                    document.id,
                    PropertyOwnerKind.RelType,
                    key.range,
                )
            }
            else -> Unit
        }

        if (document is NodeDocument) {
            val bodyOffset = lineStarts[endLine] + lines[endLine].length +
                if (text.getOrNull(lineStarts[endLine] + lines[endLine].length) == '\r') 2 else 1
            val body = text.substring(bodyOffset.coerceAtMost(text.length))
            references += extractBodyReferences(body, bodyOffset)
            references += extractInlineTimelineReferences(body, bodyOffset)
            propertyReferences += extractBodyPropertyReferences(body, bodyOffset, document.type)
        }

        val frontMatterEndOffset = lineStarts[endLine] + lines[endLine].length +
            if (text.getOrNull(lineStarts[endLine] + lines[endLine].length) == '\r') 2 else 1
        return GraphDocumentAnalysis(
            text,
            parsed,
            frontMatterEndOffset,
            definitions,
            references,
            propertyDefinitions,
            propertyReferences,
        )
    }

    fun findReferenceAt(analysis: GraphDocumentAnalysis, offset: Int): SymbolReference? {
        return analysis.references.firstOrNull { offset in it.range.start..it.range.end }
    }

    fun findDefinitionAt(analysis: GraphDocumentAnalysis, offset: Int): SymbolDefinition? {
        return analysis.definitions.firstOrNull { offset in it.range.start..it.range.end }
    }

    fun findPropertyReferenceAt(analysis: GraphDocumentAnalysis, offset: Int): PropertyReference? {
        return analysis.propertyReferences.firstOrNull { offset in it.range.start..it.range.end }
    }

    fun findPropertyDefinitionAt(analysis: GraphDocumentAnalysis, offset: Int): PropertyDefinition? {
        return analysis.propertyDefinitions.firstOrNull { offset in it.range.start..it.range.end }
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
                        val parsed = RelationTargetParser.parseDetailed(raw)
                        if (parsed != null) {
                            val contentStart = baseOffset + closeLabel + 2
                            refs += SymbolReference(
                                parsed.target,
                                ReferenceTargetKind.Node,
                                "relation.target",
                                SourceRange(contentStart + parsed.targetRange.first, contentStart + parsed.targetRange.last + 1),
                            )
                            refs += SymbolReference(
                                parsed.relType,
                                ReferenceTargetKind.RelType,
                                "relation.type",
                                SourceRange(contentStart + parsed.relTypeRange.first, contentStart + parsed.relTypeRange.last + 1),
                            )
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

    private fun maskQuotedStrings(text: String): String {
        val chars = text.toCharArray()
        var index = 0
        while (index < chars.size) {
            if (chars[index] != '"') {
                index++
                continue
            }
            chars[index++] = ' '
            var escaped = false
            while (index < chars.size) {
                val char = chars[index]
                if (char == '\n') break
                chars[index] = ' '
                index++
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> break
                }
            }
        }
        return chars.concatToString()
    }

    private fun scanYamlInlineList(line: String): YamlInlineList? {
        val mapping = Regex("""^(\s*)([A-Za-z][A-Za-z0-9_-]*)\s*:""").find(line) ?: return null
        val field = mapping.groupValues[2]
        var index = mapping.range.last + 1
        while (index < line.length && line[index].isWhitespace()) index++
        if (line.getOrNull(index) != '[') return null
        val itemsStart = index + 1
        index = itemsStart
        var quote: Char? = null
        var escaped = false
        while (index < line.length) {
            val char = line[index]
            when {
                quote == '"' && escaped -> escaped = false
                quote == '"' && char == '\\' -> escaped = true
                quote == '\'' && char == '\'' && line.getOrNull(index + 1) == '\'' -> index++
                quote != null && char == quote -> quote = null
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && char == '#' && index > itemsStart && line[index - 1].isWhitespace() -> return null
                quote == null && char == ']' -> {
                    if (!onlyWhitespaceOrSeparatedComment(line, index + 1)) return null
                    return YamlInlineList(field, line.substring(itemsStart, index), itemsStart)
                }
            }
            index++
        }
        return null
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
        splitYamlInlineItems(rawItems).forEach { item ->
            val itemText = rawItems.substring(item.start, item.end)
            val scalar = scanYamlScalar(itemText, 0, absoluteListStart + item.start) ?: return@forEach
            references += SymbolReference(scalar.decoded, kind, field, scalar.range)
        }
    }

    private fun extractInlineTimelineReferences(body: String, baseOffset: Int): List<SymbolReference> {
        val references = mutableListOf<SymbolReference>()
        val masked = maskQuotedStrings(maskCodeRegions(body))
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
            INLINE_SELECTOR.findAll(expression).forEach { match ->
                val token = match.groups[1] ?: return@forEach
                val decoded = decodeInlineToken(token.value)
                val quoteOffset = if (token.value.firstOrNull() in setOf('"', '\'')) 1 else 0
                val tokenStart = match.range.first + match.value.lastIndexOf(token.value)
                val absoluteStart = baseOffset + cursor + tokenStart + quoteOffset
                references += SymbolReference(
                    decoded,
                    ReferenceTargetKind.Timeline,
                    "validTime.timeline",
                    SourceRange(absoluteStart, absoluteStart + token.value.length - quoteOffset * 2),
                )
            }
        }
        INLINE_TIMELINE_ASSIGNMENT.findAll(masked).forEach { match ->
            val token = match.groups[1] ?: return@forEach
            val quoteOffset = if (token.value.firstOrNull() in setOf('"', '\'')) 1 else 0
            val tokenStart = match.range.first + match.value.lastIndexOf(token.value)
            val absoluteStart = baseOffset + tokenStart + quoteOffset
            if (references.none { it.range.start == absoluteStart }) {
                references += SymbolReference(
                    decodeInlineToken(token.value),
                    ReferenceTargetKind.Timeline,
                    "timeline",
                    SourceRange(absoluteStart, absoluteStart + token.value.length - quoteOffset * 2),
                )
            }
        }
        return references
    }

    private fun extractYamlPropertyKeys(
        lines: List<String>,
        lineStarts: List<Int>,
        endLine: Int,
    ): List<PropertyKey> {
        val propsLine = (1 until endLine).firstOrNull { lineIndex ->
            lines[lineIndex].matches(Regex("""^props\s*:\s*(?:#.*)?$"""))
        } ?: return emptyList()
        val candidates = mutableListOf<YamlPropertyKey>()
        for (lineIndex in propsLine + 1 until endLine) {
            val line = lines[lineIndex]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) line.length else it }
            if (indent == 0) break
            val content = line.drop(indent)
            val colonIndex = content.indexOf(':')
            if (colonIndex <= 0) continue
            val rawKey = content.substring(0, colonIndex)
            val key = rawKey.trim()
            if (key.isEmpty()) continue
            val keyStart = indent + rawKey.indexOf(key)
            candidates += YamlPropertyKey(
                indent,
                PropertyKey(
                    key,
                    SourceRange(
                        lineStarts[lineIndex] + keyStart,
                        lineStarts[lineIndex] + keyStart + key.length,
                    ),
                ),
            )
        }
        val propertyIndent = candidates.minOfOrNull { it.indent } ?: return emptyList()
        return candidates.filter { it.indent == propertyIndent }.map { it.key }
    }

    private fun extractBodyPropertyReferences(
        body: String,
        baseOffset: Int,
        nodeTypeId: String,
    ): List<PropertyReference> {
        val references = mutableListOf<PropertyReference>()
        val masked = maskCodeRegions(body)
        var index = 0
        while (index < masked.length) {
            when {
                masked.startsWith("@props", index) &&
                    !isEscaped(masked, index) &&
                    !isIdentifierPart(masked.getOrNull(index + "@props".length)) -> {
                    var cursor = index + "@props".length
                    if (masked.getOrNull(cursor) == '(') {
                        val argumentsEnd = readBalancedEnd(masked, cursor, '(', ')')
                        if (argumentsEnd == null) {
                            index += 1
                            continue
                        }
                        cursor = argumentsEnd
                    }
                    if (masked.getOrNull(cursor) == '{') {
                        inlineObjectKeys(body, cursor).forEach { key ->
                            references += PropertyReference(
                                key.name,
                                nodeTypeId,
                                PropertyOwnerKind.NodeType,
                                key.range.shiftedBy(baseOffset),
                            )
                        }
                        index = readBalancedEnd(masked, cursor, '{', '}') ?: masked.length
                    } else {
                        index += 1
                    }
                }
                masked.startsWith("@link", index) &&
                    !isEscaped(masked, index) &&
                    !isIdentifierPart(masked.getOrNull(index + "@link".length)) -> {
                    val extracted = extractRelationPropertyReferences(body, masked, index, baseOffset)
                    references += extracted.references
                    index = extracted.nextIndex
                }
                else -> index += 1
            }
        }
        return references
    }

    private fun extractRelationPropertyReferences(
        body: String,
        masked: String,
        start: Int,
        baseOffset: Int,
    ): ExtractedPropertyReferences {
        var cursor = start + "@link".length
        if (masked.getOrNull(cursor) == '(') {
            cursor = readBalancedEnd(masked, cursor, '(', ')')
                ?: return ExtractedPropertyReferences(emptyList(), start + 1)
        }
        if (masked.getOrNull(cursor) != '{') {
            return ExtractedPropertyReferences(emptyList(), start + 1)
        }
        val braceStart = cursor
        val braceEnd = readBalancedEnd(masked, braceStart, '{', '}')
            ?: return ExtractedPropertyReferences(emptyList(), masked.length)
        if (masked.getOrNull(braceEnd) != '[') {
            return ExtractedPropertyReferences(emptyList(), braceEnd)
        }
        val closeLabel = findUnescaped(masked, ']', braceEnd + 1)
            ?: return ExtractedPropertyReferences(emptyList(), braceEnd)
        if (masked.getOrNull(closeLabel + 1) != '(') {
            return ExtractedPropertyReferences(emptyList(), closeLabel + 1)
        }
        val closeParen = findUnescaped(masked, ')', closeLabel + 2)
            ?: return ExtractedPropertyReferences(emptyList(), closeLabel + 1)
        val relType = RelationTargetParser.parse(body.substring(closeLabel + 2, closeParen))?.second
            ?: return ExtractedPropertyReferences(emptyList(), closeParen + 1)
        val references = inlineObjectKeys(body, braceStart).map { key ->
            PropertyReference(
                key.name,
                relType,
                PropertyOwnerKind.RelType,
                key.range.shiftedBy(baseOffset),
            )
        }
        return ExtractedPropertyReferences(references, closeParen + 1)
    }

    private fun inlineObjectKeys(text: String, braceStart: Int): List<PropertyKey> {
        val keys = mutableListOf<PropertyKey>()
        var index = braceStart + 1
        while (index < text.length) {
            while (index < text.length && (text[index].isWhitespace() || text[index] == ',')) index++
            if (text.getOrNull(index) == '}' || index >= text.length) break
            if (!isIdentifierStart(text[index])) {
                index++
                continue
            }
            val keyStart = index
            val keyEnd = readIdentifierEnd(text, keyStart)
            var cursor = skipWhitespace(text, keyEnd)
            if (text.getOrNull(cursor) == '(') {
                cursor = readBalancedEnd(text, cursor, '(', ')') ?: break
                cursor = skipWhitespace(text, cursor)
            }
            if (text.getOrNull(cursor) != '=') {
                index = keyEnd
                continue
            }
            keys += PropertyKey(text.substring(keyStart, keyEnd), SourceRange(keyStart, keyEnd))
            index = skipInlinePropertyValue(text, cursor + 1)
        }
        return keys
    }

    private fun skipInlinePropertyValue(text: String, start: Int): Int {
        var index = skipWhitespace(text, start)
        while (index < text.length) {
            when (text[index]) {
                '"' -> index = readQuotedEnd(text, index)
                '{' -> index = readBalancedEnd(text, index, '{', '}') ?: return text.length
                '[' -> index = readBalancedEnd(text, index, '[', ']') ?: return text.length
                '(' -> index = readBalancedEnd(text, index, '(', ')') ?: return text.length
                ',' -> return index + 1
                '}' -> return index
                else -> {
                    if (text[index].isWhitespace()) {
                        val candidate = skipWhitespace(text, index)
                        if (looksLikeInlineProperty(text, candidate)) return candidate
                        index = candidate
                    } else {
                        index++
                    }
                }
            }
        }
        return index
    }

    private fun looksLikeInlineProperty(text: String, start: Int): Boolean {
        if (!isIdentifierStart(text.getOrNull(start))) return false
        var cursor = skipWhitespace(text, readIdentifierEnd(text, start))
        if (text.getOrNull(cursor) == '(') {
            cursor = readBalancedEnd(text, cursor, '(', ')') ?: return false
            cursor = skipWhitespace(text, cursor)
        }
        return text.getOrNull(cursor) == '='
    }

    private fun readIdentifierEnd(text: String, start: Int): Int {
        var index = start + 1
        while (isIdentifierPart(text.getOrNull(index))) index++
        return index
    }

    private fun readQuotedEnd(text: String, start: Int): Int {
        var index = start + 1
        var escaped = false
        while (index < text.length) {
            when {
                escaped -> escaped = false
                text[index] == '\\' -> escaped = true
                text[index] == '"' -> return index + 1
            }
            index++
        }
        return text.length
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var index = start
        while (index < text.length && text[index].isWhitespace()) index++
        return index
    }

    private fun isIdentifierStart(char: Char?): Boolean =
        char != null && (char.isLetter() || char == '_')

    private fun isIdentifierPart(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char in setOf('_', '.', ':', '-'))

    private fun SourceRange.shiftedBy(offset: Int): SourceRange =
        SourceRange(start + offset, end + offset)

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

    private fun scanYamlScalar(line: String, start: Int, absoluteBase: Int): YamlScalarToken? {
        var tokenStart = start
        while (tokenStart < line.length && line[tokenStart].isWhitespace()) tokenStart++
        if (tokenStart >= line.length || line[tokenStart] == '#') return null
        return when (line[tokenStart]) {
            '"' -> {
                val decoded = StringBuilder()
                var index = tokenStart + 1
                var closedAt: Int? = null
                while (index < line.length) {
                    when (val char = line[index]) {
                        '"' -> {
                            closedAt = index
                            break
                        }
                        '\\' -> {
                            when (val escaped = line.getOrNull(index + 1)) {
                                'n' -> decoded.append('\n')
                                'r' -> decoded.append('\r')
                                't' -> decoded.append('\t')
                                '\\' -> decoded.append('\\')
                                '"' -> decoded.append('"')
                                null -> Unit
                                else -> decoded.append(escaped)
                            }
                            index += 2
                        }
                        else -> {
                            decoded.append(char)
                            index++
                        }
                    }
                }
                val endQuote = closedAt ?: return scanPlainYamlScalar(line, tokenStart, absoluteBase)
                if (!onlyWhitespaceOrSeparatedComment(line, endQuote + 1)) {
                    return scanPlainYamlScalar(line, tokenStart, absoluteBase)
                }
                YamlScalarToken(
                    decoded.toString(),
                    SourceRange(absoluteBase + tokenStart + 1, absoluteBase + endQuote),
                    quoted = true,
                )
            }
            '\'' -> {
                val decoded = StringBuilder()
                var index = tokenStart + 1
                var closedAt: Int? = null
                while (index < line.length) {
                    if (line[index] == '\'' && line.getOrNull(index + 1) == '\'') {
                        decoded.append('\'')
                        index += 2
                    } else if (line[index] == '\'') {
                        closedAt = index
                        break
                    } else {
                        decoded.append(line[index])
                        index++
                    }
                }
                val endQuote = closedAt ?: return scanPlainYamlScalar(line, tokenStart, absoluteBase)
                if (!onlyWhitespaceOrSeparatedComment(line, endQuote + 1)) {
                    return scanPlainYamlScalar(line, tokenStart, absoluteBase)
                }
                YamlScalarToken(
                    decoded.toString(),
                    SourceRange(absoluteBase + tokenStart + 1, absoluteBase + endQuote),
                    quoted = true,
                )
            }
            else -> scanPlainYamlScalar(line, tokenStart, absoluteBase)
        }
    }

    private fun scanPlainYamlScalar(line: String, tokenStart: Int, absoluteBase: Int): YamlScalarToken? {
        var end = line.length
        var index = tokenStart
        while (index < line.length) {
            if (line[index] == '#' && index > tokenStart && line[index - 1].isWhitespace()) {
                end = index
                break
            }
            index++
        }
        while (end > tokenStart && line[end - 1].isWhitespace()) end--
        return if (end == tokenStart) null
        else YamlScalarToken(
            line.substring(tokenStart, end),
            SourceRange(absoluteBase + tokenStart, absoluteBase + end),
            quoted = false,
        )
    }

    private fun onlyWhitespaceOrSeparatedComment(line: String, start: Int): Boolean {
        var index = start
        while (index < line.length && line[index].isWhitespace()) index++
        return index == line.length || (index > start && line[index] == '#')
    }

    private fun splitYamlInlineItems(value: String): List<SourceRange> {
        val ranges = mutableListOf<SourceRange>()
        var start = 0
        var index = 0
        var quote: Char? = null
        var escaped = false
        while (index <= value.length) {
            val char = value.getOrNull(index)
            when {
                quote == '"' && escaped -> escaped = false
                quote == '"' && char == '\\' -> escaped = true
                quote != null && char == quote -> {
                    if (quote == '\'' && value.getOrNull(index + 1) == '\'') {
                        index++
                    } else {
                        quote = null
                    }
                }
                quote == null && (char == '"' || char == '\'') -> quote = char
                quote == null && (char == ',' || char == null) -> {
                    ranges += SourceRange(start, index)
                    start = index + 1
                }
            }
            index++
        }
        return ranges
    }

    private data class YamlScalarToken(
        val decoded: String,
        val range: SourceRange,
        val quoted: Boolean,
    ) {
        val isUnquotedFlowValue: Boolean
            get() = !quoted && decoded.startsWith("[")
    }

    private data class YamlInlineList(
        val field: String,
        val rawItems: String,
        val itemsStart: Int,
    )

    private fun decodeInlineToken(raw: String): String {
        if (raw.length < 2) return raw
        return when (raw.first()) {
            '"' -> {
                val result = StringBuilder()
                var index = 1
                while (index < raw.length - 1) {
                    if (raw[index] == '\\' && index + 1 < raw.length - 1) {
                        result.append(raw[index + 1])
                        index += 2
                    } else {
                        result.append(raw[index++])
                    }
                }
                result.toString()
            }
            '\'' -> raw.substring(1, raw.length - 1).replace("''", "'")
            else -> raw
        }
    }

    private data class PropertyKey(
        val name: String,
        val range: SourceRange,
    )

    private data class YamlPropertyKey(
        val indent: Int,
        val key: PropertyKey,
    )

    private data class ExtractedPropertyReferences(
        val references: List<PropertyReference>,
        val nextIndex: Int,
    )

    private companion object {
        private val INLINE_TOKEN = """"(?:\\.|[^"])*"|'(?:''|[^'])*'|[^\s,()\[\]{}=]+"""
        private val INLINE_SELECTOR = Regex("""(?:^|[\[,])\s*($INLINE_TOKEN)""")
        private val INLINE_TIMELINE_ASSIGNMENT = Regex("""\btimeline\s*=\s*($INLINE_TOKEN)""")
    }
}
