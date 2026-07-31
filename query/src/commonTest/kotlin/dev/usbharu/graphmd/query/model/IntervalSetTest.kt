package dev.usbharu.graphmd.query.model

import dev.usbharu.graphmd.core.model.NormalizedTimeline
import dev.usbharu.graphmd.core.model.SourceInfo
import dev.usbharu.graphmd.core.model.ValidTime
import dev.usbharu.graphmd.core.model.TimePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntervalSetTest {
    private val timeline = TimelineId("A")

    @Test
    fun `intersection preserves open and closed boundaries`() {
        val closed = interval(100.0, 140.0)
        val halfOpen = TemporalInterval(
            timeline,
            IntervalBoundary(140.0, inclusive = true),
            IntervalBoundary(200.0, inclusive = false),
        )

        val intersection = IntervalSet.of(closed) intersect IntervalSet.of(halfOpen)

        assertEquals(1, intersection.intervals.size)
        assertTrue(intersection.contains(timeline, 140.0))
        assertFalse(intersection.contains(timeline, 139.0))
    }

    @Test
    fun `subtract splits intervals without losing boundary semantics`() {
        val source = IntervalSet.of(interval(0.0, 10.0))
        val removed = IntervalSet.of(interval(3.0, 7.0))

        val result = source.subtract(removed)

        assertEquals(2, result.intervals.size)
        assertTrue(result.contains(timeline, 2.0))
        assertFalse(result.contains(timeline, 3.0))
        assertFalse(result.contains(timeline, 7.0))
        assertTrue(result.contains(timeline, 8.0))
    }

    @Test
    fun `timeline catalog converts coordinates without losing asserted timeline identity`() {
        val catalog = TimelineCatalog.from(
            listOf(
                NormalizedTimeline(
                    id = "A",
                    timecode = null,
                    mappings = emptyList(),
                    props = emptyMap(),
                    ancestorIds = emptySet(),
                    mappedOffsets = mapOf("B" to 10.0),
                    source = SourceInfo("a.md"),
                ),
                NormalizedTimeline(
                    id = "B",
                    timecode = null,
                    mappings = emptyList(),
                    props = emptyMap(),
                    ancestorIds = emptySet(),
                    mappedOffsets = mapOf("A" to -10.0),
                    source = SourceInfo("b.md"),
                ),
            ),
        )

        val canonicalA = catalog.normalize(
            TimelineId("A"), IntervalBoundary(20.0, true), IntervalBoundary(30.0, true),
        )
        val canonicalB = catalog.normalize(
            TimelineId("B"), IntervalBoundary(30.0, true), IntervalBoundary(40.0, true),
        )
        val onA = catalog.fromValidTimes(listOf(ValidTime("A", TimePoint(20.0), TimePoint(30.0))))
        val onB = catalog.fromValidTimes(listOf(ValidTime("B", TimePoint(30.0), TimePoint(40.0))))

        assertEquals(canonicalA, canonicalB)
        assertEquals(TimelineId("A"), onA.intervals.single().timelineId)
        assertEquals(TimelineId("B"), onB.intervals.single().timelineId)
        assertTrue((onA intersect onB).isEmpty)
    }

    @Test
    fun `timeline inheritance shares assertion scope`() {
        val catalog = TimelineCatalog.from(
            listOf(
                NormalizedTimeline(
                    id = "Root",
                    timecode = null,
                    mappings = emptyList(),
                    props = emptyMap(),
                    ancestorIds = emptySet(),
                    mappedOffsets = mapOf("Child" to 0.0),
                    source = SourceInfo("root.md"),
                ),
                NormalizedTimeline(
                    id = "Child",
                    timecode = null,
                    mappings = emptyList(),
                    props = emptyMap(),
                    ancestorIds = setOf("Root"),
                    mappedOffsets = mapOf("Root" to 0.0),
                    source = SourceInfo("child.md"),
                ),
            ),
        )

        val onRoot = catalog.fromValidTimes(listOf(ValidTime("Root")))
        val onChild = catalog.fromValidTimes(listOf(ValidTime("Child")))

        assertEquals(onRoot, onChild)
        assertEquals(TimelineId("Child"), onRoot.intervals.single().timelineId)
    }

    @Test
    fun `timeline catalog skips unknown source validTime timelines`() {
        val catalog = TimelineCatalog.from(
            listOf(
                NormalizedTimeline(
                    id = "A",
                    timecode = null,
                    mappings = emptyList(),
                    props = emptyMap(),
                    ancestorIds = emptySet(),
                    source = SourceInfo("a.md"),
                ),
            ),
        )

        val result = catalog.fromValidTimes(
            listOf(
                ValidTime("Missing"),
                ValidTime("A", TimePoint(40.0), TimePoint(30.0)),
                ValidTime("A", TimePoint(20.0), TimePoint(30.0)),
            ),
        )

        assertEquals(1, result.intervals.size)
        assertEquals(TimelineId("A"), result.intervals.single().timelineId)
    }

    private fun interval(start: Double, end: Double): TemporalInterval =
        TemporalInterval(
            timeline,
            IntervalBoundary(start, inclusive = true),
            IntervalBoundary(end, inclusive = true),
        )
}
