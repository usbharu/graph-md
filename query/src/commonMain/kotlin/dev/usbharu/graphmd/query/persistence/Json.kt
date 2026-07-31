package dev.usbharu.graphmd.query.persistence

internal sealed interface Json {
    data class Object(val values: Map<String, Json>) : Json
    data class Array(val values: List<Json>) : Json
    data class StringValue(val value: String) : Json
    data class NumberValue(val value: String) : Json
    data class BooleanValue(val value: Boolean) : Json
    data object Null : Json
}

internal fun jsonObject(vararg values: Pair<String, Json>): Json.Object =
    Json.Object(linkedMapOf(*values))

internal fun jsonArray(values: Iterable<Json>): Json.Array = Json.Array(values.toList())
internal fun jsonString(value: String): Json.StringValue = Json.StringValue(value)
internal fun jsonNumber(value: Number): Json.NumberValue = Json.NumberValue(value.toString())
internal fun jsonBoolean(value: Boolean): Json.BooleanValue = Json.BooleanValue(value)
internal fun jsonNullableString(value: String?): Json = value?.let(::jsonString) ?: Json.Null
internal fun jsonNullableNumber(value: Double?): Json = value?.let(::jsonNumber) ?: Json.Null
internal fun jsonNullableLong(value: Long?): Json = value?.let(::jsonNumber) ?: Json.Null

internal fun Json.encode(): String = when (this) {
    is Json.Object -> values.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
        "${escapeJson(it.key)}:${it.value.encode()}"
    }
    is Json.Array -> values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.encode() }
    is Json.StringValue -> escapeJson(value)
    is Json.NumberValue -> value
    is Json.BooleanValue -> value.toString()
    Json.Null -> "null"
}

private fun escapeJson(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
}

internal fun parseJson(text: String): Json = JsonParser(text).parse()

private class JsonParser(
    private val text: String,
) {
    private var index = 0

    fun parse(): Json {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        require(index == text.length) { "Unexpected JSON content at offset $index" }
        return value
    }

    private fun parseValue(): Json {
        require(index < text.length) { "Unexpected end of JSON input" }
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.StringValue(parseString())
            't' -> parseLiteral("true", Json.BooleanValue(true))
            'f' -> parseLiteral("false", Json.BooleanValue(false))
            'n' -> parseLiteral("null", Json.Null)
            '-', in '0'..'9' -> parseNumber()
            else -> error("Unexpected JSON token at offset $index")
        }
    }

    private fun parseObject(): Json.Object {
        index++
        skipWhitespace()
        val values = linkedMapOf<String, Json>()
        if (consume('}')) return Json.Object(values)
        while (true) {
            require(peek() == '"') { "Expected an object key at offset $index" }
            val key = parseString()
            skipWhitespace()
            require(consume(':')) { "Expected ':' after object key at offset $index" }
            skipWhitespace()
            require(key !in values) { "Duplicate JSON object key: $key" }
            values[key] = parseValue()
            skipWhitespace()
            if (consume('}')) break
            require(consume(',')) { "Expected ',' in object at offset $index" }
            skipWhitespace()
        }
        return Json.Object(values)
    }

    private fun parseArray(): Json.Array {
        index++
        skipWhitespace()
        val values = mutableListOf<Json>()
        if (consume(']')) return Json.Array(values)
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consume(']')) break
            require(consume(',')) { "Expected ',' in array at offset $index" }
            skipWhitespace()
        }
        return Json.Array(values)
    }

    private fun parseString(): String {
        require(consume('"'))
        return buildString {
            while (true) {
                require(index < text.length) { "Unterminated JSON string" }
                when (val character = text[index++]) {
                    '"' -> return@buildString
                    '\\' -> {
                        require(index < text.length) { "Unterminated JSON escape" }
                        append(
                            when (val escaped = text[index++]) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    require(index + 4 <= text.length) { "Invalid Unicode escape" }
                                    text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                                }
                                else -> error("Unknown JSON escape: $escaped")
                            },
                        )
                    }
                    else -> append(character)
                }
            }
        }
    }

    private fun parseNumber(): Json.NumberValue {
        val start = index
        if (peek() == '-') index++
        if (peek() == '0') {
            index++
        } else {
            require(peek() in '1'..'9') { "Invalid JSON number at offset $index" }
            while (peek() in '0'..'9') index++
        }
        if (peek() == '.') {
            index++
            require(peek() in '0'..'9') { "Invalid JSON fraction at offset $index" }
            while (peek() in '0'..'9') index++
        }
        if (peek() == 'e' || peek() == 'E') {
            index++
            if (peek() == '+' || peek() == '-') index++
            require(peek() in '0'..'9') { "Invalid JSON exponent at offset $index" }
            while (peek() in '0'..'9') index++
        }
        return Json.NumberValue(text.substring(start, index))
    }

    private fun <T : Json> parseLiteral(literal: String, value: T): T {
        require(text.startsWith(literal, index)) { "Expected '$literal' at offset $index" }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') index++
    }

    private fun consume(character: Char): Boolean {
        if (peek() != character) return false
        index++
        return true
    }

    private fun peek(): Char? = text.getOrNull(index)
}

internal fun Json.objectValue(): Map<String, Json> = (this as Json.Object).values
internal fun Json.arrayValue(): List<Json> = (this as Json.Array).values
internal fun Json.stringValue(): String = (this as Json.StringValue).value
internal fun Json.intValue(): Int = (this as Json.NumberValue).value.toInt()
internal fun Json.longValue(): Long = (this as Json.NumberValue).value.toLong()
internal fun Json.doubleValue(): Double = (this as Json.NumberValue).value.toDouble()
internal fun Json.booleanValue(): Boolean = (this as Json.BooleanValue).value
internal fun Json.nullableStringValue(): String? = if (this === Json.Null) null else stringValue()
internal fun Json.nullableDoubleValue(): Double? = if (this === Json.Null) null else doubleValue()
internal fun Json.nullableLongValue(): Long? = if (this === Json.Null) null else longValue()

internal fun Map<String, Json>.required(name: String): Json =
    requireNotNull(this[name]) { "Missing JSON field: $name" }
