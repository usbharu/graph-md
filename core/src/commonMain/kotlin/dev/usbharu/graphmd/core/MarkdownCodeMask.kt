package dev.usbharu.graphmd.core

internal fun maskMarkdownCodeRegions(body: String): String {
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
        if (lineStart && (body.startsWith("    ", index) || chars[index] == '\t')) {
            var end = index
            while (end < chars.size && chars[end] != '\n') {
                chars[end] = ' '
                end += 1
            }
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
