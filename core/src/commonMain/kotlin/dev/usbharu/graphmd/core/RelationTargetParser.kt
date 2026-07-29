package dev.usbharu.graphmd.core

internal object RelationTargetParser {
    data class Parsed(
        val target: String,
        val relType: String,
        val targetRange: IntRange,
        val relTypeRange: IntRange,
    )

    fun parse(value: String): Pair<String, String>? {
        val parsed = parseDetailed(value) ?: return null
        return parsed.target to parsed.relType
    }

    fun parseDetailed(value: String): Parsed? {
        val leading = value.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val trailing = value.indexOfLast { !it.isWhitespace() } + 1
        val trimmed = value.substring(leading, trailing)
        val separator = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (separator <= 0) return null
        val target = trimmed.substring(0, separator)
        var typeStart = separator
        while (typeStart < trimmed.length && (trimmed[typeStart] == ' ' || trimmed[typeStart] == '\t')) typeStart++
        val typePart = trimmed.substring(typeStart)
        if (typePart.isEmpty()) return null
        if (typePart.first() == '"') {
            val quoted = parseQuotedRelationType(typePart) ?: return null
            val relType = quoted.first
            if (relType.isEmpty()) return null
            return Parsed(
                target,
                relType,
                leading until leading + separator,
                leading + typeStart + 1 until leading + typeStart + quoted.second,
            )
        }
        if (typePart.any { it == ' ' || it == '\t' || it == ')' }) return null
        return Parsed(
            target,
            typePart,
            leading until leading + separator,
            leading + typeStart until leading + typeStart + typePart.length,
        )
    }

    private fun parseQuotedRelationType(value: String): Pair<String, Int>? {
        val builder = StringBuilder()
        var index = 1
        var escaped = false
        while (index < value.length) {
            val char = value[index]
            if (escaped) {
                builder.append(char)
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == '"') {
                return if (value.substring(index + 1).trim().isEmpty()) builder.toString() to index else null
            } else {
                builder.append(char)
            }
            index += 1
        }
        return null
    }
}
