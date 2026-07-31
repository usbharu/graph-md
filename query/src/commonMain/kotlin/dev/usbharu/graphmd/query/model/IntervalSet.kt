package dev.usbharu.graphmd.query.model

import dev.usbharu.graphmd.core.model.NormalizedTimeline
import dev.usbharu.graphmd.core.model.ValidTime

data class IntervalBoundary(
    val value: Double,
    val inclusive: Boolean,
) {
    init {
        require(value.isFinite()) { "Interval boundaries must be finite" }
    }
}

/**
 * An interval on one normalized timeline axis.
 *
 * GraphMD source `validTime` bounds are closed. Query ranges can use an
 * exclusive upper bound, so inclusivity is retained instead of approximated.
 */
data class TemporalInterval(
    val timelineId: TimelineId,
    val start: IntervalBoundary? = null,
    val end: IntervalBoundary? = null,
) {
    init {
        require(!isEmpty()) { "An interval must not be empty" }
    }

    fun contains(value: Double): Boolean =
        (start == null || value > start.value || value == start.value && start.inclusive) &&
            (end == null || value < end.value || value == end.value && end.inclusive)

    fun contains(other: TemporalInterval): Boolean {
        if (timelineId != other.timelineId) return false
        val startsBefore = when {
            start == null -> true
            other.start == null -> false
            start.value < other.start.value -> true
            start.value > other.start.value -> false
            else -> start.inclusive || !other.start.inclusive
        }
        val endsAfter = when {
            end == null -> true
            other.end == null -> false
            end.value > other.end.value -> true
            end.value < other.end.value -> false
            else -> end.inclusive || !other.end.inclusive
        }
        return startsBefore && endsAfter
    }

    fun intersect(other: TemporalInterval): TemporalInterval? {
        if (timelineId != other.timelineId) return null
        val nextStart = laterStart(start, other.start)
        val nextEnd = earlierEnd(end, other.end)
        if (isEmpty(nextStart, nextEnd)) return null
        return TemporalInterval(timelineId, nextStart, nextEnd)
    }

    fun subtract(other: TemporalInterval): List<TemporalInterval> {
        val overlap = intersect(other) ?: return listOf(this)
        if (overlap == this) return emptyList()
        return buildList {
            overlap.start?.let { overlapStart ->
                val leftEnd = IntervalBoundary(overlapStart.value, !overlapStart.inclusive)
                if (!isEmpty(start, leftEnd)) add(TemporalInterval(timelineId, start, leftEnd))
            }
            overlap.end?.let { overlapEnd ->
                val rightStart = IntervalBoundary(overlapEnd.value, !overlapEnd.inclusive)
                if (!isEmpty(rightStart, end)) add(TemporalInterval(timelineId, rightStart, end))
            }
        }
    }

    private fun isEmpty(): Boolean = isEmpty(start, end)

    companion object {
        internal fun isEmpty(start: IntervalBoundary?, end: IntervalBoundary?): Boolean = when {
            start == null || end == null -> false
            start.value > end.value -> true
            start.value < end.value -> false
            else -> !start.inclusive || !end.inclusive
        }

        internal fun laterStart(left: IntervalBoundary?, right: IntervalBoundary?): IntervalBoundary? = when {
            left == null -> right
            right == null -> left
            left.value > right.value -> left
            right.value > left.value -> right
            else -> IntervalBoundary(left.value, left.inclusive && right.inclusive)
        }

        internal fun earlierEnd(left: IntervalBoundary?, right: IntervalBoundary?): IntervalBoundary? = when {
            left == null -> right
            right == null -> left
            left.value < right.value -> left
            right.value < left.value -> right
            else -> IntervalBoundary(left.value, left.inclusive && right.inclusive)
        }
    }
}

@ConsistentCopyVisibility
data class IntervalSet private constructor(
    val intervals: List<TemporalInterval>,
    val isUniversal: Boolean,
) {
    val isEmpty: Boolean
        get() = !isUniversal && intervals.isEmpty()

    infix fun intersect(other: IntervalSet): IntervalSet {
        if (isEmpty || other.isEmpty) return empty()
        if (isUniversal) return other
        if (other.isUniversal) return this
        return of(intervals.flatMap { left -> other.intervals.mapNotNull(left::intersect) })
    }

    infix fun union(other: IntervalSet): IntervalSet {
        if (isUniversal || other.isUniversal) return universal()
        return of(intervals + other.intervals)
    }

    fun subtract(other: IntervalSet): IntervalSet {
        if (isEmpty || other.isEmpty) return this
        if (other.isUniversal) return empty()
        require(!isUniversal) {
            "A universal interval set cannot be partially subtracted without a timeline window"
        }
        var remaining = intervals
        other.intervals.forEach { excluded ->
            remaining = remaining.flatMap { it.subtract(excluded) }
        }
        return of(remaining)
    }

    fun contains(other: IntervalSet): Boolean = when {
        isUniversal -> true
        other.isUniversal -> false
        other.isEmpty -> true
        else -> other.intervals.all { candidate ->
            intervals.any { it.contains(candidate) }
        }
    }

    fun contains(timelineId: TimelineId, value: Double): Boolean =
        isUniversal || intervals.any { it.timelineId == timelineId && it.contains(value) }

    companion object {
        fun empty(): IntervalSet = IntervalSet(emptyList(), isUniversal = false)

        fun universal(): IntervalSet = IntervalSet(emptyList(), isUniversal = true)

        fun of(vararg intervals: TemporalInterval): IntervalSet = of(intervals.asList())

        fun of(intervals: Iterable<TemporalInterval>): IntervalSet {
            val sorted = intervals.sortedWith(
                compareBy<TemporalInterval> { it.timelineId.value }
                    .thenComparator { left, right -> compareStarts(left.start, right.start) },
            )
            val merged = mutableListOf<TemporalInterval>()
            sorted.forEach { interval ->
                val previous = merged.lastOrNull()
                if (previous == null || !canMerge(previous, interval)) {
                    merged += interval
                } else {
                    merged[merged.lastIndex] = TemporalInterval(
                        timelineId = previous.timelineId,
                        start = previous.start,
                        end = laterEnd(previous.end, interval.end),
                    )
                }
            }
            return IntervalSet(merged, isUniversal = false)
        }

        private fun compareStarts(left: IntervalBoundary?, right: IntervalBoundary?): Int = when {
            left == null && right == null -> 0
            left == null -> -1
            right == null -> 1
            left.value < right.value -> -1
            left.value > right.value -> 1
            left.inclusive == right.inclusive -> 0
            left.inclusive -> -1
            else -> 1
        }

        private fun canMerge(left: TemporalInterval, right: TemporalInterval): Boolean {
            if (left.timelineId != right.timelineId) return false
            val leftEnd = left.end ?: return true
            val rightStart = right.start ?: return true
            return leftEnd.value > rightStart.value ||
                leftEnd.value == rightStart.value && (leftEnd.inclusive || rightStart.inclusive)
        }

        private fun laterEnd(left: IntervalBoundary?, right: IntervalBoundary?): IntervalBoundary? = when {
            left == null || right == null -> null
            left.value > right.value -> left
            right.value > left.value -> right
            else -> IntervalBoundary(left.value, left.inclusive || right.inclusive)
        }
    }
}

data class QueryTimeline(
    val id: TimelineId,
    val canonicalId: TimelineId,
    val offsetToCanonical: Double,
    val assertionScopeId: TimelineId = id,
)

class TimelineCatalog private constructor(
    val timelines: List<QueryTimeline>,
) {
    private val byId = timelines.associateBy { it.id }

    fun normalize(
        timelineId: TimelineId,
        start: IntervalBoundary?,
        end: IntervalBoundary?,
    ): TemporalInterval {
        val timeline = requireNotNull(byId[timelineId]) { "Unknown Timeline: ${timelineId.value}" }
        fun IntervalBoundary.shift(): IntervalBoundary = copy(value = value + timeline.offsetToCanonical)
        return TemporalInterval(
            timelineId = timeline.canonicalId,
            start = start?.shift(),
            end = end?.shift(),
        )
    }

    /**
     * Builds a search validity scope. Timeline inheritance shares one scope;
     * canonical conversion is available through [normalize], but a mapping
     * must not make an assertion valid on every Timeline on that axis.
     */
    fun assertedInterval(
        timelineId: TimelineId,
        start: IntervalBoundary?,
        end: IntervalBoundary?,
    ): TemporalInterval {
        return TemporalInterval(assertionScopeId(timelineId), start, end)
    }

    fun assertionScopeId(timelineId: TimelineId): TimelineId =
        requireNotNull(byId[timelineId]) { "Unknown Timeline: ${timelineId.value}" }.assertionScopeId

    fun fromValidTimes(validTimes: List<ValidTime>): IntervalSet {
        if (validTimes.isEmpty()) return IntervalSet.universal()
        return IntervalSet.of(validTimes.mapNotNull { validTime ->
            val timelineId = TimelineId(validTime.timeline)
            if (timelineId !in byId) return@mapNotNull null
            val from = validTime.from?.timecode
            val to = validTime.to?.timecode
            if (from != null && to != null && from > to) return@mapNotNull null
            assertedInterval(
                timelineId = timelineId,
                start = from?.let { IntervalBoundary(it, inclusive = true) },
                end = to?.let { IntervalBoundary(it, inclusive = true) },
            )
        })
    }

    operator fun contains(timelineId: TimelineId): Boolean = timelineId in byId

    companion object {
        fun from(timelines: List<NormalizedTimeline>): TimelineCatalog {
            val ids = timelines.map { it.id }.toSet()
            val inheritanceEdges = ids.associateWith { linkedSetOf<String>() }
            timelines.forEach { timeline ->
                timeline.ancestorIds.filter { it in ids }.forEach { ancestor ->
                    inheritanceEdges.getValue(timeline.id).add(ancestor)
                    inheritanceEdges.getValue(ancestor).add(timeline.id)
                }
            }

            fun assertionScope(id: String): String {
                val component = linkedSetOf(id)
                val queue = ArrayDeque<String>()
                queue.addLast(id)
                while (queue.isNotEmpty()) {
                    inheritanceEdges.getValue(queue.removeFirst()).forEach { related ->
                        if (component.add(related)) queue.addLast(related)
                    }
                }
                return component.minOrNull() ?: id
            }

            return TimelineCatalog(timelines.map { timeline ->
                val component = (timeline.mappedOffsets.keys + timeline.id).filter { it in ids }
                val canonical = component.minOrNull() ?: timeline.id
                QueryTimeline(
                    id = TimelineId(timeline.id),
                    canonicalId = TimelineId(canonical),
                    offsetToCanonical = if (canonical == timeline.id) {
                        0.0
                    } else {
                        timeline.mappedOffsets.getValue(canonical)
                    },
                    assertionScopeId = TimelineId(assertionScope(timeline.id)),
                )
            })
        }

        fun fromQueryTimelines(timelines: List<QueryTimeline>): TimelineCatalog =
            TimelineCatalog(timelines)
    }
}
