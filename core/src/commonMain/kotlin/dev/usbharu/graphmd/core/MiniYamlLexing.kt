package dev.usbharu.graphmd.core

internal data class YamlFlowSlice(
    val start: Int,
    val raw: String,
)

internal fun stripYamlComment(value: String): String {
    var quote: Char? = null
    var escaped = false
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (quote != null) {
            when {
                escaped -> escaped = false
                quote == '"' && char == '\\' -> escaped = true
                quote == '\'' && char == '\'' && value.getOrNull(index + 1) == '\'' -> index++
                char == quote -> quote = null
            }
        } else {
            when {
                char == '\'' || char == '"' -> quote = char
                char == '#' && (index == 0 || value[index - 1].isWhitespace()) -> return value.substring(0, index)
            }
        }
        index++
    }
    return value
}

internal fun splitYamlFlowItems(value: String): List<YamlFlowSlice> {
    val result = mutableListOf<YamlFlowSlice>()
    var start = 0
    var quote: Char? = null
    var escaped = false
    var nestedDepth = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (quote != null) {
            when {
                escaped -> escaped = false
                quote == '"' && char == '\\' -> escaped = true
                quote == '\'' && char == '\'' && value.getOrNull(index + 1) == '\'' -> index++
                char == quote -> quote = null
            }
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '[', '{' -> nestedDepth++
                ']', '}' -> if (nestedDepth > 0) nestedDepth--
                ',' -> if (nestedDepth == 0) {
                    result += YamlFlowSlice(start, value.substring(start, index))
                    start = index + 1
                }
            }
        }
        index++
    }
    result += YamlFlowSlice(start, value.substring(start))
    return result
}

internal fun findYamlMappingColon(value: String): Int {
    var quote: Char? = null
    var escaped = false
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (quote != null) {
            when {
                escaped -> escaped = false
                quote == '"' && char == '\\' -> escaped = true
                quote == '\'' && char == '\'' && value.getOrNull(index + 1) == '\'' -> index++
                char == quote -> quote = null
            }
        } else {
            when (char) {
                '\'', '"' -> quote = char
                ':' -> {
                    val next = value.getOrNull(index + 1)
                    if (next == null || next.isWhitespace()) return index
                }
            }
        }
        index++
    }
    return -1
}

internal fun decodeYamlScalar(raw: String): String {
    val value = raw.trim()
    if (value.length < 2) return value
    if (value.first() == '\'' && value.last() == '\'') {
        return value.substring(1, value.length - 1).replace("''", "'")
    }
    if (value.first() != '"' || value.last() != '"') return value
    val result = StringBuilder()
    var index = 1
    while (index < value.length - 1) {
        val char = value[index]
        if (char != '\\') {
            result.append(char)
            index++
            continue
        }
        when (val next = value.getOrNull(index + 1)) {
            'n' -> result.append('\n')
            'r' -> result.append('\r')
            't' -> result.append('\t')
            '\\' -> result.append('\\')
            '"' -> result.append('"')
            else -> if (next != null) result.append(next)
        }
        index += 2
    }
    return result.toString()
}
