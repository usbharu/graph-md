package dev.usbharu.graphmd.core

internal object RelationTargetParser {
    fun parse(value: String): Pair<String, String>? {
        val trimmed = value.trim()
        val separator = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        if (separator <= 0) return null
        val target = trimmed.substring(0, separator)
        val typePart = trimmed.substring(separator).trim()
        if (typePart.isEmpty()) return null
        if (typePart.first() == '"') {
            val relType = parseQuotedRelationType(typePart) ?: return null
            if (relType.isEmpty() || relType.any { it.isWhitespace() }) return null
            return target to relType
        }
        if (typePart.any { it.isWhitespace() || it == ')' }) return null
        return target to typePart
    }

    private fun parseQuotedRelationType(value: String): String? {
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
                return if (value.substring(index + 1).trim().isEmpty()) builder.toString() else null
            } else {
                builder.append(char)
            }
            index += 1
        }
        return null
    }
}
