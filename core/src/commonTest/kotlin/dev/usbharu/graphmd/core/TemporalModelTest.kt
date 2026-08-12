package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemporalModelTest {
    @Test
    fun `exact rational parses scientific notation without losing precision`() {
        assertEquals(ExactRational.of(1, 1_000_000), ExactRational.parse("1e-6"))
        assertEquals(ExactRational.of(1_250), ExactRational.parse("1.25E3"))
        assertEquals(ExactRational.of(-1, 400), ExactRational.parse("-2.5e-3"))
        assertEquals(ExactRational.of(1, 1_000_000), ExactRational.fromDouble(1e-6))
        assertEquals(
            ExactRational.of(1, 2_000_000_000_000_000_000),
            ExactRational.parse("5e-19"),
        )
    }

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
    fun `calendar pattern fields drive parsing formatting and natural granularity`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "PublicationMonth",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  calendar: gregorian
                  fields: [year, month]
                """.trimIndent(),
            ),
            timeline(
                "Birthday",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  calendar: gregorian
                  fields: [month, day]
                  repeatsEvery: year
                  format: "{day:02}/{month:02}"
                """.trimIndent(),
            ),
            timeline(
                "ShortMonth",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [month]
                  repeatsEvery: year
                  format: "{month:1}"
                """.trimIndent(),
            ),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val engine = TemporalEngine(result.temporalModel)

        val month = engine.parse("PublicationMonth", "2026-08")
        assertEquals(
            TemporalSelection.Period(
                TemporalAxisPeriod(
                    checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2026, 8, 1))),
                    checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2026, 9, 1))),
                ),
            ),
            engine.resolveToAxis("PublicationMonth", month.coordinate),
        )
        val birthday = engine.parse("Birthday", "08/02")
        assertEquals("08/02", engine.format("Birthday", birthday.coordinate))
        assertEquals(
            mapOf(CalendarField.Month to 2L, CalendarField.Day to 8L),
            assertIs<TemporalCoordinate.CalendarPattern>(birthday.coordinate).fields,
        )
        val shortMonth = engine.parse("ShortMonth", "12")
        assertEquals("12", engine.format("ShortMonth", shortMonth.coordinate))
        assertEquals(
            shortMonth.coordinate,
            engine.parse("ShortMonth", engine.format("ShortMonth", shortMonth.coordinate)).coordinate,
        )
    }

    @Test
    fun `calendar pattern recurrence skips invalid leap days instead of rounding`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "LeapDay",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [month, day]
                  repeatsEvery: year
                """.trimIndent(),
            ),
        )
        val engine = TemporalEngine(result.temporalModel)
        val start = checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2023, 1, 1)))
        val end = checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2025, 1, 1)))
        val selection = assertIs<TemporalSelection.Recurrence>(
            engine.resolveToAxis(
                "LeapDay",
                engine.parse("LeapDay", "02-29").coordinate,
                TemporalExpansionWindow(start, end),
            ),
        )

        assertEquals(1, selection.occurrences.size)
        assertEquals(
            checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2024, 2, 29))),
            selection.occurrences.single().start,
        )
    }

    @Test
    fun `calendar pattern resolves ISO week and configurable fiscal quarter boundaries`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "IsoWeek",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [weekYear, week]
                """.trimIndent(),
            ),
            timeline(
                "FiscalQuarter",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [year, quarter]
                  quarterStartMonth: 4
                  quarterYearLabel: end
                """.trimIndent(),
            ),
        )
        val engine = TemporalEngine(result.temporalModel)

        val iso = assertIs<TemporalSelection.Period>(
            engine.resolveToAxis("IsoWeek", engine.parse("IsoWeek", "2020-W53").coordinate),
        ).value
        assertEquals(
            checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2020, 12, 28))),
            iso.start,
        )
        assertEquals(
            checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2021, 1, 4))),
            iso.endExclusive,
        )

        val quarter = assertIs<TemporalSelection.Period>(
            engine.resolveToAxis("FiscalQuarter", engine.parse("FiscalQuarter", "2026-Q1").coordinate),
        ).value
        assertEquals(
            checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2025, 4, 1))),
            quarter.start,
        )
        assertEquals(
            checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(2025, 7, 1))),
            quarter.endExclusive,
        )
    }

    @Test
    fun `calendar pattern canonical syntax is derived from declared fields`() {
        val declarations = listOf(
            Triple("MonthDay", "fields: [month, day]\nrepeatsEvery: year", "08-08"),
            Triple("YearMonth", "fields: [year, month]", "2026-08"),
            Triple("Year", "fields: [year]", "2026"),
            Triple("Month", "fields: [month]\nrepeatsEvery: year", "08"),
            Triple("Quarter", "fields: [year, quarter]", "2026-Q3"),
            Triple("IsoWeek", "fields: [weekYear, week]", "2020-W53"),
        )
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            *declarations.map { (id, declaration, _) ->
                timeline(
                    id,
                    """
                    sameAxisAs: CommonEra
                    coordinate:
                      kind: calendar-pattern
                      ${declaration.replace("\n", "\n                      ")}
                    """.trimIndent(),
                )
            }.toTypedArray(),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val engine = TemporalEngine(result.temporalModel)

        declarations.forEach { (id, _, raw) ->
            assertEquals(raw, engine.format(id, engine.parse(id, raw).coordinate), id)
        }
    }

    @Test
    fun `calendar pattern rejects invalid field combinations and formats`() {
        val result = compile(
            timeline(
                "DayWithoutMonth",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [day]
                  repeatsEvery: year
                """.trimIndent(),
            ),
            timeline(
                "JulianIsoWeek",
                """
                coordinate:
                  kind: calendar-pattern
                  calendar: julian
                  fields: [weekYear, week]
                """.trimIndent(),
            ),
            timeline(
                "BrokenFormat",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [year, month]
                  format: "{year:04}"
                """.trimIndent(),
            ),
            timeline(
                "ZeroWidthFormat",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [month]
                  repeatsEvery: year
                  format: "{month:0}"
                """.trimIndent(),
            ),
            timeline(
                "OverflowWidthFormat",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [month]
                  repeatsEvery: year
                  format: "{month:999999999999999999999}"
                """.trimIndent(),
            ),
            timeline(
                "AmbiguousAdjacentFormat",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [month, day]
                  repeatsEvery: year
                  format: "{month:1}{day:1}"
                """.trimIndent(),
            ),
        )

        assertTrue(result.diagnostics.any { it.message == "calendar-pattern day requires month" })
        assertTrue(result.diagnostics.any { it.message == "ISO week fields require the Gregorian calendar" })
        assertTrue(result.diagnostics.any { it.message == "coordinate.format MUST reference every declared field exactly once" })
        assertEquals(
            2,
            result.diagnostics.count { it.message == "coordinate.format widths MUST be integers between 1 and 64" },
        )
        assertTrue(result.diagnostics.any {
            it.message == "coordinate.format MUST separate adjacent variable-width fields"
        })
    }

    @Test
    fun `calendar pattern expansion rejects windows longer than ten thousand calendar years`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "Birthday",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [month, day]
                  repeatsEvery: year
                """.trimIndent(),
            ),
        )
        val engine = TemporalEngine(result.temporalModel)
        val start = checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(1, 1, 1)))
        val end = checkNotNull(engine.normalizeToAxis("CommonEra", TemporalCoordinate.CalendarDate(10_002, 1, 1)))

        assertNull(
            engine.resolveToAxis(
                "Birthday",
                engine.parse("Birthday", "01-01").coordinate,
                TemporalExpansionWindow(start, end),
            ),
        )
    }

    @Test
    fun `calendar pattern rejects year zero when numbering has no year zero`() {
        val result = compile(
            timeline(
                "PublicationMonth",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [year, month]
                """.trimIndent(),
            ),
            SourceDocument(
                """
                ---
                id: Item
                kind: NodeType
                props:
                  published:
                    type: instant
                    timeline: PublicationMonth
                ---
                """.trimIndent(),
                "/item-type.md",
            ),
            SourceDocument(
                """
                ---
                id: invalid-year
                kind: Node
                type: Item
                props:
                  published: { timeline: PublicationMonth, value: "0000-08" }
                ---
                """.trimIndent(),
                "/invalid-year.md",
            ),
        )

        assertTrue(result.diagnostics.any {
            it.message == "published.value is not valid for PublicationMonth"
        }, result.diagnostics.toString())
    }

    @Test
    fun `calendar pattern rejects invalid structured dates in instant and duration properties`() {
        val result = compile(
            timeline(
                "PatternDate",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [year, month, day]
                """.trimIndent(),
            ),
            SourceDocument(
                """
                ---
                id: Event
                kind: NodeType
                props:
                  occurredAt:
                    type: instant
                    timeline: PatternDate
                  active:
                    type: duration
                    timeline: PatternDate
                ---
                """.trimIndent(),
                "/event-type.md",
            ),
            SourceDocument(
                """
                ---
                id: invalid-date
                kind: Node
                type: Event
                props:
                  occurredAt:
                    timeline: PatternDate
                    value: { year: 2023, month: 2, day: 29 }
                  active:
                    timeline: PatternDate
                    from: { year: 2023, month: 2, day: 29 }
                ---
                """.trimIndent(),
                "/invalid-date.md",
            ),
        )

        assertTrue(result.diagnostics.any {
            it.message == "occurredAt.value is not valid for PatternDate"
        }, result.diagnostics.toString())
        assertTrue(result.diagnostics.any {
            it.message == "active.from is not valid for PatternDate"
        }, result.diagnostics.toString())
    }

    @Test
    fun `calendar pattern values normalize in instant and duration properties`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "Birthday",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [month, day]
                  repeatsEvery: year
                """.trimIndent(),
            ),
            SourceDocument(
                """
                ---
                id: Person
                kind: NodeType
                props:
                  birthday:
                    type: instant
                    timeline: Birthday
                  celebration:
                    type: duration
                    timeline: Birthday
                ---
                """.trimIndent(),
                "/person-type.md",
            ),
            SourceDocument(
                """
                ---
                id: alice
                kind: Node
                type: Person
                props:
                  birthday: { timeline: Birthday, value: "08-08" }
                  celebration:
                    from: { timeline: Birthday, value: "08-08" }
                    to: { timeline: Birthday, value: "08-10" }
                ---
                """.trimIndent(),
                "/alice.md",
            ),
        )
        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val node = result.nodes.single()

        assertIs<TemporalCoordinate.CalendarPattern>(
            assertIs<InstantValue>(node.props.getValue("birthday")).coordinate,
        )
        val duration = assertIs<DurationValue>(node.props.getValue("celebration"))
        assertIs<TemporalCoordinate.CalendarPattern>(duration.from?.coordinate)
        assertIs<TemporalCoordinate.CalendarPattern>(duration.to?.coordinate)
    }

    @Test
    fun `calendar pattern custom format applies to validTime and object duration endpoints`() {
        val result = compile(
            timeline("CommonEra", "coordinate: gregorian"),
            timeline(
                "DisplayBirthday",
                """
                sameAxisAs: CommonEra
                coordinate:
                  kind: calendar-pattern
                  fields: [month, day]
                  repeatsEvery: year
                  format: "{day:02}/{month:02}"
                """.trimIndent(),
            ),
            SourceDocument(
                """
                ---
                id: Event
                kind: NodeType
                props:
                  span:
                    type: duration
                    timeline: DisplayBirthday
                ---
                """.trimIndent(),
                "/event-type.md",
            ),
            SourceDocument(
                """
                ---
                id: event
                kind: Node
                type: Event
                validTime:
                  - timeline: DisplayBirthday
                    from: "08/02"
                props:
                  span:
                    from: { timeline: DisplayBirthday, value: "08/02" }
                    to: { timeline: DisplayBirthday, value: "09/02" }
                ---
                """.trimIndent(),
                "/event.md",
            ),
        )

        assertTrue(result.diagnostics.isEmpty(), result.diagnostics.toString())
        val node = result.nodes.single()
        val duration = assertIs<DurationValue>(node.props.getValue("span"))
        assertEquals(
            mapOf(CalendarField.Month to 2L, CalendarField.Day to 8L),
            assertIs<TemporalCoordinate.CalendarPattern>(duration.from?.coordinate).fields,
        )
        assertEquals(
            mapOf(CalendarField.Month to 2L, CalendarField.Day to 9L),
            assertIs<TemporalCoordinate.CalendarPattern>(duration.to?.coordinate).fields,
        )
    }

    @Test
    fun `non recurring calendar pattern validTime retains reversed range diagnostics`() {
        val result = compile(
            timeline(
                "PublicationMonth",
                """
                coordinate:
                  kind: calendar-pattern
                  fields: [year, month]
                """.trimIndent(),
            ),
            SourceDocument(
                """
                ---
                id: Item
                kind: NodeType
                ---
                """.trimIndent(),
                "/item-type.md",
            ),
            SourceDocument(
                """
                ---
                id: reversed
                kind: Node
                type: Item
                validTime:
                  - timeline: PublicationMonth
                    from: "2026-12"
                    to: "2026-01"
                ---
                """.trimIndent(),
                "/reversed.md",
            ),
        )

        assertTrue(result.diagnostics.any {
            it.message == "validTime.from is after validTime.to on PublicationMonth"
        }, result.diagnostics.toString())
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
