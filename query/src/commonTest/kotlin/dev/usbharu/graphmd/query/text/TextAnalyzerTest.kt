package dev.usbharu.graphmd.query.text

import dev.usbharu.graphmd.query.model.TextPredicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextAnalyzerTest {
    @Test
    fun `Japanese bigrams match and score`() {
        val text = "Aliceは勇者として活動していた。"
        val predicate = TextPredicate("勇者")

        assertTrue(TextAnalyzer.matches(text, predicate))
        assertEquals(
            1.0,
            TextAnalyzer.scanScore(text, predicate),
            "document=${TextAnalyzer.analyze(text)} query=${TextAnalyzer.analyze(predicate.text)}",
        )
    }

    @Test
    fun `identifiers are searchable as complete and split terms`() {
        val terms = TextAnalyzer.analyze("GraphSearchEngine user_id").terms

        assertTrue("graphsearchengine" in terms)
        assertTrue("graph" in terms)
        assertTrue("search" in terms)
        assertTrue("engine" in terms)
        assertTrue("user" in terms)
        assertTrue("id" in terms)
    }
}
