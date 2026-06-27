package dev.usbharu.graphmd.core.model

internal fun rawValueToJsonString(value: RawValue): String = when (value) {
    is RawString -> escapeJsonString(value.value)
    is RawInteger -> value.value.toString()
    is RawNumber -> value.value.toString()
    is RawBoolean -> value.value.toString()
    RawNull -> "null"
    is RawArray -> value.values.joinToString(prefix = "[", postfix = "]", separator = ",") { rawValueToJsonString(it) }
    is RawObject -> value.values.entries
        .joinToString(prefix = "{", postfix = "}", separator = ",") { "${escapeJsonString(it.key)}:${rawValueToJsonString(it.value)}" }
}

internal fun rawObjectToJsonString(value: RawObject): String =
    value.values.entries
        .joinToString(prefix = "{", postfix = "}", separator = ",") { "${escapeJsonString(it.key)}:${rawValueToJsonString(it.value)}" }

internal fun escapeJsonString(value: String): String {
    val builder = StringBuilder(value.length + 2)
    builder.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            '\u0008' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            else -> if (ch.code < 0x20) {
                builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            } else {
                builder.append(ch)
            }
        }
    }
    builder.append('"')
    return builder.toString()
}
