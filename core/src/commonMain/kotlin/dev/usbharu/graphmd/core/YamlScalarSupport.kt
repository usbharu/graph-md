package dev.usbharu.graphmd.core

internal fun stripYamlTrailingComment(raw: String): String {
    var quote: Char? = null
    var escaped = false
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        when {
            quote == '"' && escaped -> escaped = false
            quote == '"' && char == '\\' -> escaped = true
            quote == '\'' && char == '\'' && raw.getOrNull(index + 1) == '\'' -> index++
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
            quote == null && char == '#' && (index == 0 || raw[index - 1].isWhitespace()) ->
                return raw.substring(0, index).trimEnd()
        }
        index++
    }
    return raw.trimEnd()
}

internal fun decodeDoubleQuotedYamlScalar(value: String): String {
    val result = StringBuilder()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '\\') {
            result.append(char)
            index++
            continue
        }
        when (val escaped = value.getOrNull(index + 1)) {
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            '\\' -> result.append('\\')
            '"' -> result.append('"')
            else -> if (escaped != null) result.append(escaped)
        }
        index += 2
    }
    return result.toString()
}

internal data class YamlInlineItem(val raw: String, val start: Int)

internal fun splitYamlInlineList(value: String): List<YamlInlineItem> {
    val items = mutableListOf<YamlInlineItem>()
    var start = 0
    var quote: Char? = null
    var escaped = false
    var index = 0
    while (index <= value.length) {
        val char = value.getOrNull(index)
        when {
            index == value.length || char == ',' && quote == null -> {
                items += YamlInlineItem(value.substring(start, index), start)
                start = index + 1
            }
            quote == '"' && escaped -> escaped = false
            quote == '"' && char == '\\' -> escaped = true
            quote == '\'' && char == '\'' && value.getOrNull(index + 1) == '\'' -> index++
            quote != null && char == quote -> quote = null
            quote == null && (char == '"' || char == '\'') -> quote = char
        }
        index++
    }
    return items.filter { it.raw.isNotBlank() }
}
