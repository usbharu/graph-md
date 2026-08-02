package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalModelTest {
    @Test
    fun `bare Timeline creates an independent exact number axis`() {
        val result = compile(
            timeline("Story"),
            timeline("Other"),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString { it.message })
        val story = result.timelines.first { it.id == "Story" }
        val other = result.timelines.first { it.id == "Other" }
        assertEquals("Story", story.axisId)
        assertEquals("domain:Story", story.domainId)
        assertTrue(story.axisId != other.axisId)
        assertEquals(ExactRational.of(1, 10), ExactRational.parse("0.1"))
    }

    @Test
    fun `sameAxisAs offset is a coordinate transform`() {
        val result = compile(
            timeline("Story"),
            timeline(
                "ProjectEra",
                """
                sameAxisAs: Story
                offset: 1000
                """.trimIndent(),
            ),
        )
        val engine = TemporalEngine(result.temporalModel)

        val converted = engine.convert(
            TemporalValue("Story", TemporalCoordinate.Rational(ExactRational.of(25))),
            "ProjectEra",
        )

        assertEquals(
            TemporalConversionResult.Exact(
                TemporalValue("ProjectEra", TemporalCoordinate.Rational(ExactRational.of(1025))),
            ),
            converted,
        )
    }

    @Test
    fun `lineage alone stays unrelated while mapsTo converts`() {
        val withoutMapping = compile(
            timeline("Reality"),
            timeline("IfWorld", "derivedFrom:\n  timeline: Reality\n  kind: fork"),
        )
        assertEquals(
            TemporalComparisonResult.Unrelated,
            TemporalEngine(withoutMapping.temporalModel).compare(
                TemporalValue("Reality", TemporalCoordinate.Rational(ExactRational.ZERO)),
                TemporalValue("IfWorld", TemporalCoordinate.Rational(ExactRational.ZERO)),
            ),
        )

        val withMapping = compile(
            timeline("Reality"),
            timeline(
                "IfWorld",
                """
                derivedFrom:
                  timeline: Reality
                  kind: fork
                mapsTo: Reality
                """.trimIndent(),
            ),
        )
        assertIs<TemporalComparisonResult.Ordered>(
            TemporalEngine(withMapping.temporalModel).compare(
                TemporalValue("Reality", TemporalCoordinate.Rational(ExactRational.ZERO)),
                TemporalValue("IfWorld", TemporalCoordinate.Rational(ExactRational.ZERO)),
            ),
        )
    }

    @Test
    fun `same axis aliases preserve axis lineage regardless of document order`() {
        val result = compile(
            timeline("Alias", "sameAxisAs: Fork"),
            timeline("Fork", "derivedFrom:\n  timeline: Reality\n  kind: fork"),
            timeline("Reality"),
        )

        val fork = result.timelines.single { it.id == "Fork" }
        val alias = result.timelines.single { it.id == "Alias" }
        val axis = result.temporalModel.axes.single { it.id == fork.axisId }

        assertEquals(AxisLineageKind.Fork, fork.lineage?.kind)
        assertEquals(fork.lineage, alias.lineage)
        assertEquals(fork.lineage, axis.lineage)
    }

    @Test
    fun `calendar presets normalize Gregorian and Julian labels`() {
        val result = compile(
            timeline("Gregorian", "coordinate: gregorian"),
            timeline("Julian", "sameAxisAs: Gregorian\ncoordinate: julian"),
        )
        val engine = TemporalEngine(result.temporalModel)

        assertEquals(
            TemporalComparisonResult.Ordered(TemporalOrder.Equal),
            engine.compare(engine.parse("Gregorian", "2000-01-14"), engine.parse("Julian", "2000-01-01")),
        )
    }

    @Test
    fun `removed Timeline authoring fields report replacements`() {
        val result = compile(
            timeline("Old", "extends: [Base]\ntimecode:\n  type: number\nmappings: []"),
        )

        assertTrue(result.diagnostics.any { it.message == "Timeline.extends was removed; use sameAxisAs or derivedFrom" })
        assertTrue(result.diagnostics.any { it.message == "Timeline.timecode was removed; use coordinate" })
        assertTrue(result.diagnostics.any { it.message == "Timeline.mappings was removed; use mapsTo" })
    }

    @Test
    fun `era and drop frame coordinates normalize at their boundaries`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "JapaneseEra",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: era
                  periods:
                    - name: Reiwa
                      aliases: [令和, R]
                      since: 2019-05-01
                      firstYear: 1
                """.trimIndent(),
            ),
            timeline(
                "Video",
                """
                coordinate:
                  kind: timecode
                  actualFps: 30000/1001
                  nominalFps: 30
                  dropFrame: true
                  wrapHours: 24
                """.trimIndent(),
            ),
        )
        val engine = TemporalEngine(result.temporalModel)

        assertEquals(
            TemporalComparisonResult.Ordered(TemporalOrder.Equal),
            engine.compare(engine.parse("CommonEra", "2019-05-01"), engine.parse("JapaneseEra", "令和 1-05-01")),
        )
        assertEquals(
            ExactRational.of(1800),
            engine.normalizeToAxis("Video", engine.parse("Video", "00:01:00;02").coordinate),
        )
    }

    @Test
    fun `wrapped timecode does not silently discard elapsed days`() {
        val result = compile(
            timeline("Frames", "coordinate: frame"),
            timeline(
                "Video",
                """
                sameAxisAs: Frames
                coordinate:
                  kind: timecode
                  actualFps: 30
                  nominalFps: 30
                  dropFrame: false
                  wrapHours: 24
                """.trimIndent(),
            ),
        )
        val engine = TemporalEngine(result.temporalModel)

        val representable = assertIs<TemporalConversionResult.Exact>(
            engine.convert(TemporalValue("Frames", TemporalCoordinate.FrameIndex(23 * 60 * 60 * 30L)), "Video"),
        )
        assertEquals(
            TemporalCoordinate.Timecode(23, 0, 0, 0),
            representable.value.coordinate,
        )
        assertIs<TemporalConversionResult.Unmappable>(
            engine.convert(TemporalValue("Frames", TemporalCoordinate.FrameIndex(25 * 60 * 60 * 30L)), "Video"),
        )
    }

    @Test
    fun `segments infer reverse order and pairs return alternatives`() {
        val result = compile(
            timeline("Source"),
            timeline(
                "Edit",
                """
                mapsTo:
                  - timeline: Source
                    kind: correspondence
                    segments:
                      - source: { from: 0, to: 100 }
                        target: { from: 600, to: 500 }
                  - timeline: Source
                    kind: correspondence
                    pairs:
                      - from: 200
                        to: [700, 900]
                """.trimIndent(),
            ),
        )
        val mappings = result.timelines.single { it.id == "Edit" }.temporalMappings
        val engine = TemporalEngine(result.temporalModel)

        assertEquals(TemporalOrderBehavior.StrictlyDecreasing, mappings.first().traits.orderBehavior)
        assertEquals(
            TemporalConversionResult.Exact(
                TemporalValue("Source", TemporalCoordinate.Rational(ExactRational.of(550))),
            ),
            engine.convert(TemporalValue("Edit", TemporalCoordinate.Rational(ExactRational.of(50))), "Source"),
        )
        assertIs<TemporalConversionResult.Alternatives>(
            engine.convert(TemporalValue("Edit", TemporalCoordinate.Rational(ExactRational.of(200))), "Source"),
        )
    }

    @Test
    fun `segment order inference includes ordering between segments`() {
        val result = compile(
            timeline(
                "Source",
                """
                mapsTo:
                  timeline: Target
                  segments:
                    - source: { from: 0, to: 10 }
                      target: { from: 100, to: 110 }
                    - source: { from: 20, to: 30 }
                      target: { from: 50, to: 60 }
                """.trimIndent(),
            ),
            timeline("Target"),
        )
        val mapping = result.timelines.single { it.id == "Source" }.temporalMappings.single()
        val engine = TemporalEngine(result.temporalModel)

        assertEquals(TemporalOrderBehavior.NonMonotonic, mapping.traits.orderBehavior)
        assertNull(
            engine.convertForSearch(
                TemporalValue("Source", TemporalCoordinate.Rational(ExactRational.of(5))),
                "Target",
            ),
        )
    }

    @Test
    fun `sameAxis cycles and strengthening mapping traits are diagnosed`() {
        val result = compile(
            timeline("A", "sameAxisAs: B"),
            timeline("B", "sameAxisAs: A"),
            timeline("C"),
            timeline(
                "D",
                """
                mapsTo:
                  timeline: C
                  pairs:
                    - from: 0
                      to: [1, 2]
                  traits:
                    invertibility: invertible
                """.trimIndent(),
            ),
        )

        assertTrue(result.diagnostics.any { "Cyclic sameAxisAs" in it.message })
        assertTrue(result.diagnostics.any { "cannot strengthen" in it.message })
    }

    @Test
    fun `mapping validation normalizes coordinates and rejects errors on exact precision`() {
        val result = compile(
            timeline(
                "CalendarSource",
                """
                coordinate: gregorian
                mapsTo:
                  - timeline: Target
                    precision:
                      kind: exact
                      error: 1
                  - timeline: Target
                    segments:
                      - source: { from: 2026-12-31, to: 2026-01-01 }
                        target: { from: 0, to: 1 }
                  - timeline: Target
                    segments:
                      - source: { from: 2026-01-01, to: 2026-06-30 }
                        target: { from: 0, to: 1 }
                      - source: { from: 2026-06-01, to: 2026-12-31 }
                        target: { from: 2, to: 3 }
                """.trimIndent(),
            ),
            timeline("Target"),
        )

        assertTrue(result.diagnostics.any { it.message == "exact mapsTo MUST NOT define an error" })
        assertTrue(result.diagnostics.any { it.message == "mapsTo segment source.from MUST NOT be after source.to" })
        assertTrue(result.diagnostics.any { it.message == "mapsTo segments overlap on the source axis" })
    }

    @Test
    fun `compare follows a one way mapping and reports overlap for uncertain ranges`() {
        val oneWay = compile(
            timeline(
                "Source",
                """
                mapsTo:
                  timeline: Target
                  scale: 0
                """.trimIndent(),
            ),
            timeline("Target"),
        )
        assertEquals(
            TemporalComparisonResult.Ordered(TemporalOrder.Equal),
            TemporalEngine(oneWay.temporalModel).compare(
                TemporalValue("Source", TemporalCoordinate.Rational(ExactRational.of(5))),
                TemporalValue("Target", TemporalCoordinate.Rational(ExactRational.ZERO)),
            ),
        )

        val uncertain = compile(
            timeline(
                "Source",
                """
                mapsTo:
                  timeline: Target
                  precision:
                    kind: uncertain
                    error: 2
                """.trimIndent(),
            ),
            timeline("Target"),
        )
        assertEquals(
            TemporalComparisonResult.Overlapping,
            TemporalEngine(uncertain.temporalModel).compare(
                TemporalValue("Target", TemporalCoordinate.Rational(ExactRational.of(10))),
                TemporalValue("Source", TemporalCoordinate.Rational(ExactRational.of(10))),
            ),
        )
    }

    private fun compile(vararg sources: SourceDocument): GraphCompilationResult = GraphCompiler().compileSources(sources.toList())

    private fun timeline(id: String, fields: String = ""): SourceDocument = SourceDocument(
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
