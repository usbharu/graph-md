package dev.usbharu.graphmd.query.index

import dev.usbharu.graphmd.query.indexedFixtureGraph
import dev.usbharu.graphmd.query.ir.AssertionOwner
import dev.usbharu.graphmd.query.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchIndexBuilderTest {
    private val graph = indexedFixtureGraph()
    private val index = SearchIndexBuilder().build(graph)

    @Test
    fun `builds node property and relation posting lists`() {
        assertEquals(
            listOf(NodeId("alice"), NodeId("bob")),
            index.nodeIdsByType.getValue(NodeTypeId("Person")),
        )
        assertEquals(
            listOf(AssertionId(0)),
            index.propertyExactPostings.getValue(
                PropertyExactKey(PropertyId("name"), "s:Alice"),
            ),
        )
        assertEquals(
            listOf(AssertionId(1), AssertionId(2)),
            index.propertyValuePostings.getValue(PropertyId("age")).map { it.assertionId },
        )
        assertEquals(
            listOf(AssertionId(1), AssertionId(2)),
            index.propertyIdsByOwnerAndPath.getValue(
                PropertyOwnerPathKey(AssertionOwner.Node(NodeId("bob")), PropertyPath("age")),
            ),
        )
        assertEquals(listOf(AssertionId(3)), index.relationIdsBySource.getValue(NodeId("alice")))
        assertEquals(listOf(AssertionId(3)), index.relationIdsByTarget.getValue(NodeId("bob")))
    }

    @Test
    fun `interval index finds only assertions overlapping the query window`() {
        val window = IntervalSet.of(
            TemporalInterval(
                TimelineId("TimelineA"),
                IntervalBoundary(110.0, true),
                IntervalBoundary(130.0, false),
            ),
        )

        val candidates = index.intervalIndex.candidates(window)

        assertTrue(AssertionId(2) in candidates)
        assertTrue(AssertionId(3) in candidates)
        assertTrue(AssertionId(1) !in candidates)
    }

    @Test
    fun `full text index contains Japanese unigram and bigram positions`() {
        val hero = index.fullTextIndex.postingsByTerm.getValue("勇者")
        val firstCharacter = index.fullTextIndex.postingsByTerm.getValue("勇")

        assertEquals(listOf(AssertionId(4)), hero.map { it.assertionId })
        assertEquals(listOf(AssertionId(4)), firstCharacter.map { it.assertionId })
        assertTrue(hero.single().positions.isNotEmpty())
        assertTrue(index.fullTextIndex.averageDocumentLength > 0.0)
    }
}
