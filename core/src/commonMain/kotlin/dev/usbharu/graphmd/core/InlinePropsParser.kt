package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

class InlinePropsParseException(message: String) : IllegalArgumentException(message)

class InlinePropsParser(private val input: String) {
    private var index: Int = 0

    fun parseObject(): RawObject {
        skipHorizontalAndNewlines()
        val value = parseInlineObject()
        skipHorizontalAndNewlines()
        if (!isEof()) {
            fail("Unexpected trailing content")
        }
        return value
    }

    private fun parseInlineObject(): RawObject {
        expect('{')
        skipHorizontalAndNewlines()
        val result = linkedMapOf<String, RawValue>()
        while (!tryConsume('}')) {
            val key = parseIdentifier()
            skipHorizontalAndNewlines()
            val annotation = if (peek() == '(') parseKeyAnnotation() else KeyAnnotation()
            skipHorizontalAndNewlines()
            expect('=')
            skipHorizontalAndNewlines()
            var value = parseValue()
            annotation.textKey?.let { textKey ->
                value = RawObject(mapOf(textKey to value))
            }
            annotation.validTime?.let { validTime ->
                value = RawObject(mapOf("value" to value, "validTime" to validTime))
            }
            val previous = result[key]
            result[key] = when {
                previous == null && annotation.validTime != null -> RawArray(listOf(value))
                previous == null -> value
                annotation.textKey != null && previous is RawObject && value is RawObject ->
                    RawObject(previous.values + value.values)
                annotation.validTime != null ->
                    mergeTimedAssertion(previous, value as RawObject, annotation.validTime)
                timedEntries(previous) != null ->
                    appendFallbackAssertion(key, previous, value)
                else -> fail("Duplicate key: $key")
            }
            val hadWhitespace = skipHorizontalAndNewlines()
            if (tryConsume(',')) {
                skipHorizontalAndNewlines()
            } else if (peek() != '}' && !hadWhitespace) {
                fail("Expected separator")
            }
        }
        return RawObject(result)
    }

    private fun mergeTimedAssertion(previous: RawValue, incoming: RawObject, validTime: RawArray): RawArray {
        val entries = timedEntries(previous)?.toMutableList()
            ?: mutableListOf(RawObject(mapOf("value" to previous)))
        val signature = validTimeSignature(validTime)
        val existingIndex = entries.indexOfFirst { entry ->
            (entry.values["validTime"] as? RawArray)?.let(::validTimeSignature) == signature
        }
        if (existingIndex >= 0) {
            entries[existingIndex] = incoming
        } else {
            entries += incoming
        }
        return RawArray(entries)
    }

    private fun appendFallbackAssertion(key: String, previous: RawValue, value: RawValue): RawArray {
        val variants = timedEntries(previous) ?: fail("Duplicate key: $key")
        if (variants.any { "validTime" !in it.values }) {
            fail("Duplicate key: $key")
        }
        return RawArray(listOf(RawObject(mapOf("value" to value))) + variants)
    }

    private fun timedEntries(value: RawValue): List<RawObject>? {
        val values = (value as? RawArray)?.values ?: return null
        return values.map { entry ->
            val obj = entry as? RawObject ?: return null
            if ("value" !in obj.values || obj.values.keys.any { it !in setOf("value", "validTime") }) return null
            obj
        }
    }

    private fun validTimeSignature(validTime: RawArray): String =
        validTime.values.map { entry ->
            val time = entry as? RawObject ?: return@map rawValueToJsonString(entry)
            fun point(name: String): String {
                val point = time.values[name] as? RawObject ?: return ""
                return listOf("value", "timecode").joinToString(";") { key ->
                    point.values[key]?.let(::rawValueToJsonString) ?: ""
                }
            }
            "${(time.values["timeline"] as? RawString)?.value}|${point("from")}|${point("to")}"
        }.sorted().joinToString("||")

    private fun parseKeyAnnotation(): KeyAnnotation {
        expect('(')
        skipHorizontalAndNewlines()
        var textKey: String? = null
        var validTime: RawArray? = null
        while (!tryConsume(')')) {
            val name = parseIdentifier()
            skipHorizontalAndNewlines()
            expect('=')
            skipHorizontalAndNewlines()
            when (name) {
                "key" -> textKey = if (peek() == '"') parseQuotedString() else parseIdentifier()
                "validTime" -> validTime = parseValidTimeExpression()
                else -> fail("Unknown key annotation: $name")
            }
            skipHorizontalAndNewlines()
            if (tryConsume(',')) skipHorizontalAndNewlines() else if (peek() != ')') fail("Expected annotation separator")
        }
        if (textKey != null && validTime != null) fail("key and validTime cannot be combined")
        return KeyAnnotation(textKey, validTime)
    }

    private fun parseValidTimeExpression(): RawArray {
        val entries = mutableListOf<RawValue>()
        if (tryConsume('[')) {
            skipHorizontalAndNewlines()
            while (!tryConsume(']')) {
                entries += parseValidTimeEntry()
                skipHorizontalAndNewlines()
                if (tryConsume(',')) skipHorizontalAndNewlines() else if (peek() != ']') fail("validTime entries must be comma-separated")
            }
        } else {
            entries += parseValidTimeEntry()
        }
        return RawArray(entries)
    }

    private fun parseValidTimeEntry(): RawObject {
        val timeline = parseIdentifier()
        val values = linkedMapOf<String, RawValue>("timeline" to RawString(timeline))
        if (tryConsume('(')) {
            skipHorizontalAndNewlines()
            while (!tryConsume(')')) {
                val bound = parseIdentifier()
                if (bound !in setOf("from", "to")) fail("validTime only accepts from and to")
                skipHorizontalAndNewlines()
                expect('=')
                skipHorizontalAndNewlines()
                val rawPoint = parseValue()
                val point = when (rawPoint) {
                    is RawInteger -> RawObject(mapOf("timecode" to rawPoint))
                    is RawNumber -> RawObject(mapOf("timecode" to rawPoint))
                    is RawObject -> rawPoint
                    else -> fail("validTime bound must be numeric or a timePoint object")
                }
                values[bound] = point
                skipHorizontalAndNewlines()
                if (tryConsume(',')) skipHorizontalAndNewlines() else if (peek() != ')') fail("validTime bounds must be comma-separated")
            }
        }
        return RawObject(values)
    }

    private data class KeyAnnotation(
        val textKey: String? = null,
        val validTime: RawArray? = null,
    )

    private fun parseValue(): RawValue {
        skipHorizontalAndNewlines()
        return when (val ch = peek()) {
            '"' -> RawString(parseQuotedString())
            '{' -> parseInlineObject()
            '[' -> parseArray()
            null -> fail("Expected value")
            else -> parseScalarOrBareString(ch)
        }
    }

    private fun parseArray(): RawArray {
        expect('[')
        skipHorizontalAndNewlines()
        val values = mutableListOf<RawValue>()
        while (!tryConsume(']')) {
            values += parseValue()
            skipHorizontalAndNewlines()
            if (tryConsume(',')) {
                skipHorizontalAndNewlines()
            } else if (peek() != ']') {
                fail("Array elements must be comma-separated")
            }
        }
        return RawArray(values)
    }

    private fun parseScalarOrBareString(first: Char): RawValue {
        if (first == '-' || first.isDigit()) {
            val token = parseToken()
            return when {
                token.matches(Regex("-?[0-9]+")) -> RawInteger(token.toLong())
                token.matches(Regex("-?[0-9]+\\.[0-9]+")) -> RawNumber(token.toDouble())
                else -> fail("Invalid numeric token: $token")
            }
        }
        return when (val token = parseIdentifier()) {
            "true" -> RawBoolean(true)
            "false" -> RawBoolean(false)
            "null" -> RawNull
            else -> RawString(token)
        }
    }

    private fun parseQuotedString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            val ch = peek() ?: fail("Unterminated string")
            advance()
            when (ch) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(ch)
            }
        }
    }

    private fun parseEscape(): Char {
        val escaped = peek() ?: fail("Unterminated escape sequence")
        advance()
        return when (escaped) {
            '"' -> '"'
            '\\' -> '\\'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                val hex = buildString {
                    repeat(4) {
                        append(peek() ?: fail("Incomplete unicode escape"))
                        advance()
                    }
                }
                hex.toInt(16).toChar()
            }
            else -> fail("Unsupported escape: \\$escaped")
        }
    }

    private fun parseIdentifier(): String {
        val start = index
        val first = peek() ?: fail("Expected identifier")
        if (!(first.isLetter() || first == '_')) {
            fail("Expected identifier")
        }
        advance()
        while (peek()?.let { it.isLetterOrDigit() || it == '_' || it == '.' || it == ':' || it == '-' } == true) {
            advance()
        }
        return input.substring(start, index)
    }

    private fun parseToken(): String {
        val start = index
        if (peek() == '-') advance()
        while (peek()?.isDigit() == true) advance()
        if (peek() == '.') {
            advance()
            while (peek()?.isDigit() == true) advance()
        }
        return input.substring(start, index)
    }

    private fun skipHorizontalAndNewlines(): Boolean {
        var consumed = false
        while (peek()?.isWhitespace() == true) {
            consumed = true
            advance()
        }
        return consumed
    }

    private fun expect(expected: Char) {
        val actual = peek()
        if (actual != expected) {
            fail("Expected '$expected' but found '${actual ?: "<eof>"}'")
        }
        advance()
    }

    private fun tryConsume(expected: Char): Boolean {
        if (peek() == expected) {
            advance()
            return true
        }
        return false
    }

    private fun peek(): Char? = input.getOrNull(index)

    private fun advance() {
        index += 1
    }

    private fun isEof(): Boolean = index >= input.length

    private fun fail(message: String): Nothing = throw InlinePropsParseException("$message at index $index")
}
