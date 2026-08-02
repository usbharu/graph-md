package dev.usbharu.graphmd.query.model

import dev.usbharu.graphmd.core.TemporalEngine
import dev.usbharu.graphmd.core.model.*

data class IntervalBoundary(
    val exactValue: ExactRational,
    val inclusive: Boolean,
) {
    constructor(value: Double, inclusive: Boolean) : this(ExactRational.fromDouble(value), inclusive)
    constructor(value: Long, inclusive: Boolean) : this(ExactRational.of(value), inclusive)

    @Deprecated("Use exactValue")
    val value: Double get() = exactValue.toDouble()
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

    fun contains(value: ExactRational): Boolean =
        (start == null || value > start.exactValue || value == start.exactValue && start.inclusive) &&
            (end == null || value < end.exactValue || value == end.exactValue && end.inclusive)

    fun contains(value: Double): Boolean = contains(ExactRational.fromDouble(value))

    fun contains(other: TemporalInterval): Boolean {
        if (timelineId != other.timelineId) return false
        val startsBefore = when {
            start == null -> true
            other.start == null -> false
            start.exactValue < other.start.exactValue -> true
            start.exactValue > other.start.exactValue -> false
            else -> start.inclusive || !other.start.inclusive
        }
        val endsAfter = when {
            end == null -> true
            other.end == null -> false
            end.exactValue > other.end.exactValue -> true
            end.exactValue < other.end.exactValue -> false
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
                val leftEnd = IntervalBoundary(overlapStart.exactValue, !overlapStart.inclusive)
                if (!isEmpty(start, leftEnd)) add(TemporalInterval(timelineId, start, leftEnd))
            }
            overlap.end?.let { overlapEnd ->
                val rightStart = IntervalBoundary(overlapEnd.exactValue, !overlapEnd.inclusive)
                if (!isEmpty(rightStart, end)) add(TemporalInterval(timelineId, rightStart, end))
            }
        }
    }

    private fun isEmpty(): Boolean = isEmpty(start, end)

    companion object {
        internal fun isEmpty(start: IntervalBoundary?, end: IntervalBoundary?): Boolean = when {
            start == null || end == null -> false
            start.exactValue > end.exactValue -> true
            start.exactValue < end.exactValue -> false
            else -> !start.inclusive || !end.inclusive
        }

        internal fun laterStart(left: IntervalBoundary?, right: IntervalBoundary?): IntervalBoundary? = when {
            left == null -> right
            right == null -> left
            left.exactValue > right.exactValue -> left
            right.exactValue > left.exactValue -> right
            else -> IntervalBoundary(left.exactValue, left.inclusive && right.inclusive)
        }

        internal fun earlierEnd(left: IntervalBoundary?, right: IntervalBoundary?): IntervalBoundary? = when {
            left == null -> right
            right == null -> left
            left.exactValue < right.exactValue -> left
            right.exactValue < left.exactValue -> right
            else -> IntervalBoundary(left.exactValue, left.inclusive && right.inclusive)
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

    fun contains(timelineId: TimelineId, value: ExactRational): Boolean =
        isUniversal || intervals.any { it.timelineId == timelineId && it.contains(value) }

    fun contains(timelineId: TimelineId, value: Double): Boolean = contains(timelineId, ExactRational.fromDouble(value))

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
            left.exactValue < right.exactValue -> -1
            left.exactValue > right.exactValue -> 1
            left.inclusive == right.inclusive -> 0
            left.inclusive -> -1
            else -> 1
        }

        private fun canMerge(left: TemporalInterval, right: TemporalInterval): Boolean {
            if (left.timelineId != right.timelineId) return false
            val leftEnd = left.end ?: return true
            val rightStart = right.start ?: return true
            return leftEnd.exactValue > rightStart.exactValue ||
                leftEnd.exactValue == rightStart.exactValue && (leftEnd.inclusive || rightStart.inclusive)
        }

        private fun laterEnd(left: IntervalBoundary?, right: IntervalBoundary?): IntervalBoundary? = when {
            left == null || right == null -> null
            left.exactValue > right.exactValue -> left
            right.exactValue > left.exactValue -> right
            else -> IntervalBoundary(left.exactValue, left.inclusive || right.inclusive)
        }
    }
}

data class QueryTimeline(
    val id: TimelineId,
    val canonicalId: TimelineId,
    val exactOffsetToCanonical: ExactRational,
    val assertionScopeId: TimelineId = id,
    val domainId: String = "domain:${id.value}",
    val axisId: TimelineId = canonicalId,
    val axisUnit: TemporalAxisUnit = TemporalAxisUnit.Tick,
    val coordinateSystem: TemporalCoordinateSystem = TemporalCoordinateSystem(
        id = id.value,
        axisId = axisId.value,
        domainId = domainId,
        coordinate = TemporalCoordinateSpec.Number,
    ),
    val mappings: List<TemporalMappingInstance> = emptyList(),
) {
    constructor(
        id: TimelineId,
        canonicalId: TimelineId,
        offsetToCanonical: Double,
        assertionScopeId: TimelineId = id,
    ) : this(id, canonicalId, ExactRational.fromDouble(offsetToCanonical), assertionScopeId)

    @Deprecated("Use exactOffsetToCanonical")
    val offsetToCanonical: Double get() = exactOffsetToCanonical.toDouble()
}

class TimelineCatalog private constructor(
    val timelines: List<QueryTimeline>,
) {
    private val byId = timelines.associateBy { it.id }
    private val temporalModel = TemporalModel(
        domains = timelines.map { TemporalDomain(it.domainId) }.distinctBy { it.id },
        axes = timelines.map { TemporalAxis(it.axisId.value, it.domainId, it.axisUnit) }.distinctBy { it.id },
        coordinateSystems = timelines.map { it.coordinateSystem },
        mappings = timelines.flatMap { it.mappings }.distinctBy { it.id },
    )
    private val engine = TemporalEngine(temporalModel)

    fun normalize(
        timelineId: TimelineId,
        start: IntervalBoundary?,
        end: IntervalBoundary?,
    ): TemporalInterval {
        val timeline = requireNotNull(byId[timelineId]) { "Unknown Timeline: ${timelineId.value}" }
        fun IntervalBoundary.shift(): IntervalBoundary {
            val normalized = if (timeline.canonicalId != timeline.axisId) {
                exactValue + timeline.exactOffsetToCanonical
            } else {
                runCatching {
                    engine.normalizeToAxis(timelineId.value, TemporalCoordinate.Rational(exactValue))
                }.getOrNull() ?: exactValue + timeline.exactOffsetToCanonical
            }
            return copy(exactValue = normalized)
        }
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
        return normalize(timelineId, start, end)
    }

    fun searchIntervals(
        timelineId: TimelineId,
        start: Pair<TemporalCoordinate, Boolean>?,
        end: Pair<TemporalCoordinate, Boolean>?,
    ): IntervalSet {
        val source = requireNotNull(byId[timelineId]) { "Unknown Timeline: ${timelineId.value}" }
        if (start == null && end == null) {
            return IntervalSet.of(TemporalInterval(source.assertionScopeId))
        }
        val targetByAxis = timelines.distinctBy { it.axisId }
        val intervals = targetByAxis.mapNotNull { target ->
            fun convert(boundary: Pair<TemporalCoordinate, Boolean>?): IntervalBoundary? {
                boundary ?: return null
                val converted = if (source.axisId == target.axisId) {
                    TemporalValue(timelineId.value, boundary.first)
                } else {
                    engine.convertForSearch(
                        TemporalValue(timelineId.value, boundary.first),
                        target.id.value,
                    ) ?: return null
                }
                val exact = engine.normalizeToAxis(converted.timeline, converted.coordinate) ?: return null
                return IntervalBoundary(exact, boundary.second)
            }

            val convertedStart = convert(start)
            val convertedEnd = convert(end)
            if (start != null && convertedStart == null || end != null && convertedEnd == null) return@mapNotNull null
            if (TemporalInterval.isEmpty(convertedStart, convertedEnd)) return@mapNotNull null
            TemporalInterval(target.axisId, convertedStart, convertedEnd)
        }
        return IntervalSet.of(intervals)
    }

    fun assertionScopeId(timelineId: TimelineId): TimelineId =
        requireNotNull(byId[timelineId]) { "Unknown Timeline: ${timelineId.value}" }.assertionScopeId

    fun parseCoordinate(timelineId: TimelineId, raw: String): TemporalCoordinate {
        require(timelineId in byId) { "Unknown Timeline: ${timelineId.value}" }
        return engine.parse(timelineId.value, raw).coordinate
    }

    internal fun normalizeCoordinate(
        timelineId: TimelineId,
        coordinate: TemporalCoordinate,
    ): ExactRational? {
        if (timelineId !in byId) return null
        return runCatching { engine.normalizeToAxis(timelineId.value, coordinate) }.getOrNull()
    }

    fun fromValidTimes(validTimes: List<ValidTime>): IntervalSet {
        if (validTimes.isEmpty()) return IntervalSet.universal()
        return IntervalSet.of(validTimes.mapNotNull { validTime ->
            val timelineId = TimelineId(validTime.timeline)
            if (timelineId !in byId) return@mapNotNull null
            val from = validTime.from?.let { point ->
                runCatching { engine.normalizeToAxis(validTime.timeline, point.coordinate) }.getOrNull()
            }
            val to = validTime.to?.let { point ->
                runCatching { engine.normalizeToAxis(validTime.timeline, point.coordinate) }.getOrNull()
            }
            if (from != null && to != null && from > to) return@mapNotNull null
            TemporalInterval(
                timelineId = requireNotNull(byId[timelineId]).assertionScopeId,
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
                val legacyCanonical = component.minOrNull() ?: timeline.id
                val usesLegacyMapping = timeline.temporalMappings.isEmpty() && timeline.mappedOffsets.isNotEmpty()
                val canonical = if (usesLegacyMapping) legacyCanonical else timeline.axisId
                QueryTimeline(
                    id = TimelineId(timeline.id),
                    canonicalId = TimelineId(canonical),
                    exactOffsetToCanonical = if (!usesLegacyMapping || canonical == timeline.id) {
                        ExactRational.ZERO
                    } else {
                        ExactRational.fromDouble(timeline.mappedOffsets.getValue(canonical))
                    },
                    assertionScopeId = if (usesLegacyMapping) {
                        TimelineId(assertionScope(timeline.id))
                    } else {
                        TimelineId(timeline.axisId)
                    },
                    domainId = if (usesLegacyMapping) "domain:$canonical" else timeline.domainId,
                    axisId = TimelineId(timeline.axisId),
                    axisUnit = timeline.axisUnit,
                    coordinateSystem = timeline.coordinateSystem,
                    mappings = timeline.temporalMappings,
                )
            })
        }

        fun fromQueryTimelines(timelines: List<QueryTimeline>): TimelineCatalog =
            TimelineCatalog(timelines)
    }
}
