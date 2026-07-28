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
    private val frontMatterScanner = FrontMatterStructureScanner()

    fun analyze(text: String, sourcePath: String): GraphDocumentAnalysis {
        val normalized = text.replace("\r\n", "\n")
        val parsed = parser.parseDocument(normalized, sourcePath)
        val document = parsed.document
        val lines = normalized.split('\n')
        if (lines.firstOrNull() != "---") {
            return GraphDocumentAnalysis(normalized, parsed, 0, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val endLine = lines.drop(1).indexOfFirst { it == "---" || it == "..." }.let { if (it >= 0) it + 1 else -1 }
        if (endLine < 0) {
            return GraphDocumentAnalysis(normalized, parsed, 0, emptyList(), emptyList(), emptyList(), emptyList())
        }
        val lineStarts = computeLineStarts(normalized)
        val definitions = mutableListOf<SymbolDefinition>()
        val references = mutableListOf<SymbolReference>()
        val propertyDefinitions = mutableListOf<PropertyDefinition>()
        val propertyReferences = mutableListOf<PropertyReference>()
        val frontMatter = frontMatterScanner.scan(lines, lineStarts, 1, endLine)
        collectStructuredFrontMatterSymbols(frontMatter, document, definitions, references)
        val yamlPropertyKeys = extractYamlPropertyKeys(frontMatter)
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
            val bodyOffset = lineStarts[endLine] + lines[endLine].length + 1
            references += extractBodyReferences(document.body, bodyOffset)
            references += extractInlineTimelineReferences(document.body, bodyOffset)
            propertyReferences += extractBodyPropertyReferences(document.body, bodyOffset, document.type)
        }

        val frontMatterEndOffset = lineStarts[endLine] + lines[endLine].length + 1
        return GraphDocumentAnalysis(
            normalized,
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

    private fun collectStructuredFrontMatterSymbols(
        structure: FrontMatterStructure,
        document: GraphDocument?,
        definitions: MutableList<SymbolDefinition>,
        references: MutableList<SymbolReference>,
    ) {
        document ?: return

        structure.rootEntries("id").lastOrNull()?.let { entry ->
            structure.scalarsFor(entry).singleOrNull()?.let { scalar ->
                definitions += SymbolDefinition(scalar.value, definitionKind(document), scalar.range)
            }
        }

        fun collectRootField(field: String, kind: ReferenceTargetKind?) {
            if (kind == null) return
            structure.rootEntries(field).lastOrNull()?.let { entry ->
                structure.scalarsFor(entry).forEach { scalar ->
                    references += SymbolReference(scalar.value, kind, field, scalar.range)
                }
            }
        }

        if (document is NodeDocument) {
            collectRootField("type", ReferenceTargetKind.NodeType)
        }
        collectRootField("extends", listFieldKind("extends", document))
        collectRootField("from", listFieldKind("from", document))
        collectRootField("to", listFieldKind("to", document))

        structure.scalars.forEach { scalar ->
            val isNestedTimelineField = when (document) {
                is NodeDocument -> isNodeTimelinePath(scalar.path)
                is NodeTypeDocument, is RelTypeDocument -> isPropertySchemaTimelinePath(scalar.path)
                else -> false
            }
            val isCanonicalSelectorId =
                (document is NodeTypeDocument || document is RelTypeDocument) &&
                isPropertySchemaTimelineSelectorIdPath(scalar.path)
            val isTimelineMappingEndpoint =
                document is TimelineDocument &&
                scalar.path.size == 2 &&
                scalar.path.firstOrNull() == "mappings" &&
                scalar.path.lastOrNull() in setOf("from", "to")
            if (isNestedTimelineField || isCanonicalSelectorId || isTimelineMappingEndpoint) {
                val field = if (isCanonicalSelectorId) "timeline" else scalar.path.last()
                references += SymbolReference(
                    scalar.value,
                    ReferenceTargetKind.Timeline,
                    field,
                    scalar.range,
                )
            }
        }
    }

    private fun isNodeTimelinePath(path: List<String>): Boolean {
        if (path.lastOrNull() != "timeline") return false
        if (path.firstOrNull() == "validTime") return true
        if (path.firstOrNull() != "props" || path.size < 3) return false
        // `props.timeline` is a property named "timeline", while a timeline
        // below a property value is an instant/duration or validTime member.
        return true
    }

    private fun isPropertySchemaTimelinePath(path: List<String>): Boolean {
        if (path.size < 3 || path[0] != "props" || path.last() != "timeline") return false
        return path.subList(2, path.lastIndex).all { it == "items" }
    }

    private fun isPropertySchemaTimelineSelectorIdPath(path: List<String>): Boolean {
        if (path.size < 4 || path[0] != "props" || path.last() != "id") return false
        val schemaPath = path.subList(2, path.lastIndex)
        return schemaPath.lastOrNull() == "timeline" &&
            schemaPath.dropLast(1).all { it == "items" }
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

    private fun extractYamlPropertyKeys(structure: FrontMatterStructure): List<PropertyKey> =
        structure.entries
            .filter { it.path.size == 2 && it.path.first() == "props" }
            .map { PropertyKey(it.key, it.keyRange) }

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
            "from", "to" -> if (document is RelTypeDocument) ReferenceTargetKind.NodeType else null
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

    private data class PropertyKey(
        val name: String,
        val range: SourceRange,
    )

    private data class ExtractedPropertyReferences(
        val references: List<PropertyReference>,
        val nextIndex: Int,
    )
}
