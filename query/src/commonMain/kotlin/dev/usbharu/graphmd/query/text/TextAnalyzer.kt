package dev.usbharu.graphmd.query.text

import dev.usbharu.graphmd.query.model.TextMatchMode
import dev.usbharu.graphmd.query.model.TextPredicate

data class AnalyzedToken(
    val term: String,
    val position: Int,
)

data class AnalyzedText(
    val normalized: String,
    val tokens: List<AnalyzedToken>,
) {
    val terms: Set<String>
        get() = tokens.mapTo(linkedSetOf()) { it.term }
}

object TextAnalyzer {
    const val VERSION: String = "identifier-ngram-v1"

    fun analyze(text: String, caseSensitive: Boolean = false): AnalyzedText {
        val normalized = if (caseSensitive) text else text.lowercase()
        val tokens = mutableListOf<AnalyzedToken>()
        var position = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            when {
                isCjk(character) -> {
                    val start = index
                    while (index < text.length && isCjk(text[index])) index++
                    val segment = text.substring(start, index).normalizeCase(caseSensitive)
                    segment.forEach { c ->
                        tokens += AnalyzedToken(c.toString(), position++)
                    }
                    if (segment.length > 1) {
                        for (offset in 0 until segment.lastIndex) {
                            tokens += AnalyzedToken(segment.substring(offset, offset + 2), position++)
                        }
                    }
                }
                character.isLetterOrDigit() || character == '_' -> {
                    val start = index
                    while (
                        index < text.length &&
                        !isCjk(text[index]) &&
                        (text[index].isLetterOrDigit() || text[index] == '_')
                    ) {
                        index++
                    }
                    val identifier = text.substring(start, index)
                    splitIdentifier(identifier).forEach { part ->
                        tokens += AnalyzedToken(part.normalizeCase(caseSensitive), position++)
                    }
                }
                else -> index++
            }
        }
        return AnalyzedText(normalized, tokens)
    }

    fun matches(text: String, predicate: TextPredicate): Boolean {
        val document = analyze(text, predicate.caseSensitive)
        val query = analyze(predicate.text, predicate.caseSensitive)
        return when (predicate.mode) {
            TextMatchMode.CONTAINS, TextMatchMode.PHRASE -> predicateText(predicate) in document.normalized
            TextMatchMode.ALL_TERMS -> query.terms.isNotEmpty() && document.terms.containsAll(query.terms)
            TextMatchMode.ANY_TERM -> query.terms.any { it in document.terms }
        }
    }

    fun scanScore(text: String, predicate: TextPredicate): Double {
        if (!matches(text, predicate)) return 0.0
        val document = analyze(text, predicate.caseSensitive)
        val query = analyze(predicate.text, predicate.caseSensitive)
        if (query.tokens.isEmpty()) return 1.0
        val counts = document.tokens.groupingBy { it.term }.eachCount()
        return query.terms.sumOf { counts[it]?.toDouble() ?: 0.0 } / query.terms.size
    }

    private fun predicateText(predicate: TextPredicate): String =
        if (predicate.caseSensitive) predicate.text else predicate.text.lowercase()

    private fun splitIdentifier(identifier: String): List<String> {
        val result = linkedSetOf(identifier)
        identifier.split('_').filter(String::isNotBlank).forEach { underscorePart ->
            var start = 0
            for (index in 1 until underscorePart.length) {
                val previous = underscorePart[index - 1]
                val current = underscorePart[index]
                val next = underscorePart.getOrNull(index + 1)
                val boundary =
                    previous.isLowerCase() && current.isUpperCase() ||
                        previous.isLetter() && current.isDigit() ||
                        previous.isDigit() && current.isLetter() ||
                        previous.isUpperCase() && current.isUpperCase() && next?.isLowerCase() == true
                if (boundary) {
                    result += underscorePart.substring(start, index)
                    start = index
                }
            }
            result += underscorePart.substring(start)
        }
        return result.filter(String::isNotBlank)
    }

    private fun String.normalizeCase(caseSensitive: Boolean): String =
        if (caseSensitive) this else lowercase()

    private fun isCjk(character: Char): Boolean =
        character in '\u3040'..'\u30ff' ||
            character in '\u3400'..'\u4dbf' ||
            character in '\u4e00'..'\u9fff' ||
            character in '\uac00'..'\ud7af'
}
