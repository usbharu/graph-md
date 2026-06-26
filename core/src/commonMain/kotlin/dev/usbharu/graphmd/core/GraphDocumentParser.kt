package dev.usbharu.graphmd.core

class GraphDocumentParser {
    fun parseDocument(text: String, sourcePath: String): ParsedGraphDocumentResult {
        val diagnostics = mutableListOf<Diagnostic>()
        val split = splitFrontMatter(text, sourcePath, diagnostics) ?: return ParsedGraphDocumentResult(null, diagnostics)
        val root = MiniYamlParser(split.frontMatter, sourcePath, diagnostics).parse() ?: return ParsedGraphDocumentResult(null, diagnostics)
        val document = toGraphDocument(root, split.body, sourcePath, diagnostics)
        return ParsedGraphDocumentResult(document, diagnostics)
    }

    private fun splitFrontMatter(
        text: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): FrontMatterSplit? {
        val normalized = text.replace("\r\n", "\n")
        val lines = normalized.split('\n')
        if (lines.firstOrNull() != "---") {
            diagnostics += syntaxError("Document MUST start with YAML front matter", sourcePath)
            return null
        }
        val relativeEndIndex = lines.drop(1).indexOfFirst { it == "---" || it == "..." }
        val endIndex = relativeEndIndex.takeIf { it >= 0 }?.plus(1)
        if (endIndex == null) {
            diagnostics += syntaxError("Unclosed YAML front matter", sourcePath)
            return null
        }
        return FrontMatterSplit(
            frontMatter = lines.subList(1, endIndex).joinToString("\n"),
            body = lines.drop(endIndex + 1).joinToString("\n"),
        )
    }

    private fun toGraphDocument(
        value: YamlValue,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument? {
        val root = value as? YamlMap ?: run {
            diagnostics += schemaError("Front matter root MUST be a mapping", sourcePath)
            return null
        }
        val id = root.requireString("id", sourcePath, diagnostics) ?: return null
        val kindName = root.requireString("kind", sourcePath, diagnostics) ?: return null
        return when (kindName) {
            "Node" -> parseNodeDocument(id, root, body, sourcePath, diagnostics)
            "NodeType" -> parseNodeTypeDocument(id, root, body, sourcePath, diagnostics)
            "RelType" -> parseRelTypeDocument(id, root, body, sourcePath, diagnostics)
            "Timeline" -> parseTimelineDocument(id, root, body, sourcePath, diagnostics)
            else -> {
                diagnostics += schemaError("Unknown document kind: $kindName", sourcePath, id)
                null
            }
        }
    }

    private fun parseNodeDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument? {
        val type = root.requireString("type", sourcePath, diagnostics, id) ?: return null
        return NodeDocument(
            id = id,
            type = type,
            props = root.map["props"]?.let { parseRawObjectEntries(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
            topLevelFields = root.map.keys,
        )
    }

    private fun parseNodeTypeDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument {
        return NodeTypeDocument(
            id = id,
            extends = root.stringList("extends", sourcePath, diagnostics, id) ?: emptyList(),
            props = root.map["props"]?.let { parsePropSchemaMap(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
        )
    }

    private fun parseRelTypeDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument {
        return RelTypeDocument(
            id = id,
            extends = root.stringList("extends", sourcePath, diagnostics, id) ?: emptyList(),
            from = root.stringList("from", sourcePath, diagnostics, id),
            to = root.stringList("to", sourcePath, diagnostics, id),
            props = root.map["props"]?.let { parsePropSchemaMap(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
        )
    }

    private fun parseTimelineDocument(
        id: String,
        root: YamlMap,
        body: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
    ): GraphDocument? {
        val allowedFields = setOf("id", "kind", "extends", "timecode", "mappings", "props")
        root.map.keys.filterNot { it in allowedFields }.forEach { field ->
            diagnostics += schemaError("Unknown top-level field: $field", sourcePath, id)
        }
        val mappings = when (val rawMappings = root.map["mappings"]?.takeUnless { it == YamlNull }) {
            null -> emptyList()
            is YamlList -> rawMappings.values.mapNotNull { parseTimelineMapping(it, sourcePath, diagnostics, id) }
            else -> {
                diagnostics += schemaError("mappings MUST be a list", sourcePath, id)
                emptyList()
            }
        }
        return TimelineDocument(
            id = id,
            extends = root.stringList("extends", sourcePath, diagnostics, id) ?: emptyList(),
            timecode = root.map["timecode"]?.takeUnless { it == YamlNull }?.let { parseTimecodeSchema(it, sourcePath, diagnostics, id) },
            mappings = mappings,
            props = root.map["props"]?.let { parseRawObjectEntries(it, sourcePath, diagnostics, id, "props") } ?: emptyMap(),
            body = body,
            sourcePath = sourcePath,
        )
    }

    private fun parsePropSchemaMap(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): Map<String, PropSchema> {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$fieldName MUST be a mapping", sourcePath, documentId)
            return emptyMap()
        }
        return map.map.mapValues { (name, schemaValue) ->
            parsePropSchema(schemaValue, sourcePath, diagnostics, documentId, "$fieldName.$name")
        }
    }

    private fun parsePropSchema(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): PropSchema {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$fieldName MUST be a mapping", sourcePath, documentId)
            return PropSchema(PropType.string)
        }
        val type = map.requireString("type", sourcePath, diagnostics, documentId)?.let {
            runCatching { PropType.valueOf(it) }.getOrElse {
                diagnostics += schemaError("Unknown prop type: $it", sourcePath, documentId)
                null
            }
        } ?: PropType.string
        val index = map.string("index", sourcePath, diagnostics, documentId)?.let {
            runCatching { PropIndex.valueOf(it) }.getOrElse {
                diagnostics += schemaError("Unknown prop index: $it", sourcePath, documentId)
                null
            }
        }
        return PropSchema(
            type = type,
            required = map.boolean("required", sourcePath, diagnostics, documentId) ?: false,
            default = map.map["default"]?.let { parseRawValue(it) },
            index = index,
            timeline = map.map["timeline"]?.let {
                parseTimelineSelector(it, sourcePath, diagnostics, documentId, "$fieldName.timeline")
            },
            timelines = map.map["timelines"]?.let {
                parseTimelineSelectors(it, sourcePath, diagnostics, documentId, "$fieldName.timelines")
            },
            items = map.map["items"]?.let { parsePropSchema(it, sourcePath, diagnostics, documentId, "$fieldName.items") },
            properties = map.map["properties"]?.let { parsePropSchemaMap(it, sourcePath, diagnostics, documentId, "$fieldName.properties") }
                ?: emptyMap(),
        )
    }

    private fun parseTimelineMapping(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TimelineMapping? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("mapping MUST be a mapping", sourcePath, documentId)
            return null
        }
        return when (val kind = map.requireString("kind", sourcePath, diagnostics, documentId)) {
            "none" -> NoTimelineMapping()
            "offset" -> {
                val to = map.requireString("to", sourcePath, diagnostics, documentId) ?: return null
                val unit = map.string("unit", sourcePath, diagnostics, documentId)
                val offset = map.long("offset", sourcePath, diagnostics, documentId)?.toInt() ?: return null
                OffsetTimelineMapping(to, unit, offset)
            }
            "table" -> {
                val to = map.requireString("to", sourcePath, diagnostics, documentId) ?: return null
                val entries = map.map["entries"] as? YamlList ?: run {
                    diagnostics += schemaError("mapping.entries MUST be a list", sourcePath, documentId)
                    return null
                }
                TableTimelineMapping(
                    to = to,
                    entries = entries.values.mapNotNull { entry ->
                        val entryMap = entry as? YamlMap ?: run {
                            diagnostics += schemaError("mapping.entries items MUST be mappings", sourcePath, documentId)
                            return@mapNotNull null
                        }
                        val from = parseMappingEndpoint(entryMap.map["from"], "mapping.entries.from", sourcePath, diagnostics, documentId)
                            ?: return@mapNotNull null
                        val toValue = parseMappingEndpoint(entryMap.map["to"], "mapping.entries.to", sourcePath, diagnostics, documentId)
                            ?: return@mapNotNull null
                        TableTimelineMappingEntry(
                            from = from.first,
                            to = toValue.first,
                            fromTimecode = from.second,
                            toTimecode = toValue.second,
                        )
                    },
                )
            }
            null -> null
            else -> {
                diagnostics += schemaError("Unknown mapping kind: $kind", sourcePath, documentId)
                null
            }
        }
    }

    private fun parseTimecodeSchema(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TimecodeSchema? {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("timecode MUST be a mapping", sourcePath, documentId)
            return null
        }
        val type = map.requireString("type", sourcePath, diagnostics, documentId)?.let {
            runCatching { TimecodeType.valueOf(it) }.getOrElse {
                diagnostics += schemaError("Unknown timecode type: $it", sourcePath, documentId)
                null
            }
        } ?: return null
        val direction = map.string("direction", sourcePath, diagnostics, documentId)?.let {
            runCatching { TimecodeDirection.valueOf(it) }.getOrElse {
                diagnostics += schemaError("Unknown timecode direction: $it", sourcePath, documentId)
                null
            }
        }
        if (type == TimecodeType.tuple && direction != null) {
            diagnostics += schemaError("timecode.direction is only valid for timecode.type: number", sourcePath, documentId)
        }
        return TimecodeSchema(type = type, direction = direction)
    }

    private fun parseMappingEndpoint(
        value: YamlValue?,
        fieldName: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): Pair<String, TimecodeValue?>? {
        return when (value) {
            is YamlString -> value.value to null
            is YamlMap -> {
                val mappedValue = value.requireString("value", sourcePath, diagnostics, documentId) ?: return null
                mappedValue to parseTimecodeValue(value.map["timecode"], fieldName, sourcePath, diagnostics, documentId)
            }
            null -> {
                diagnostics += schemaError("$fieldName is required", sourcePath, documentId)
                null
            }
            else -> {
                diagnostics += schemaError("$fieldName MUST be a string or mapping", sourcePath, documentId)
                null
            }
        }
    }

    private fun parseTimecodeValue(
        value: YamlValue?,
        fieldName: String,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
    ): TimecodeValue? {
        return when (value) {
            null -> null
            is YamlInteger -> NumberTimecode(value.value.toDouble())
            is YamlNumber -> NumberTimecode(value.value)
            is YamlList -> {
                val items = value.values.mapNotNull { item ->
                    when (item) {
                        is YamlInteger -> item.value.toDouble()
                        is YamlNumber -> item.value
                        else -> {
                            diagnostics += schemaError("$fieldName MUST contain only numbers", sourcePath, documentId)
                            null
                        }
                    }
                }
                if (items.size != value.values.size) null else TupleTimecode(items)
            }
            else -> {
                diagnostics += schemaError("$fieldName MUST be a number or number tuple", sourcePath, documentId)
                null
            }
        }?.takeIf { timecode ->
            when (timecode) {
                is NumberTimecode -> timecode.value.isFinite()
                is TupleTimecode -> timecode.values.all(Double::isFinite)
            }.also { valid ->
                if (!valid) diagnostics += schemaError("$fieldName MUST be finite", sourcePath, documentId)
            }
        }
    }

    private fun parseRawObjectEntries(
        value: YamlValue,
        sourcePath: String,
        diagnostics: MutableList<Diagnostic>,
        documentId: String,
        fieldName: String,
    ): Map<String, RawValue> {
        val map = value as? YamlMap ?: run {
            diagnostics += schemaError("$fieldName MUST be a mapping", sourcePath, documentId)
            return emptyMap()
        }
        return map.map.mapValues { (_, rawValue) -> parseRawValue(rawValue) }
    }

    private fun parseRawValue(value: YamlValue): RawValue = when (value) {
        YamlNull -> RawNull
        is YamlBoolean -> RawBoolean(value.value)
        is YamlInteger -> RawInteger(value.value)
        is YamlNumber -> RawNumber(value.value)
        is YamlString -> RawString(value.value)
        is YamlList -> RawArray(value.values.map(::parseRawValue))
        is YamlMap -> RawObject(value.map.mapValues { parseRawValue(it.value) })
    }

    private fun syntaxError(message: String, sourcePath: String, documentId: String? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.SyntaxError, Severity.Error, message, SourceInfo(sourcePath, documentId))

    private fun schemaError(message: String, sourcePath: String, documentId: String? = null): Diagnostic =
        Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, message, SourceInfo(sourcePath, documentId))
}

private data class FrontMatterSplit(
    val frontMatter: String,
    val body: String,
)

private sealed interface YamlValue
private data object YamlNull : YamlValue
private data class YamlBoolean(val value: Boolean) : YamlValue
private data class YamlInteger(val value: Long) : YamlValue
private data class YamlNumber(val value: Double) : YamlValue
private data class YamlString(val value: String) : YamlValue
private data class YamlList(val values: List<YamlValue>) : YamlValue
private data class YamlMap(val map: LinkedHashMap<String, YamlValue>) : YamlValue

private class MiniYamlParser(
    text: String,
    private val sourcePath: String,
    private val diagnostics: MutableList<Diagnostic>,
) {
    private val lines = text.split('\n').map { it.trimEnd() }
    private var index = 0

    fun parse(): YamlValue? {
        skipIgnorable()
        if (index >= lines.size) {
            diagnostics += syntaxError("YAML front matter is empty")
            return null
        }
        val value = parseBlock(indentOf(lines[index]))
        skipIgnorable()
        return value
    }

    private fun parseBlock(expectedIndent: Int): YamlValue? {
        skipIgnorable()
        if (index >= lines.size) return null
        val line = lines[index]
        val indent = indentOf(line)
        if (indent < expectedIndent) return null
        if (indent > expectedIndent) {
            diagnostics += syntaxError("Unexpected indentation in YAML front matter")
            return null
        }
        return if (line.drop(indent).startsWith("- ")) parseList(expectedIndent) else parseMap(expectedIndent)
    }

    private fun parseMap(expectedIndent: Int): YamlMap {
        val result = linkedMapOf<String, YamlValue>()
        while (index < lines.size) {
            skipIgnorable()
            if (index >= lines.size) break
            val line = lines[index]
            val indent = indentOf(line)
            if (indent < expectedIndent) break
            if (indent > expectedIndent) {
                diagnostics += syntaxError("Unexpected indentation in YAML front matter")
                break
            }
            val content = line.drop(indent)
            if (content.startsWith("- ")) break
            val split = splitKeyValue(content)
            if (split == null) {
                diagnostics += syntaxError("Invalid YAML mapping entry: $content")
                index++
                continue
            }
            index++
            val inlineValue = split.second
            val value = if (inlineValue == null) {
                parseNestedBlock(expectedIndent) ?: YamlNull
            } else {
                parseInlineValue(inlineValue)
            }
            result[split.first] = value
        }
        return YamlMap(LinkedHashMap(result))
    }

    private fun parseList(expectedIndent: Int): YamlList {
        val result = mutableListOf<YamlValue>()
        while (index < lines.size) {
            skipIgnorable()
            if (index >= lines.size) break
            val line = lines[index]
            val indent = indentOf(line)
            if (indent < expectedIndent) break
            if (indent != expectedIndent || !line.drop(indent).startsWith("- ")) break
            val remainder = line.drop(indent + 2)
            index++
            val value = when {
                remainder.isBlank() -> parseNestedBlock(expectedIndent) ?: YamlNull
                looksLikeInlineMapEntry(remainder) -> parseListItemMap(remainder, expectedIndent + 2)
                else -> parseInlineValue(remainder)
            }
            result += value
        }
        return YamlList(result)
    }

    private fun parseListItemMap(firstEntry: String, nestedIndent: Int): YamlMap {
        val split = splitKeyValue(firstEntry)
        val result = linkedMapOf<String, YamlValue>()
        if (split != null) {
            result[split.first] = split.second?.let(::parseInlineValue) ?: (parseNestedBlock(nestedIndent - 2) ?: YamlNull)
        } else {
            diagnostics += syntaxError("Invalid YAML mapping entry: $firstEntry")
        }
        while (index < lines.size) {
            skipIgnorable()
            if (index >= lines.size) break
            val line = lines[index]
            val indent = indentOf(line)
            if (indent < nestedIndent) break
            if (indent > nestedIndent) {
                diagnostics += syntaxError("Unexpected indentation in YAML front matter")
                break
            }
            val content = line.drop(indent)
            if (content.startsWith("- ")) break
            val next = splitKeyValue(content)
            if (next == null) {
                diagnostics += syntaxError("Invalid YAML mapping entry: $content")
                index++
                continue
            }
            index++
            result[next.first] = next.second?.let(::parseInlineValue) ?: (parseNestedBlock(nestedIndent) ?: YamlNull)
        }
        return YamlMap(LinkedHashMap(result))
    }

    private fun parseNestedBlock(parentIndent: Int): YamlValue? {
        skipIgnorable()
        if (index >= lines.size) return null
        val actualIndent = indentOf(lines[index])
        if (actualIndent <= parentIndent) return null
        return parseBlock(actualIndent)
    }

    private fun parseInlineValue(raw: String): YamlValue {
        val value = raw.trim()
        if (value.isEmpty()) return YamlNull
        if (value.startsWith("[") && value.endsWith("]")) {
            val inner = value.substring(1, value.lastIndex)
            if (inner.isBlank()) return YamlList(emptyList())
            return YamlList(splitInlineList(inner).map(::parseInlineValue))
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
            return YamlString(parseQuoted(value.substring(1, value.length - 1)))
        }
        if (value.startsWith("'") && value.endsWith("'") && value.length >= 2) {
            return YamlString(value.substring(1, value.length - 1).replace("''", "'"))
        }
        return when {
            value == "null" -> YamlNull
            value == "true" -> YamlBoolean(true)
            value == "false" -> YamlBoolean(false)
            value.matches(Regex("[-+]?[0-9]+")) -> YamlInteger(value.toLong())
            value.matches(Regex("[-+]?[0-9]+\\.[0-9]+")) -> YamlNumber(value.toDouble())
            else -> YamlString(value)
        }
    }

    private fun parseQuoted(value: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch != '\\') {
                result.append(ch)
                i++
                continue
            }
            val next = value.getOrNull(i + 1)
            when (next) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                '\\' -> result.append('\\')
                '"' -> result.append('"')
                else -> if (next != null) result.append(next)
            }
            i += 2
        }
        return result.toString()
    }

    private fun splitInlineList(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var quoteChar = '\u0000'
        value.forEach { ch ->
            when {
                inQuotes && ch == quoteChar -> {
                    inQuotes = false
                    current.append(ch)
                }
                !inQuotes && (ch == '"' || ch == '\'') -> {
                    inQuotes = true
                    quoteChar = ch
                    current.append(ch)
                }
                !inQuotes && ch == ',' -> {
                    parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }

    private fun splitKeyValue(content: String): Pair<String, String?>? {
        val colonIndex = content.indexOf(':')
        if (colonIndex <= 0) return null
        val key = content.substring(0, colonIndex).trim()
        if (key.isEmpty()) return null
        val rest = content.substring(colonIndex + 1)
        return key to rest.takeIf { it.isNotBlank() }?.trim()
    }

    private fun looksLikeInlineMapEntry(content: String): Boolean {
        val colonIndex = content.indexOf(':')
        if (colonIndex <= 0) return false
        val next = content.getOrNull(colonIndex + 1) ?: return true
        return next == ' ' || next == '\t'
    }

    private fun skipIgnorable() {
        while (index < lines.size && lines[index].isBlank()) index++
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) line.length else it }

    private fun syntaxError(message: String): Diagnostic =
        Diagnostic(DiagnosticCategory.SyntaxError, Severity.Error, message, SourceInfo(sourcePath))
}

private fun YamlMap.requireString(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): String? {
    val value = map[key] ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key is required", SourceInfo(sourcePath, documentId))
        return null
    }
    return (value as? YamlString)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a string", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.string(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): String? {
    val value = map[key] ?: return null
    return (value as? YamlString)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a string", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.boolean(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): Boolean? {
    val value = map[key] ?: return null
    return (value as? YamlBoolean)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a boolean", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.long(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): Long? {
    val value = map[key] ?: return null
    return (value as? YamlInteger)?.value ?: run {
        diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be an integer", SourceInfo(sourcePath, documentId))
        null
    }
}

private fun YamlMap.stringList(
    key: String,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String? = null,
): List<String>? {
    val value = map[key] ?: return null
    return when (value) {
        is YamlList -> value.values.mapNotNull {
            (it as? YamlString)?.value ?: run {
                diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key items MUST be strings", SourceInfo(sourcePath, documentId))
                null
            }
        }
        is YamlString -> listOf(value.value)
        else -> {
            diagnostics += Diagnostic(DiagnosticCategory.SchemaError, Severity.Error, "$key MUST be a list of strings", SourceInfo(sourcePath, documentId))
            null
        }
    }
}

private fun parseTimelineSelector(
    value: YamlValue,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String?,
    fieldName: String,
): TimelineSelector? {
    return when (value) {
        is YamlString -> if (value.value == "any") TimelineSelector.Any else TimelineSelector.Id(value.value)
        is YamlMap -> {
            val mapped = (value.map["mapped"] as? YamlString)?.value
            if (mapped == null) {
                diagnostics += Diagnostic(
                    DiagnosticCategory.SchemaError,
                    Severity.Error,
                    "$fieldName selector mapping MUST have a string 'mapped' field",
                    SourceInfo(sourcePath, documentId),
                )
                null
            } else {
                TimelineSelector.Mapped(mapped)
            }
        }
        else -> {
            diagnostics += Diagnostic(
                DiagnosticCategory.SchemaError,
                Severity.Error,
                "$fieldName MUST be a Timeline identifier, 'any', or a { mapped: Identifier } mapping",
                SourceInfo(sourcePath, documentId),
            )
            null
        }
    }
}

private fun parseTimelineSelectors(
    value: YamlValue,
    sourcePath: String,
    diagnostics: MutableList<Diagnostic>,
    documentId: String?,
    fieldName: String,
): List<TimelineSelector>? {
    return when (value) {
        is YamlList -> value.values.mapNotNull {
            parseTimelineSelector(it, sourcePath, diagnostics, documentId, fieldName)
        }
        else -> parseTimelineSelector(value, sourcePath, diagnostics, documentId, fieldName)?.let(::listOf)
    }
}
