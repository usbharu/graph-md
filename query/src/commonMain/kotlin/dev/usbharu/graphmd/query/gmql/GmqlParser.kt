package dev.usbharu.graphmd.query.gmql

import dev.usbharu.graphmd.query.model.RelationDirection

internal class GmqlParseException(
    message: String,
    val sourceRange: GmqlSourceRange,
) : IllegalArgumentException(message)

private enum class TokenKind { IDENT, STRING, INTEGER, DECIMAL, PARAMETER, SYMBOL, EOF }
private data class Token(
    val kind: TokenKind,
    val text: String,
    val start: Int,
    val end: Int,
    val quoted: Boolean = false,
) {
    val range get() = GmqlSourceRange(start, end)
}

internal object GmqlParser {
    fun parse(source: String): GmqlQueryAst = Parser(Lexer(source).lex()).parse()
}

private class Lexer(private val source: String) {
    private var index = 0
    fun lex(): List<Token> = buildList {
        while (true) {
            skipIgnored()
            if (index >= source.length) {
                add(Token(TokenKind.EOF, "", index, index))
                break
            }
            val start = index
            val c = source[index]
            when {
                c == '"' -> add(readString())
                c == '`' -> add(readQuotedIdentifier())
                c == '$' -> add(readParameter())
                c.isLetter() || c == '_' -> add(readIdentifier())
                c.isDigit() -> add(readNumber())
                else -> add(readSymbol(start))
            }
        }
    }

    private fun skipIgnored() {
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index++
                source.startsWith("//", index) -> {
                    index += 2
                    while (index < source.length && source[index] != '\n') index++
                }
                source.startsWith("/*", index) -> {
                    val start = index
                    index += 2
                    while (index + 1 < source.length && !source.startsWith("*/", index)) index++
                    if (index + 1 >= source.length) fail("Unterminated block comment", start, source.length)
                    index += 2
                }
                else -> return
            }
        }
    }

    private fun readString(): Token {
        val start = index++
        val value = buildString {
            while (index < source.length) {
                when (val c = source[index++]) {
                    '"' -> return Token(TokenKind.STRING, toString(), start, index)
                    '\\' -> {
                        if (index >= source.length) fail("Unterminated string escape", start, index)
                        append(
                            when (val escaped = source[index++]) {
                                '"', '\\', '/' -> escaped
                                'b' -> '\b'
                                'f' -> '\u000c'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> {
                                    if (index + 4 > source.length) fail("Invalid Unicode escape", index - 2, source.length)
                                    source.substring(index, index + 4).toIntOrNull(16)?.toChar()
                                        ?.also { index += 4 }
                                        ?: fail("Invalid Unicode escape", index - 2, index + 4)
                                }
                                else -> fail("Unknown string escape '\\$escaped'", index - 2, index)
                            },
                        )
                    }
                    else -> {
                        if (c.code < 0x20) fail("Control character in string", index - 1, index)
                        append(c)
                    }
                }
            }
        }
        fail("Unterminated string", start, source.length)
    }

    private fun readQuotedIdentifier(): Token {
        val start = index++
        val end = source.indexOf('`', index)
        if (end < 0) fail("Unterminated quoted identifier", start, source.length)
        val value = source.substring(index, end)
        index = end + 1
        return Token(TokenKind.IDENT, value, start, index, quoted = true)
    }

    private fun readParameter(): Token {
        val start = index++
        if (index >= source.length || !(source[index].isLetter() || source[index] == '_')) {
            fail("Expected parameter name", start, index)
        }
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
        return Token(TokenKind.PARAMETER, source.substring(start + 1, index), start, index)
    }

    private fun readIdentifier(): Token {
        val start = index++
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
        return Token(TokenKind.IDENT, source.substring(start, index), start, index)
    }

    private fun readNumber(): Token {
        val start = index
        while (index < source.length && source[index].isDigit()) index++
        var kind = TokenKind.INTEGER
        if (index < source.length && source[index] == '.' && source.getOrNull(index + 1)?.isDigit() == true) {
            kind = TokenKind.DECIMAL
            index++
            while (index < source.length && source[index].isDigit()) index++
        }
        if (source.getOrNull(index)?.lowercaseChar() == 'e') {
            kind = TokenKind.DECIMAL
            index++
            if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') index++
            if (source.getOrNull(index)?.isDigit() != true) fail("Invalid exponent", start, index)
            while (index < source.length && source[index].isDigit()) index++
        }
        return Token(kind, source.substring(start, index), start, index)
    }

    private fun readSymbol(start: Int): Token {
        val multi = listOf("->", "<-", "<=", ">=", "!=", "+INF", "-INF").firstOrNull { source.startsWith(it, index) }
        if (multi != null) {
            index += multi.length
            return Token(TokenKind.SYMBOL, multi, start, index)
        }
        val c = source[index++]
        if (c !in "()[],:;.+-*/%=<>") fail("Unexpected character '$c'", start, index)
        return Token(TokenKind.SYMBOL, c.toString(), start, index)
    }

    private fun fail(message: String, start: Int, end: Int): Nothing =
        throw GmqlParseException(message, GmqlSourceRange(start, end))
}

private class Parser(private val tokens: List<Token>) {
    private var index = 0

    fun parse(): GmqlQueryAst {
        val patterns = mutableListOf<GmqlPattern>()
        do {
            expectKeyword("MATCH")
            do patterns += parsePattern() while (consume(","))
        } while (peekKeyword("MATCH"))
        val where = if (consumeKeyword("WHERE")) parseExpression() else null
        val valid = if (consumeKeyword("VALID")) parseValid() else null
        expectKeyword("RETURN")
        val distinct = consumeKeyword("DISTINCT")
        val returns = buildList {
            do {
                val expression = parseExpression()
                add(GmqlReturnItem(expression, if (consumeKeyword("AS")) expectIdentifier().text else null))
            } while (consume(","))
        }
        val order = if (consumeKeyword("ORDER")) {
            expectKeyword("BY")
            buildList {
                do {
                    val expression = parseExpression()
                    val ascending = when {
                        consumeKeyword("ASC") -> true
                        consumeKeyword("DESC") -> false
                        else -> true
                    }
                    add(GmqlOrderItem(expression, ascending))
                } while (consume(","))
            }
        } else emptyList()
        val offset = if (consumeKeyword("OFFSET")) parseExpression() else null
        val limit = if (consumeKeyword("LIMIT")) parseExpression() else null
        consume(";")
        if (peek().kind != TokenKind.EOF) fail("Unexpected token '${peek().text}'", peek())
        return GmqlQueryAst(patterns, where, valid, distinct, returns, order, offset, limit)
    }

    private fun parsePattern(): GmqlPattern {
        val nodes = mutableListOf(parseNode())
        val relations = mutableListOf<GmqlRelationPattern>()
        while (peek().text == "-" || peek().text == "<-") {
            val start = peek().start
            val incoming = consume("<-")
            if (!incoming) expect("-")
            expect("[")
            var variable: String? = null
            var type: String? = null
            if (peek().kind == TokenKind.IDENT && peek().text != ":") variable = takeIdentifier().text
            if (consume(":")) type = expectIdentifier().text
            expect("]")
            val direction = when {
                incoming -> {
                    expect("-")
                    RelationDirection.INCOMING
                }
                consume("->") -> RelationDirection.OUTGOING
                consume("-") -> RelationDirection.EITHER
                else -> fail("Expected relation direction", peek())
            }
            relations += GmqlRelationPattern(variable, type, direction, GmqlSourceRange(start, previous().end))
            nodes += parseNode()
        }
        return GmqlPattern(nodes, relations)
    }

    private fun parseNode(): GmqlNodePattern {
        val start = expect("(").start
        var variable: String? = null
        var type: String? = null
        if (peek().kind == TokenKind.IDENT) variable = takeIdentifier().text
        if (consume(":")) type = expectIdentifier().text
        val end = expect(")").end
        return GmqlNodePattern(variable, type, GmqlSourceRange(start, end))
    }

    private fun parseValid(): GmqlValid {
        val start = previous().start
        val timeline = if (consumeKeyword("ON")) expectIdentifier().text else null
        val valid = when {
            consumeKeyword("ANYTIME") -> GmqlValid(timeline, GmqlValidOperator.ANYTIME, range = rangeFrom(start))
            consumeKeyword("AT") -> GmqlValid(
                timeline, GmqlValidOperator.AT, instant = parseExpression(), range = rangeFrom(start),
            )
            consumeKeyword("OVERLAPS") -> GmqlValid(
                timeline, GmqlValidOperator.OVERLAPS, interval = parseInterval(), range = rangeFrom(start),
            )
            consumeKeyword("CONTAINS") -> GmqlValid(
                timeline, GmqlValidOperator.CONTAINS, interval = parseInterval(), range = rangeFrom(start),
            )
            consumeKeyword("DURING") -> GmqlValid(
                timeline, GmqlValidOperator.DURING, interval = parseInterval(), range = rangeFrom(start),
            )
            else -> fail("Expected ANYTIME, AT, OVERLAPS, CONTAINS, or DURING", peek())
        }
        return if (consumeKeyword("WITHIN")) {
            valid.copy(expansionWindow = parseInterval(), range = rangeFrom(start))
        } else {
            valid
        }
    }

    private fun parseInterval(): GmqlIntervalExpression {
        val includeStart = when {
            consume("[") -> true
            consume("(") -> false
            else -> fail("Expected interval opening delimiter", peek())
        }
        val start = if (consume("-INF")) null else parseExpression()
        expect(",")
        val end = if (consume("+INF")) null else parseExpression()
        val includeEnd = when {
            consume("]") -> true
            consume(")") -> false
            else -> fail("Expected interval closing delimiter", peek())
        }
        return GmqlIntervalExpression(start, end, includeStart, includeEnd)
    }

    private fun parseExpression(): GmqlExpression = parseOr()
    private fun parseOr(): GmqlExpression = leftAssociative(::parseAnd, listOf("OR"))
    private fun parseAnd(): GmqlExpression = leftAssociative(::parseNot, listOf("AND"))
    private fun parseNot(): GmqlExpression =
        if (consumeKeyword("NOT")) {
            val start = previous().start
            val operand = parseNot()
            GmqlExpression.Unary("NOT", operand, GmqlSourceRange(start, operand.range.end))
        } else parseComparison()

    private fun parseComparison(): GmqlExpression {
        var left = parseAdditive()
        if (consumeKeyword("IS")) {
            val negated = consumeKeyword("NOT")
            val missing = when {
                consumeKeyword("NULL") -> false
                consumeKeyword("MISSING") -> true
                else -> fail("Expected NULL or MISSING", peek())
            }
            return GmqlExpression.IsTest(left, missing, negated, GmqlSourceRange(left.range.start, previous().end))
        }
        val op = when {
            peek().text in listOf("=", "!=", "<", "<=", ">", ">=") -> take().text
            consumeKeyword("IN") -> "IN"
            consumeKeyword("CONTAINS") -> "CONTAINS"
            consumeKeyword("STARTS") -> {
                expectKeyword("WITH"); "STARTS WITH"
            }
            consumeKeyword("ENDS") -> {
                expectKeyword("WITH"); "ENDS WITH"
            }
            else -> null
        }
        if (op != null) {
            val right = parseAdditive()
            left = GmqlExpression.Binary(left, op, right, GmqlSourceRange(left.range.start, right.range.end))
        }
        return left
    }

    private fun parseAdditive() = leftAssociative(::parseMultiplicative, listOf("+", "-"))
    private fun parseMultiplicative() = leftAssociative(::parseUnary, listOf("*", "/", "%"))
    private fun parseUnary(): GmqlExpression =
        if (peek().text == "+" || peek().text == "-") {
            val operator = take()
            val operand = parseUnary()
            GmqlExpression.Unary(operator.text, operand, GmqlSourceRange(operator.start, operand.range.end))
        } else parsePostfix()

    private fun parsePostfix(): GmqlExpression {
        var expression = parsePrimary()
        while (consume(".")) {
            val property = expectIdentifier()
            expression = GmqlExpression.Property(
                expression, property.text, GmqlSourceRange(expression.range.start, property.end),
            )
        }
        return expression
    }

    private fun parsePrimary(): GmqlExpression {
        val token = take()
        return when (token.kind) {
            TokenKind.STRING -> GmqlExpression.Literal(GmqlValue.StringValue(token.text), token.range)
            TokenKind.INTEGER -> GmqlExpression.Literal(
                GmqlValue.IntegerValue(token.text.toLongOrNull() ?: fail("Integer is out of range", token)), token.range,
            )
            TokenKind.DECIMAL -> GmqlExpression.Literal(
                GmqlValue.DecimalValue(
                    token.text.toDoubleOrNull()?.takeIf(Double::isFinite)
                        ?: fail("Decimal must be finite", token),
                ),
                token.range,
            )
            TokenKind.PARAMETER -> GmqlExpression.Parameter(token.text, token.range)
            TokenKind.IDENT -> when {
                token.text.equals("TRUE", true) ->
                    GmqlExpression.Literal(GmqlValue.BooleanValue(true), token.range)
                token.text.equals("FALSE", true) ->
                    GmqlExpression.Literal(GmqlValue.BooleanValue(false), token.range)
                token.text.equals("NULL", true) -> GmqlExpression.Literal(GmqlValue.NullValue, token.range)
                consume("(") -> {
                    val arguments = buildList {
                        if (!consume(")")) {
                            do add(parseExpression()) while (consume(","))
                            expect(")")
                        }
                    }
                    GmqlExpression.Call(token.text, arguments, GmqlSourceRange(token.start, previous().end))
                }
                token.isReserved() -> fail("Keyword '${token.text}' must be quoted when used as an identifier", token)
                else -> GmqlExpression.Variable(token.text, token.range)
            }
            TokenKind.SYMBOL -> if (token.text == "(") {
                val expression = parseExpression()
                expect(")")
                expression
            } else fail("Expected expression", token)
            TokenKind.EOF -> fail("Expected expression", token)
        }
    }

    private fun leftAssociative(
        next: () -> GmqlExpression,
        operators: List<String>,
    ): GmqlExpression {
        var left = next()
        while (true) {
            val operator = when {
                peek().kind == TokenKind.SYMBOL && peek().text in operators -> take().text
                peek().kind == TokenKind.IDENT && !peek().quoted &&
                    operators.any { peek().text.equals(it, true) } -> take().text.uppercase()
                else -> break
            }
            val right = next()
            left = GmqlExpression.Binary(left, operator, right, GmqlSourceRange(left.range.start, right.range.end))
        }
        return left
    }

    private fun rangeFrom(start: Int) = GmqlSourceRange(start, previous().end)
    private fun peek() = tokens[index]
    private fun previous() = tokens[index - 1]
    private fun take() = tokens[index++]
    private fun consume(text: String): Boolean = if (peek().text == text) { index++; true } else false
    private fun consumeKeyword(text: String): Boolean =
        if (peek().kind == TokenKind.IDENT && !peek().quoted && peek().text.equals(text, true)) { index++; true } else false
    private fun peekKeyword(text: String): Boolean =
        peek().kind == TokenKind.IDENT && !peek().quoted && peek().text.equals(text, true)
    private fun expect(text: String): Token = if (consume(text)) previous() else fail("Expected '$text'", peek())
    private fun expectKeyword(text: String): Token =
        if (consumeKeyword(text)) previous() else fail("Expected $text", peek())
    private fun expectIdentifier(): Token =
        if (peek().kind == TokenKind.IDENT) takeIdentifier() else fail("Expected identifier", peek())
    private fun takeIdentifier(): Token {
        val token = take()
        if (token.isReserved()) fail("Keyword '${token.text}' must be quoted when used as an identifier", token)
        return token
    }
    private fun fail(message: String, token: Token): Nothing = throw GmqlParseException(message, token.range)
}

private val RESERVED_KEYWORDS = setOf(
    "MATCH", "WHERE", "VALID", "ON", "AT", "OVERLAPS", "CONTAINS", "DURING", "ANYTIME", "WITHIN",
    "RETURN", "DISTINCT", "AS", "ORDER", "BY", "ASC", "DESC", "OFFSET", "LIMIT",
    "AND", "OR", "NOT", "IN", "IS", "NULL", "MISSING", "TRUE", "FALSE",
)

private fun Token.isReserved(): Boolean = !quoted && text.uppercase() in RESERVED_KEYWORDS
