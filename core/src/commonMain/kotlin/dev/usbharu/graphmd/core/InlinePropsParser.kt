package dev.usbharu.graphmd.core

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
            expect('=')
            skipHorizontalAndNewlines()
            if (key in result) {
                fail("Duplicate key: $key")
            }
            result[key] = parseValue()
            val hadWhitespace = skipHorizontalAndNewlines()
            if (tryConsume(',')) {
                skipHorizontalAndNewlines()
            } else if (peek() != '}' && !hadWhitespace) {
                fail("Expected separator")
            }
        }
        return RawObject(result)
    }

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
        val token = parseIdentifier()
        return when (token) {
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
