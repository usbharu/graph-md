package dev.usbharu.graphmd.query.model

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporalQueryTest {
    @Test
    fun `same axis representation matches after exact normalization`() {
        val result = compile(
            timeline("Story"),
            timeline("ProjectEra", "sameAxisAs: Story\noffset: 1000"),
        )
        val catalog = TimelineCatalog.from(result.timelines)
        val assertion = catalog.fromValidTimes(
            listOf(ValidTime("Story", TimePoint(10.0), TimePoint(20.0))),
        )
        val query = TemporalWindow.At(TimelineId("ProjectEra"), 1015.0).toIntervalSet(catalog)

        assertFalse((assertion intersect query).isEmpty)
    }

    @Test
    fun `ordinary search excludes approximate mapping and includes exact mapping`() {
        val approximate = compile(
            timeline("Reality"),
            timeline(
                "Recording",
                """
                mapsTo:
                  timeline: Reality
                  kind: alignment
                  precision:
                    kind: approximate
                    error: 1/10
                """.trimIndent(),
            ),
        )
        val approximateCatalog = TimelineCatalog.from(approximate.timelines)
        val realityAssertion = approximateCatalog.fromValidTimes(
            listOf(ValidTime("Reality", TimePoint(0.0), TimePoint(10.0))),
        )
        val approximateQuery = TemporalWindow.At(TimelineId("Recording"), 5.0)
            .toIntervalSet(approximateCatalog)
        assertTrue((realityAssertion intersect approximateQuery).isEmpty)

        val exact = compile(
            timeline("Reality"),
            timeline("Recording", "mapsTo: Reality"),
        )
        val exactCatalog = TimelineCatalog.from(exact.timelines)
        val exactAssertion = exactCatalog.fromValidTimes(
            listOf(ValidTime("Reality", TimePoint(0.0), TimePoint(10.0))),
        )
        val exactQuery = TemporalWindow.At(TimelineId("Recording"), 5.0).toIntervalSet(exactCatalog)
        assertFalse((exactAssertion intersect exactQuery).isEmpty)
    }

    private fun compile(vararg sources: SourceDocument) = GraphCompiler().compileSources(sources.toList())

    private fun timeline(id: String, fields: String = "") = SourceDocument(
        """
        ---
        id: $id
        kind: Timeline
        ${fields.prependIndent("        ").trimStart()}
        ---
        """.trimIndent(),
        "/$id.md",
    )
}
