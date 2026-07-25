package dev.usbharu.graphmd.query.text

import dev.usbharu.graphmd.query.ir.TextAssertion
import dev.usbharu.graphmd.query.model.AssertionId
import dev.usbharu.graphmd.query.model.TextPredicate
import kotlin.math.ln

class Bm25Scorer private constructor(
    private val termFrequencies: Map<AssertionId, Map<String, Int>>,
    private val documentFrequencies: Map<String, Int>,
    private val documentLengths: Map<AssertionId, Int>,
    private val averageDocumentLength: Double,
    private val documentCount: Int,
) {
    fun score(assertionId: AssertionId, predicate: TextPredicate): Double {
        // Matching may be case-sensitive, but corpus statistics use the
        // canonical case-insensitive analyzer built for the physical index.
        val queryTerms = TextAnalyzer.analyze(predicate.text).terms
        if (queryTerms.isEmpty()) return 1.0
        val frequencies = termFrequencies[assertionId].orEmpty()
        val length = documentLengths[assertionId]?.toDouble() ?: return 0.0
        val safeAverage = averageDocumentLength.takeIf { it > 0.0 } ?: 1.0
        val k1 = 1.2
        val b = 0.75
        return queryTerms.sumOf { term ->
            val frequency = frequencies[term]?.toDouble() ?: return@sumOf 0.0
            val documentFrequency = documentFrequencies[term]?.toDouble() ?: return@sumOf 0.0
            val inverseDocumentFrequency = ln(
                1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5),
            )
            inverseDocumentFrequency *
                (frequency * (k1 + 1.0)) /
                (frequency + k1 * (1.0 - b + b * length / safeAverage))
        }
    }

    companion object {
        fun from(assertions: List<TextAssertion>): Bm25Scorer {
            val frequencies = assertions.associate { assertion ->
                assertion.id to TextAnalyzer.analyze(assertion.text).tokens
                    .groupingBy { it.term }
                    .eachCount()
            }
            val documentFrequencies = linkedMapOf<String, Int>()
            frequencies.values.forEach { terms ->
                terms.keys.forEach { term -> documentFrequencies[term] = documentFrequencies.getOrElse(term) { 0 } + 1 }
            }
            val lengths = frequencies.mapValues { it.value.values.sum() }
            return Bm25Scorer(
                termFrequencies = frequencies,
                documentFrequencies = documentFrequencies,
                documentLengths = lengths,
                averageDocumentLength = lengths.values.average().takeUnless(Double::isNaN) ?: 0.0,
                documentCount = assertions.size,
            )
        }
    }
}
