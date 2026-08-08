package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*

/** Executes coordinate normalization and explicit temporal mappings. */
class TemporalEngine(
    val model: TemporalModel,
) {
    private val coordinateSystems = model.coordinateSystemById
    private val axes = model.axisById
    private val mappingsByAxis = buildMap<String, MutableList<MappingEdge>> {
        model.mappings.forEach { mapping ->
            getOrPut(mapping.sourceAxisId) { mutableListOf() } += MappingEdge(mapping, inverse = false)
            if (mapping.traits.invertibility != TemporalInvertibility.NonInvertible) {
                getOrPut(mapping.targetAxisId) { mutableListOf() } += MappingEdge(mapping, inverse = true)
            }
        }
    }

    fun parse(timeline: String, raw: String): TemporalValue {
        val system = requireNotNull(coordinateSystems[timeline]) { "Unknown Timeline: $timeline" }
        return TemporalValue(timeline, parseCoordinate(system.coordinate, raw))
    }

    fun coerceCoordinate(timeline: String, coordinate: TemporalCoordinate): TemporalCoordinate? {
        val spec = coordinateSystems[timeline]?.coordinate ?: return null
        if (spec !is TemporalCoordinateSpec.CalendarPattern) return coordinate
        return when (coordinate) {
            is TemporalCoordinate.CalendarPattern -> coordinate
            is TemporalCoordinate.Label -> parseCalendarPattern(spec, coordinate.value)
            is TemporalCoordinate.Rational -> if (coordinate.value.denominator == 1L && spec.fields.size == 1) {
                TemporalCoordinate.CalendarPattern(mapOf(spec.fields.single() to coordinate.value.numerator))
                    .takeIf { validCalendarPatternFields(spec, it.fields) }
            } else {
                null
            }
            is TemporalCoordinate.CalendarDate -> if (spec.fields.toSet() == setOf(
                    CalendarField.Year,
                    CalendarField.Month,
                    CalendarField.Day,
                )
            ) {
                TemporalCoordinate.CalendarPattern(
                    mapOf(
                        CalendarField.Year to coordinate.year,
                        CalendarField.Month to coordinate.month.toLong(),
                        CalendarField.Day to coordinate.day.toLong(),
                    ),
                )
            } else {
                null
            }
            else -> null
        }
    }

    fun convert(
        value: TemporalValue,
        targetTimeline: String,
        context: Map<String, NormalizedValue> = emptyMap(),
    ): TemporalConversionResult {
        val sourceSystem = coordinateSystems[value.timeline]
            ?: return TemporalConversionResult.Unmappable("Unknown Timeline: ${value.timeline}")
        val targetSystem = coordinateSystems[targetTimeline]
            ?: return TemporalConversionResult.Unmappable("Unknown Timeline: $targetTimeline")
        val sourceAxisValue = normalizeToAxis(value.timeline, value.coordinate)
            ?: return TemporalConversionResult.Unmappable("Invalid coordinate on ${value.timeline}")

        if (sourceSystem.axisId == targetSystem.axisId) {
            val target = denormalizeFromAxis(targetTimeline, sourceAxisValue)
                ?: return TemporalConversionResult.Unmappable("Coordinate cannot be represented on $targetTimeline")
            return TemporalConversionResult.Exact(TemporalValue(targetTimeline, target))
        }

        val topologicallyRelated = areAxesConnected(sourceSystem.axisId, targetSystem.axisId)
        val outcomes = findBestOutcomes(sourceSystem.axisId, targetSystem.axisId, sourceAxisValue, context)
        if (outcomes.isEmpty()) {
            return if (topologicallyRelated) {
                TemporalConversionResult.Unmappable("No applicable Mapping for this coordinate")
            } else {
                TemporalConversionResult.Unmappable("No Mapping path between the temporal axes")
            }
        }
        val converted = outcomes.mapNotNull { outcome ->
            denormalizeFromAxis(targetTimeline, outcome.value)?.let { TemporalValue(targetTimeline, it) to outcome }
        }.distinctBy { it.first }
        if (converted.isEmpty()) return TemporalConversionResult.Unmappable("Mapping result cannot be represented on $targetTimeline")
        if (converted.size > 1) return TemporalConversionResult.Alternatives(converted.map { it.first })
        val (target, outcome) = converted.single()
        return when (outcome.precision.kind) {
            TemporalPrecisionKind.Exact -> TemporalConversionResult.Exact(target)
            TemporalPrecisionKind.Approximate -> TemporalConversionResult.Approximate(target, outcome.precision.error)
            TemporalPrecisionKind.Uncertain -> {
                val error = outcome.precision.error
                    ?: return TemporalConversionResult.Approximate(target, null)
                val lower = denormalizeFromAxis(targetTimeline, outcome.value - error)
                val upper = denormalizeFromAxis(targetTimeline, outcome.value + error)
                if (lower != null && upper != null) {
                    TemporalConversionResult.Range(
                        TemporalValue(targetTimeline, lower),
                        TemporalValue(targetTimeline, upper),
                    )
                } else {
                    TemporalConversionResult.Approximate(target, error)
                }
            }
        }
    }

    /**
     * Converts a value using only paths that are safe for ordinary temporal
     * search: exact, context-free, single-valued, and order-preserving.
     * Richer paths remain available through [convert].
     */
    fun convertForSearch(
        value: TemporalValue,
        targetTimeline: String,
        requireTotalContinuousPath: Boolean = false,
    ): TemporalValue? {
        val sourceSystem = coordinateSystems[value.timeline] ?: return null
        val targetSystem = coordinateSystems[targetTimeline] ?: return null
        val sourceAxisValue = normalizeToAxis(value.timeline, value.coordinate) ?: return null
        if (sourceSystem.axisId == targetSystem.axisId) {
            return denormalizeFromAxis(targetTimeline, sourceAxisValue)?.let {
                TemporalValue(targetTimeline, it)
            }
        }
        val outcomes = findBestOutcomes(
            sourceAxis = sourceSystem.axisId,
            targetAxis = targetSystem.axisId,
            initial = sourceAxisValue,
            context = emptyMap(),
            searchOnly = true,
            requireTotalContinuousPath = requireTotalContinuousPath,
        ).distinctBy { it.value }
        if (outcomes.size != 1 || outcomes.single().precision.kind != TemporalPrecisionKind.Exact) return null
        return denormalizeFromAxis(targetTimeline, outcomes.single().value)?.let {
            TemporalValue(targetTimeline, it)
        }
    }

    fun compare(
        left: TemporalValue,
        right: TemporalValue,
        context: Map<String, NormalizedValue> = emptyMap(),
    ): TemporalComparisonResult {
        val leftSystem = coordinateSystems[left.timeline]
            ?: return TemporalComparisonResult.Unmappable("Unknown Timeline: ${left.timeline}")
        val rightSystem = coordinateSystems[right.timeline]
            ?: return TemporalComparisonResult.Unmappable("Unknown Timeline: ${right.timeline}")
        if (
            leftSystem.axisId != rightSystem.axisId &&
            !areAxesConnected(rightSystem.axisId, leftSystem.axisId) &&
            !areAxesConnected(leftSystem.axisId, rightSystem.axisId)
        ) {
            return TemporalComparisonResult.Unrelated
        }
        val convertedRight = convert(right, left.timeline, context)
        if (convertedRight !is TemporalConversionResult.Unmappable) {
            return compareWithConvertedRight(left, convertedRight)
        }
        val convertedLeft = convert(left, right.timeline, context)
        if (convertedLeft !is TemporalConversionResult.Unmappable) {
            return compareWithConvertedLeft(convertedLeft, right)
        }
        return TemporalComparisonResult.Unmappable(convertedRight.reason)
    }

    private fun compareWithConvertedRight(
        left: TemporalValue,
        converted: TemporalConversionResult,
    ): TemporalComparisonResult = when (converted) {
            is TemporalConversionResult.Exact -> compareOnSameTimeline(left, converted.value)
            is TemporalConversionResult.Approximate -> {
                val order = compareCoordinates(left.timeline, left.coordinate, converted.value.coordinate)
                TemporalComparisonResult.Approximate(order, converted.error)
            }
            is TemporalConversionResult.Alternatives -> {
                val orders = converted.values.mapNotNull { compareCoordinates(left.timeline, left.coordinate, it.coordinate) }.distinct()
                orders.toComparisonResult()
            }
            is TemporalConversionResult.Range -> compareAgainstRange(
                point = left,
                from = converted.from,
                to = converted.to,
                pointFirst = true,
            )
            is TemporalConversionResult.Unmappable -> TemporalComparisonResult.Unmappable(converted.reason)
        }

    private fun compareWithConvertedLeft(
        converted: TemporalConversionResult,
        right: TemporalValue,
    ): TemporalComparisonResult = when (converted) {
        is TemporalConversionResult.Exact -> compareOnSameTimeline(converted.value, right)
        is TemporalConversionResult.Approximate -> {
            val order = compareCoordinates(right.timeline, converted.value.coordinate, right.coordinate)
            TemporalComparisonResult.Approximate(order, converted.error)
        }
        is TemporalConversionResult.Alternatives -> converted.values
            .mapNotNull { compareCoordinates(right.timeline, it.coordinate, right.coordinate) }
            .distinct()
            .toComparisonResult()
        is TemporalConversionResult.Range -> compareAgainstRange(
            point = right,
            from = converted.from,
            to = converted.to,
            pointFirst = false,
        )
        is TemporalConversionResult.Unmappable -> TemporalComparisonResult.Unmappable(converted.reason)
    }

    private fun compareAgainstRange(
        point: TemporalValue,
        from: TemporalValue,
        to: TemporalValue,
        pointFirst: Boolean,
    ): TemporalComparisonResult {
        val pointValue = normalizeToAxis(point.timeline, point.coordinate)
            ?: return TemporalComparisonResult.Unmappable("Coordinates cannot be ordered")
        val fromValue = normalizeToAxis(from.timeline, from.coordinate)
            ?: return TemporalComparisonResult.Unmappable("Mapping range cannot be ordered")
        val toValue = normalizeToAxis(to.timeline, to.coordinate)
            ?: return TemporalComparisonResult.Unmappable("Mapping range cannot be ordered")
        if (pointValue in minOf(fromValue, toValue)..maxOf(fromValue, toValue)) {
            return TemporalComparisonResult.Overlapping
        }
        val orders = if (pointFirst) {
            listOfNotNull(
                compareCoordinates(point.timeline, point.coordinate, from.coordinate),
                compareCoordinates(point.timeline, point.coordinate, to.coordinate),
            )
        } else {
            listOfNotNull(
                compareCoordinates(point.timeline, from.coordinate, point.coordinate),
                compareCoordinates(point.timeline, to.coordinate, point.coordinate),
            )
        }
        return orders.distinct().toComparisonResult()
    }

    private fun List<TemporalOrder>.toComparisonResult(): TemporalComparisonResult = when (size) {
        0 -> TemporalComparisonResult.Unmappable("Mapping produced no orderable result")
        1 -> TemporalComparisonResult.Ordered(single())
        else -> TemporalComparisonResult.Ambiguous(this)
    }

    fun normalizeToAxis(timeline: String, coordinate: TemporalCoordinate): ExactRational? {
        val system = coordinateSystems[timeline] ?: return null
        return when (val spec = system.coordinate) {
            TemporalCoordinateSpec.Number -> {
                val value = (coordinate as? TemporalCoordinate.Rational)?.value ?: return null
                if (system.parentTimelineId == null) {
                    value
                } else {
                    val parentCoordinate = (value - system.offsetFromParent) / system.scaleToParent
                    normalizeToAxis(system.parentTimelineId, TemporalCoordinate.Rational(parentCoordinate))
                }
            }
            is TemporalCoordinateSpec.Calendar -> {
                val date = when (coordinate) {
                    is TemporalCoordinate.CalendarDate -> coordinate
                    is TemporalCoordinate.Label -> parseCalendarDate(coordinate.value)
                    else -> null
                } ?: return null
                calendarToDayIndex(date, spec)
            }
            is TemporalCoordinateSpec.CalendarPattern -> {
                val pattern = coerceCoordinate(timeline, coordinate) as? TemporalCoordinate.CalendarPattern ?: return null
                if (spec.repeatsEvery != null || spec.fields.toSet() != setOf(
                        CalendarField.Year,
                        CalendarField.Month,
                        CalendarField.Day,
                    )
                ) return null
                pattern.toCalendarDate()?.let {
                    calendarToDayIndex(it, TemporalCoordinateSpec.Calendar(spec.calendar, spec.numbering))
                }
            }
            is TemporalCoordinateSpec.Frame -> {
                val frame = when (coordinate) {
                    is TemporalCoordinate.FrameIndex -> coordinate.value
                    is TemporalCoordinate.Rational -> coordinate.value.takeIf { it.denominator == 1L }?.numerator
                    else -> null
                } ?: return null
                ExactRational.of(frame - spec.start)
            }
            is TemporalCoordinateSpec.Timecode -> {
                val timecode = when (coordinate) {
                    is TemporalCoordinate.Timecode -> coordinate
                    is TemporalCoordinate.Label -> parseTimecode(coordinate.value)
                    else -> null
                } ?: return null
                ExactRational.of(timecodeToFrame(timecode, spec))
            }
            is TemporalCoordinateSpec.Era -> {
                val eraDate = when (coordinate) {
                    is TemporalCoordinate.EraDate -> coordinate
                    is TemporalCoordinate.Label -> parseEraDate(coordinate.value)
                    else -> null
                } ?: return null
                val parentId = system.parentTimelineId ?: return null
                val parent = coordinateSystems[parentId] ?: return null
                val parentCalendar = parent.coordinate as? TemporalCoordinateSpec.Calendar ?: return null
                val date = eraToCalendarDate(eraDate, spec, parentCalendar) ?: return null
                normalizeToAxis(parentId, date)
            }
        }
    }

    fun denormalizeFromAxis(timeline: String, value: ExactRational): TemporalCoordinate? {
        val system = coordinateSystems[timeline] ?: return null
        return when (val spec = system.coordinate) {
            TemporalCoordinateSpec.Number -> {
                val local = if (system.parentTimelineId == null) {
                    value
                } else {
                    val parent = denormalizeFromAxis(system.parentTimelineId, value) as? TemporalCoordinate.Rational ?: return null
                    parent.value * system.scaleToParent + system.offsetFromParent
                }
                TemporalCoordinate.Rational(local)
            }
            is TemporalCoordinateSpec.Calendar -> {
                if (value.denominator != 1L) return null
                dayIndexToCalendar(value.numerator, spec)
            }
            is TemporalCoordinateSpec.CalendarPattern -> {
                if (value.denominator != 1L || spec.repeatsEvery != null || spec.fields.toSet() != setOf(
                        CalendarField.Year,
                        CalendarField.Month,
                        CalendarField.Day,
                    )
                ) return null
                val date = dayIndexToCalendar(
                    value.numerator,
                    TemporalCoordinateSpec.Calendar(spec.calendar, spec.numbering),
                ) ?: return null
                TemporalCoordinate.CalendarPattern(
                    mapOf(
                        CalendarField.Year to date.year,
                        CalendarField.Month to date.month.toLong(),
                        CalendarField.Day to date.day.toLong(),
                    ),
                )
            }
            is TemporalCoordinateSpec.Frame -> {
                if (value.denominator != 1L) return null
                TemporalCoordinate.FrameIndex(value.numerator + spec.start)
            }
            is TemporalCoordinateSpec.Timecode -> {
                if (value.denominator != 1L) return null
                frameToTimecode(value.numerator, spec)
            }
            is TemporalCoordinateSpec.Era -> {
                val parentId = system.parentTimelineId ?: return null
                val calendar = denormalizeFromAxis(parentId, value) as? TemporalCoordinate.CalendarDate ?: return null
                val parentCalendar = coordinateSystems[parentId]?.coordinate as? TemporalCoordinateSpec.Calendar ?: return null
                calendarToEraDate(calendar, spec, parentCalendar)
            }
        }
    }

    private fun compareOnSameTimeline(left: TemporalValue, right: TemporalValue): TemporalComparisonResult {
        val order = compareCoordinates(left.timeline, left.coordinate, right.coordinate)
            ?: return TemporalComparisonResult.Unmappable("Coordinates cannot be ordered")
        return TemporalComparisonResult.Ordered(order)
    }

    fun format(timeline: String, coordinate: TemporalCoordinate): String {
        val spec = coordinateSystems[timeline]?.coordinate
            ?: throw IllegalArgumentException("Unknown Timeline: $timeline")
        return when {
            spec is TemporalCoordinateSpec.CalendarPattern && coordinate is TemporalCoordinate.CalendarPattern ->
                formatCalendarPattern(spec, coordinate)
            else -> coordinate.toString()
        }
    }

    fun resolveToAxis(
        timeline: String,
        coordinate: TemporalCoordinate,
        window: TemporalExpansionWindow? = null,
        recurrenceContextYears: Int = 0,
    ): TemporalSelection? {
        require(recurrenceContextYears >= 0) { "recurrence context years must not be negative" }
        val system = coordinateSystems[timeline] ?: return null
        val spec = system.coordinate as? TemporalCoordinateSpec.CalendarPattern
            ?: return normalizeToAxis(timeline, coordinate)?.let(TemporalSelection::Instant)
        val pattern = coerceCoordinate(timeline, coordinate) as? TemporalCoordinate.CalendarPattern ?: return null
        return resolveCalendarPattern(spec, pattern, window, recurrenceContextYears)
    }

    fun expansionWindow(
        timeline: String,
        start: TemporalCoordinate.CalendarDate,
        endExclusive: TemporalCoordinate.CalendarDate,
    ): TemporalExpansionWindow? {
        val coordinate = coordinateSystems[timeline]?.coordinate ?: return null
        val calendar = when (coordinate) {
            is TemporalCoordinateSpec.Calendar -> coordinate
            is TemporalCoordinateSpec.CalendarPattern -> TemporalCoordinateSpec.Calendar(
                coordinate.calendar,
                coordinate.numbering,
            )
            else -> return null
        }
        val normalizedStart = calendarToDayIndex(start, calendar) ?: return null
        val normalizedEnd = calendarToDayIndex(endExclusive, calendar) ?: return null
        return runCatching { TemporalExpansionWindow(normalizedStart, normalizedEnd) }.getOrNull()
    }

    private fun compareCoordinates(
        timeline: String,
        left: TemporalCoordinate,
        right: TemporalCoordinate,
    ): TemporalOrder? {
        val leftValue = normalizeToAxis(timeline, left) ?: return null
        val rightValue = normalizeToAxis(timeline, right) ?: return null
        return when {
            leftValue < rightValue -> TemporalOrder.Before
            leftValue > rightValue -> TemporalOrder.After
            else -> TemporalOrder.Equal
        }
    }

    private fun findBestOutcomes(
        sourceAxis: String,
        targetAxis: String,
        initial: ExactRational,
        context: Map<String, NormalizedValue>,
        searchOnly: Boolean = false,
        requireTotalContinuousPath: Boolean = false,
    ): List<PathOutcome> {
        data class State(val axis: String, val outcomes: List<PathOutcome>, val visited: Set<String>)
        var frontier = listOf(State(sourceAxis, listOf(PathOutcome(initial, TemporalPrecision())), setOf(sourceAxis)))
        val completed = mutableListOf<PathOutcome>()
        val maximumHops = axes.size.coerceAtLeast(1)
        repeat(maximumHops + 1) {
            completed += frontier.filter { it.axis == targetAxis }.flatMap { it.outcomes }
            frontier = frontier.filter { it.axis != targetAxis }.flatMap { state ->
                mappingsByAxis[state.axis].orEmpty().flatMap { edge ->
                    if (searchOnly && !edge.isSearchSafe(requireTotalContinuousPath)) {
                        return@flatMap emptyList()
                    }
                    val nextAxis = edge.toAxis
                    if (nextAxis in state.visited) return@flatMap emptyList()
                    val next = state.outcomes.flatMap { applyMapping(edge, it, context) }
                    if (next.isEmpty()) emptyList()
                    else listOf(State(nextAxis, next, state.visited + nextAxis))
                }
            }
            if (frontier.isEmpty()) return chooseBestOutcomes(completed)
        }
        return chooseBestOutcomes(completed)
    }

    private fun chooseBestOutcomes(outcomes: List<PathOutcome>): List<PathOutcome> {
        val best = outcomes.minWithOrNull(
            compareBy<PathOutcome> { precisionRank(it.precision.kind) }
                .thenBy { it.informationLoss }
                .thenBy { it.hops },
        ) ?: return emptyList()
        return outcomes.filter {
            precisionRank(it.precision.kind) == precisionRank(best.precision.kind) &&
                it.informationLoss == best.informationLoss && it.hops == best.hops
        }.distinctBy { it.value }
    }

    private fun precisionRank(kind: TemporalPrecisionKind): Int = when (kind) {
        TemporalPrecisionKind.Exact -> 0
        TemporalPrecisionKind.Approximate -> 1
        TemporalPrecisionKind.Uncertain -> 2
    }

    private fun applyMapping(
        edge: MappingEdge,
        incoming: PathOutcome,
        context: Map<String, NormalizedValue>,
    ): List<PathOutcome> {
        val mapping = edge.mapping
        if (mapping.requiredContext.any { it !in context }) return emptyList()
        val values = if (edge.inverse) applyInverse(mapping, incoming.value) else applyForward(mapping, incoming.value)
        val precision = combinePrecision(incoming.precision, mapping.precision)
        return values.map {
            PathOutcome(
                value = it,
                precision = precision,
                informationLoss = incoming.informationLoss + edge.informationLoss,
                hops = incoming.hops + 1,
            )
        }
    }

    private fun applyForward(mapping: TemporalMappingInstance, value: ExactRational): List<ExactRational> {
        mapping.pairs.takeIf { it.isNotEmpty() }?.let { pairs ->
            return pairs.filter { pair -> normalizeToAxis(mapping.sourceTimelineId, pair.from) == value }
                .flatMap { pair -> pair.to.mapNotNull { normalizeToAxis(mapping.targetTimelineId, it) } }
        }
        val segments = mapping.segments
        if (segments.isNotEmpty()) {
            return segments.flatMap { segment -> applySegment(mapping, segment, value, inverse = false) }
        }
        if (!withinRange(mapping.sourceTimelineId, value, mapping.range)) return emptyList()
        return listOf(value * mapping.scale + mapping.offset)
    }

    private fun applyInverse(mapping: TemporalMappingInstance, value: ExactRational): List<ExactRational> {
        mapping.pairs.takeIf { it.isNotEmpty() }?.let { pairs ->
            return pairs.filter { pair -> pair.to.any { normalizeToAxis(mapping.targetTimelineId, it) == value } }
                .mapNotNull { normalizeToAxis(mapping.sourceTimelineId, it.from) }
        }
        val segments = mapping.segments
        if (segments.isNotEmpty()) {
            return segments.flatMap { segment -> applySegment(mapping, segment, value, inverse = true) }
        }
        if (mapping.scale == ExactRational.ZERO) return emptyList()
        val source = (value - mapping.offset) / mapping.scale
        return if (withinRange(mapping.sourceTimelineId, source, mapping.range)) listOf(source) else emptyList()
    }

    private fun applySegment(
        mapping: TemporalMappingInstance,
        segment: TemporalMappingSegment,
        value: ExactRational,
        inverse: Boolean,
    ): List<ExactRational> {
        if (segment.pairs.isNotEmpty()) {
            return if (inverse) {
                segment.pairs.filter { pair -> pair.to.any { normalizeToAxis(mapping.targetTimelineId, it) == value } }
                    .mapNotNull { normalizeToAxis(mapping.sourceTimelineId, it.from) }
            } else {
                segment.pairs.filter { normalizeToAxis(mapping.sourceTimelineId, it.from) == value }
                    .flatMap { it.to.mapNotNull { target -> normalizeToAxis(mapping.targetTimelineId, target) } }
            }
        }
        val scaleAndOffset = inferredTransform(mapping, segment) ?: return emptyList()
        val (scale, offset) = scaleAndOffset
        return if (inverse) {
            if (!withinRange(mapping.targetTimelineId, value, segment.target) || scale == ExactRational.ZERO) emptyList()
            else listOf((value - offset) / scale).filter { withinRange(mapping.sourceTimelineId, it, segment.source) }
        } else {
            if (!withinRange(mapping.sourceTimelineId, value, segment.source)) emptyList()
            else listOf(value * scale + offset).filter { withinRange(mapping.targetTimelineId, it, segment.target) }
        }
    }

    private fun inferredTransform(
        mapping: TemporalMappingInstance,
        segment: TemporalMappingSegment,
    ): Pair<ExactRational, ExactRational>? {
        val sourceFrom = segment.source?.from?.let { normalizeToAxis(mapping.sourceTimelineId, it) }
        val sourceTo = segment.source?.to?.let { normalizeToAxis(mapping.sourceTimelineId, it) }
        val targetFrom = segment.target?.from?.let { normalizeToAxis(mapping.targetTimelineId, it) }
        val targetTo = segment.target?.to?.let { normalizeToAxis(mapping.targetTimelineId, it) }
        if (sourceFrom != null && sourceTo != null && targetFrom != null && targetTo != null && sourceFrom != sourceTo) {
            val scale = (targetTo - targetFrom) / (sourceTo - sourceFrom)
            return scale to (targetFrom - sourceFrom * scale)
        }
        return segment.scale to segment.offset
    }

    private fun withinRange(timeline: String, value: ExactRational, range: TemporalCoordinateRange?): Boolean {
        if (range == null) return true
        val from = range.from?.let { normalizeToAxis(timeline, it) }
        val to = range.to?.let { normalizeToAxis(timeline, it) }
        return when {
            from != null && to != null && from > to -> value <= from && value >= to
            else -> (from == null || value >= from) && (to == null || value <= to)
        }
    }

    private fun combinePrecision(left: TemporalPrecision, right: TemporalPrecision): TemporalPrecision {
        val kind = if (precisionRank(left.kind) >= precisionRank(right.kind)) left.kind else right.kind
        val error = listOfNotNull(left.error, right.error).fold(ExactRational.ZERO, ExactRational::plus)
            .takeUnless { it == ExactRational.ZERO }
        return TemporalPrecision(kind, error)
    }

    private fun areAxesConnected(source: String, target: String): Boolean {
        if (source == target) return true
        val seen = mutableSetOf(source)
        val queue = ArrayDeque<String>()
        queue += source
        while (queue.isNotEmpty()) {
            val axis = queue.removeFirst()
            mappingsByAxis[axis].orEmpty().forEach { edge ->
                if (edge.toAxis == target) return true
                if (seen.add(edge.toAxis)) queue += edge.toAxis
            }
        }
        return false
    }

    private data class MappingEdge(val mapping: TemporalMappingInstance, val inverse: Boolean) {
        val toAxis: String get() = if (inverse) mapping.sourceAxisId else mapping.targetAxisId

        fun isSearchSafe(requireTotalContinuousPath: Boolean): Boolean {
            if (
                requireTotalContinuousPath &&
                (mapping.traits.totality != TemporalTotality.Total ||
                    mapping.traits.continuity != TemporalContinuity.Continuous)
            ) {
                return false
            }
            if (mapping.precision.kind != TemporalPrecisionKind.Exact || mapping.requiredContext.isNotEmpty()) {
                return false
            }
            if (mapping.traits.orderBehavior !in setOf(
                    TemporalOrderBehavior.StrictlyIncreasing,
                    TemporalOrderBehavior.Monotonic,
                )
            ) return false
            return if (inverse) {
                mapping.traits.cardinality in setOf(
                    TemporalCardinality.OneToOne,
                    TemporalCardinality.OneToMany,
                )
            } else {
                mapping.traits.cardinality in setOf(
                    TemporalCardinality.OneToOne,
                    TemporalCardinality.ManyToOne,
                )
            }
        }

        val informationLoss: Int
            get() = buildList {
                if (mapping.traits.cardinality != TemporalCardinality.OneToOne) add(2)
                if (mapping.traits.invertibility != TemporalInvertibility.Invertible) add(1)
                if (mapping.traits.continuity == TemporalContinuity.Discrete) add(1)
            }.sum()
    }

    private data class PathOutcome(
        val value: ExactRational,
        val precision: TemporalPrecision,
        val informationLoss: Int = 0,
        val hops: Int = 0,
    )
}

internal fun parseCoordinate(spec: TemporalCoordinateSpec, raw: String): TemporalCoordinate = when (spec) {
    TemporalCoordinateSpec.Number -> TemporalCoordinate.Rational(ExactRational.parse(raw))
    is TemporalCoordinateSpec.Calendar -> parseCalendarDate(raw)
        ?: throw IllegalArgumentException("Invalid calendar date: $raw")
    is TemporalCoordinateSpec.CalendarPattern -> parseCalendarPattern(spec, raw)
        ?: throw IllegalArgumentException("Invalid calendar pattern: $raw")
    is TemporalCoordinateSpec.Frame -> TemporalCoordinate.FrameIndex(raw.trim().toLong())
    is TemporalCoordinateSpec.Timecode -> parseTimecode(raw)
        ?: throw IllegalArgumentException("Invalid timecode: $raw")
    is TemporalCoordinateSpec.Era -> parseEraDate(raw)
        ?: throw IllegalArgumentException("Invalid era date: $raw")
}

private fun TemporalCoordinate.CalendarPattern.toCalendarDate(): TemporalCoordinate.CalendarDate? {
    val year = fields[CalendarField.Year] ?: return null
    val month = fields[CalendarField.Month]?.toInt() ?: return null
    val day = fields[CalendarField.Day]?.toInt() ?: return null
    return TemporalCoordinate.CalendarDate(year, month, day)
}

private val calendarPatternPlaceholder = Regex("""\{([A-Za-z][A-Za-z0-9]*)(?::(\d+))?\}""")

private fun parseCalendarPattern(
    spec: TemporalCoordinateSpec.CalendarPattern,
    raw: String,
): TemporalCoordinate.CalendarPattern? {
    val template = spec.format ?: defaultCalendarPatternFormat(spec.fields)
    val placeholders = calendarPatternPlaceholder.findAll(template).toList()
    if (placeholders.isEmpty()) return null
    val fieldByName = spec.fields.associateBy(::calendarFieldName)
    val fields = mutableListOf<CalendarField>()
    val regex = buildString {
        append('^')
        var offset = 0
        placeholders.forEach { placeholder ->
            append(Regex.escape(template.substring(offset, placeholder.range.first)))
            val field = fieldByName[placeholder.groupValues[1]] ?: return null
            fields += field
            val width = placeholder.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()
            append(calendarFieldPattern(field, width))
            offset = placeholder.range.last + 1
        }
        append(Regex.escape(template.substring(offset)))
        append('$')
    }
    val match = Regex(regex).matchEntire(raw.trim()) ?: return null
    val values = fields.mapIndexed { index, field ->
        val token = match.groupValues[index + 1]
        val numeric = token.toLongOrNull() ?: return null
        field to numeric
    }.toMap()
    if (values.keys != spec.fields.toSet()) return null
    if (!validCalendarPatternFields(spec, values)) return null
    return TemporalCoordinate.CalendarPattern(values)
}

private fun formatCalendarPattern(
    spec: TemporalCoordinateSpec.CalendarPattern,
    coordinate: TemporalCoordinate.CalendarPattern,
): String {
    require(coordinate.fields.keys == spec.fields.toSet()) { "calendar-pattern fields do not match the Timeline declaration" }
    val template = spec.format ?: defaultCalendarPatternFormat(spec.fields)
    return calendarPatternPlaceholder.replace(template) { match ->
        val field = spec.fields.firstOrNull { calendarFieldName(it) == match.groupValues[1] }
            ?: error("Unknown calendar-pattern format field: ${match.groupValues[1]}")
        val value = coordinate.fields.getValue(field)
        val width = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull()
            ?: defaultCalendarFieldWidth(field)
        padCalendarField(value, width)
    }
}

private fun defaultCalendarPatternFormat(fields: List<CalendarField>): String =
    fields.sortedBy(CalendarField::ordinal).joinToString("-") { field ->
        val name = calendarFieldName(field)
        val width = defaultCalendarFieldWidth(field)
        when (field) {
            CalendarField.Quarter -> "Q{$name:$width}"
            CalendarField.Week -> "W{$name:$width}"
            else -> "{$name:$width}"
        }
    }

private fun calendarFieldName(field: CalendarField): String = when (field) {
    CalendarField.Year -> "year"
    CalendarField.Month -> "month"
    CalendarField.Day -> "day"
    CalendarField.Quarter -> "quarter"
    CalendarField.WeekYear -> "weekYear"
    CalendarField.Week -> "week"
}

private fun defaultCalendarFieldWidth(field: CalendarField): Int = when (field) {
    CalendarField.Year, CalendarField.WeekYear -> 4
    CalendarField.Month, CalendarField.Day, CalendarField.Week -> 2
    CalendarField.Quarter -> 1
}

private fun calendarFieldPattern(field: CalendarField, width: Int?): String {
    val digits = when {
        field == CalendarField.Year || field == CalendarField.WeekYear ->
            if (width == null) "[+-]?\\d{4,}" else "[+-]?\\d{$width,}"
        width == null -> "\\d{${defaultCalendarFieldWidth(field)}}"
        else -> "\\d{$width}"
    }
    return "($digits)"
}

private fun padCalendarField(value: Long, width: Int): String {
    if (value >= 0) return value.toString().padStart(width, '0')
    return "-" + (-value).toString().padStart(width, '0')
}

private fun validCalendarPatternFields(
    spec: TemporalCoordinateSpec.CalendarPattern,
    fields: Map<CalendarField, Long>,
): Boolean {
    fields[CalendarField.Month]?.let { if (it !in 1..12) return false }
    fields[CalendarField.Day]?.let { if (it !in 1..31) return false }
    fields[CalendarField.Quarter]?.let { if (it !in 1..4) return false }
    fields[CalendarField.Week]?.let { if (it !in 1..53) return false }
    val year = fields[CalendarField.Year]
    val month = fields[CalendarField.Month]
    val day = fields[CalendarField.Day]
    if (year != null && month != null && day != null) {
        val astronomical = toAstronomicalYear(year, spec.numbering) ?: return false
        if (!validDate(astronomical, month.toInt(), day.toInt(), spec.calendar)) return false
    } else if (month != null && day != null && !validDate(2000, month.toInt(), day.toInt(), spec.calendar)) {
        return false
    }
    val weekYear = fields[CalendarField.WeekYear]
    val week = fields[CalendarField.Week]
    if (weekYear != null && week != null) {
        val astronomical = toAstronomicalYear(weekYear, spec.numbering) ?: return false
        val first = isoWeekOneMonday(astronomical, spec.numbering) ?: return false
        val next = isoWeekOneMonday(astronomical + 1, spec.numbering) ?: return false
        val weeks = ((next - first).numerator / 7).toInt()
        if (week !in 1L..weeks.toLong()) return false
    }
    return true
}

internal fun parseGenericTemporalCoordinate(raw: String): TemporalCoordinate {
    val value = raw.trim()
    return runCatching { TemporalCoordinate.Rational(ExactRational.parse(value)) }.getOrNull()
        ?: parseCalendarDate(value)
        ?: parseTimecode(value)
        ?: parseEraDate(value)
        ?: TemporalCoordinate.Label(value)
}

private val calendarDatePattern = Regex("""^([+-]?\d+)-(\d{1,2})-(\d{1,2})$""")
private fun parseCalendarDate(raw: String): TemporalCoordinate.CalendarDate? {
    val match = calendarDatePattern.matchEntire(raw.trim()) ?: return null
    return TemporalCoordinate.CalendarDate(
        match.groupValues[1].toLong(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
    )
}

private val timecodePattern = Regex("""^(\d+):(\d{2}):(\d{2})[:;](\d{2,3})$""")
private fun parseTimecode(raw: String): TemporalCoordinate.Timecode? {
    val match = timecodePattern.matchEntire(raw.trim()) ?: return null
    return TemporalCoordinate.Timecode(
        match.groupValues[1].toInt(),
        match.groupValues[2].toInt(),
        match.groupValues[3].toInt(),
        match.groupValues[4].toInt(),
    )
}

private val eraDatePattern = Regex("""^([^\s:-]+)[\s:-]+(\d+)-(\d{1,2})-(\d{1,2})$""")
private fun parseEraDate(raw: String): TemporalCoordinate.EraDate? {
    val match = eraDatePattern.matchEntire(raw.trim()) ?: return null
    return TemporalCoordinate.EraDate(
        match.groupValues[1],
        match.groupValues[2].toLong(),
        match.groupValues[3].toInt(),
        match.groupValues[4].toInt(),
    )
}

private fun calendarToDayIndex(
    coordinate: TemporalCoordinate.CalendarDate,
    spec: TemporalCoordinateSpec.Calendar,
): ExactRational? {
    val astronomicalYear = toAstronomicalYear(coordinate.year, spec.numbering) ?: return null
    if (!validDate(astronomicalYear, coordinate.month, coordinate.day, spec.calendar)) return null
    val adjustment = (14 - coordinate.month) / 12
    val y = astronomicalYear + 4800 - adjustment
    val month = coordinate.month + 12 * adjustment - 3
    val dayIndex = when (spec.calendar) {
        CalendarKind.Gregorian -> coordinate.day + (153L * month + 2) / 5 + 365 * y +
            floorDiv(y, 4) - floorDiv(y, 100) + floorDiv(y, 400) - 32045
        CalendarKind.Julian -> coordinate.day + (153L * month + 2) / 5 + 365 * y + floorDiv(y, 4) - 32083
    }
    return ExactRational.of(dayIndex)
}

private fun dayIndexToCalendar(
    dayIndex: Long,
    spec: TemporalCoordinateSpec.Calendar,
): TemporalCoordinate.CalendarDate? {
    val astronomical = when (spec.calendar) {
        CalendarKind.Gregorian -> {
            val a = dayIndex + 32044
            val b = floorDiv(4 * a + 3, 146097)
            val c = a - floorDiv(146097 * b, 4)
            val d = floorDiv(4 * c + 3, 1461)
            val e = c - floorDiv(1461 * d, 4)
            val m = floorDiv(5 * e + 2, 153)
            Triple(100 * b + d - 4800 + floorDiv(m, 10), (m + 3 - 12 * floorDiv(m, 10)).toInt(), (e - floorDiv(153 * m + 2, 5) + 1).toInt())
        }
        CalendarKind.Julian -> {
            val c = dayIndex + 32082
            val d = floorDiv(4 * c + 3, 1461)
            val e = c - floorDiv(1461 * d, 4)
            val m = floorDiv(5 * e + 2, 153)
            Triple(d - 4800 + floorDiv(m, 10), (m + 3 - 12 * floorDiv(m, 10)).toInt(), (e - floorDiv(153 * m + 2, 5) + 1).toInt())
        }
    }
    val authoredYear = fromAstronomicalYear(astronomical.first, spec.numbering) ?: return null
    return TemporalCoordinate.CalendarDate(authoredYear, astronomical.second, astronomical.third)
}

private fun toAstronomicalYear(year: Long, numbering: YearNumbering): Long? = when (numbering) {
    YearNumbering.Astronomical -> year
    YearNumbering.CommonEra -> when {
        year > 0 -> year
        year < 0 -> year + 1
        else -> null
    }
    is YearNumbering.Offset -> {
        val shifted = year - numbering.offset
        when {
            numbering.yearZero -> shifted
            shifted > 0 -> shifted
            shifted < 0 -> shifted + 1
            else -> null
        }
    }
}

private fun fromAstronomicalYear(year: Long, numbering: YearNumbering): Long? = when (numbering) {
    YearNumbering.Astronomical -> year
    YearNumbering.CommonEra -> if (year > 0) year else year - 1
    is YearNumbering.Offset -> {
        val displayed = if (numbering.yearZero || year > 0) year else year - 1
        displayed + numbering.offset
    }
}

private fun validDate(year: Long, month: Int, day: Int, calendar: CalendarKind): Boolean {
    if (month !in 1..12 || day < 1) return false
    val leap = when (calendar) {
        CalendarKind.Gregorian -> floorMod(year, 4) == 0L && (floorMod(year, 100) != 0L || floorMod(year, 400) == 0L)
        CalendarKind.Julian -> floorMod(year, 4) == 0L
    }
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    return day <= days
}

private fun resolveCalendarPattern(
    spec: TemporalCoordinateSpec.CalendarPattern,
    coordinate: TemporalCoordinate.CalendarPattern,
    window: TemporalExpansionWindow?,
    recurrenceContextYears: Int,
): TemporalSelection? {
    if (coordinate.fields.keys != spec.fields.toSet() || !validCalendarPatternFields(spec, coordinate.fields)) return null
    if (spec.repeatsEvery == null) return resolveSingleCalendarPattern(spec, coordinate.fields)
    val expansion = window ?: return null
    if (expansion.start.denominator != 1L || expansion.endExclusive.denominator != 1L) return null
    val calendar = TemporalCoordinateSpec.Calendar(spec.calendar, spec.numbering)
    val from = dayIndexToCalendar(expansion.start.numerator, calendar) ?: return null
    val to = dayIndexToCalendar(expansion.endExclusive.numerator - 1, calendar) ?: return null
    val fromAstronomical = toAstronomicalYear(from.year, spec.numbering) ?: return null
    val toAstronomical = toAstronomicalYear(to.year, spec.numbering) ?: return null
    if (toAstronomical < fromAstronomical || toAstronomical - fromAstronomical > 10_000L) return null
    val expansionMargin = recurrenceContextYears.toLong() + 2L
    if (fromAstronomical < Long.MIN_VALUE + expansionMargin || toAstronomical > Long.MAX_VALUE - expansionMargin) {
        return null
    }
    val firstExpansionYear = fromAstronomical - expansionMargin
    val lastExpansionYear = toAstronomical + expansionMargin
    val occurrences = mutableListOf<TemporalAxisPeriod>()
    for (astronomicalYear in firstExpansionYear..lastExpansionYear) {
        val authoredYear = fromAstronomicalYear(astronomicalYear, spec.numbering) ?: continue
        val yearField = if (CalendarField.Week in spec.fields) CalendarField.WeekYear else CalendarField.Year
        val expandedFields = coordinate.fields + (yearField to authoredYear)
        val expandedSpec = spec.copy(
            fields = (spec.fields + yearField).distinct().sortedBy(CalendarField::ordinal),
            repeatsEvery = null,
        )
        val period = when (val selection = resolveSingleCalendarPattern(expandedSpec, expandedFields)) {
            is TemporalSelection.Instant -> TemporalAxisPeriod(selection.value, selection.value + ExactRational.ONE)
            is TemporalSelection.Period -> selection.value
            else -> null
        } ?: continue
        val start = if (recurrenceContextYears == 0) maxOf(period.start, expansion.start) else period.start
        val end = if (recurrenceContextYears == 0) minOf(period.endExclusive, expansion.endExclusive) else period.endExclusive
        if (start < end) occurrences += TemporalAxisPeriod(start, end)
    }
    return TemporalSelection.Recurrence(spec.repeatsEvery, occurrences.distinct())
}

private fun resolveSingleCalendarPattern(
    spec: TemporalCoordinateSpec.CalendarPattern,
    fields: Map<CalendarField, Long>,
): TemporalSelection? {
    val calendar = TemporalCoordinateSpec.Calendar(spec.calendar, spec.numbering)
    fields[CalendarField.Quarter]?.let { quarter ->
        val authoredLabelYear = fields[CalendarField.Year] ?: return null
        val labelAstronomical = toAstronomicalYear(authoredLabelYear, spec.numbering) ?: return null
        val fiscalStartYear = if (
            spec.quarterYearLabel == QuarterYearLabel.End && spec.quarterStartMonth != 1
        ) labelAstronomical - 1 else labelAstronomical
        val monthOffset = (quarter.toInt() - 1) * 3
        val startMonthIndex = spec.quarterStartMonth - 1 + monthOffset
        val startYear = fiscalStartYear + startMonthIndex / 12
        val startMonth = startMonthIndex % 12 + 1
        val endMonthIndex = startMonth - 1 + 3
        val endYear = startYear + endMonthIndex / 12
        val endMonth = endMonthIndex % 12 + 1
        val startAuthored = fromAstronomicalYear(startYear, spec.numbering) ?: return null
        val endAuthored = fromAstronomicalYear(endYear, spec.numbering) ?: return null
        val start = calendarToDayIndex(TemporalCoordinate.CalendarDate(startAuthored, startMonth, 1), calendar) ?: return null
        val end = calendarToDayIndex(TemporalCoordinate.CalendarDate(endAuthored, endMonth, 1), calendar) ?: return null
        return TemporalSelection.Period(TemporalAxisPeriod(start, end))
    }
    if (CalendarField.WeekYear in fields || CalendarField.Week in fields) {
        val authoredWeekYear = fields[CalendarField.WeekYear] ?: return null
        val astronomicalWeekYear = toAstronomicalYear(authoredWeekYear, spec.numbering) ?: return null
        val week = fields[CalendarField.Week]?.toInt()
        val weekOne = isoWeekOneMonday(astronomicalWeekYear, spec.numbering) ?: return null
        val nextWeekOne = isoWeekOneMonday(astronomicalWeekYear + 1, spec.numbering) ?: return null
        if (week == null) return TemporalSelection.Period(TemporalAxisPeriod(weekOne, nextWeekOne))
        val start = weekOne + ExactRational.of((week - 1L) * 7)
        val end = start + ExactRational.of(7)
        if (end > nextWeekOne) return null
        return TemporalSelection.Period(TemporalAxisPeriod(start, end))
    }
    val year = fields[CalendarField.Year] ?: return null
    val astronomicalYear = toAstronomicalYear(year, spec.numbering) ?: return null
    val month = fields[CalendarField.Month]?.toInt()
    val day = fields[CalendarField.Day]?.toInt()
    if (month != null && day != null) {
        val value = calendarToDayIndex(TemporalCoordinate.CalendarDate(year, month, day), calendar) ?: return null
        return TemporalSelection.Instant(value)
    }
    if (month != null) {
        val start = calendarToDayIndex(TemporalCoordinate.CalendarDate(year, month, 1), calendar) ?: return null
        val nextAstronomicalYear = if (month == 12) astronomicalYear + 1 else astronomicalYear
        val nextMonth = if (month == 12) 1 else month + 1
        val nextYear = fromAstronomicalYear(nextAstronomicalYear, spec.numbering) ?: return null
        val end = calendarToDayIndex(TemporalCoordinate.CalendarDate(nextYear, nextMonth, 1), calendar) ?: return null
        return TemporalSelection.Period(TemporalAxisPeriod(start, end))
    }
    val start = calendarToDayIndex(TemporalCoordinate.CalendarDate(year, 1, 1), calendar) ?: return null
    val nextYear = fromAstronomicalYear(astronomicalYear + 1, spec.numbering) ?: return null
    val end = calendarToDayIndex(TemporalCoordinate.CalendarDate(nextYear, 1, 1), calendar) ?: return null
    return TemporalSelection.Period(TemporalAxisPeriod(start, end))
}

private fun isoWeekOneMonday(
    astronomicalWeekYear: Long,
    numbering: YearNumbering,
): ExactRational? {
    val authoredYear = fromAstronomicalYear(astronomicalWeekYear, numbering) ?: return null
    val calendar = TemporalCoordinateSpec.Calendar(CalendarKind.Gregorian, numbering)
    val januaryFourth = calendarToDayIndex(TemporalCoordinate.CalendarDate(authoredYear, 1, 4), calendar) ?: return null
    val weekday = floorMod(januaryFourth.numerator, 7).toInt() + 1
    return januaryFourth - ExactRational.of((weekday - 1).toLong())
}

private fun eraToCalendarDate(
    coordinate: TemporalCoordinate.EraDate,
    spec: TemporalCoordinateSpec.Era,
    calendar: TemporalCoordinateSpec.Calendar,
): TemporalCoordinate.CalendarDate? {
    val periodIndex = spec.periods.indexOfFirst { coordinate.era == it.name || coordinate.era in it.aliases }
    if (periodIndex < 0) return null
    val period = spec.periods[periodIndex]
    val since = parseCalendarDate(period.since) ?: return null
    val year = since.year + (coordinate.year - period.firstYear)
    val result = TemporalCoordinate.CalendarDate(year, coordinate.month, coordinate.day)
    val resultIndex = calendarToDayIndex(result, calendar) ?: return null
    val sinceIndex = calendarToDayIndex(since, calendar) ?: return null
    if (resultIndex < sinceIndex) return null
    val next = spec.periods.getOrNull(periodIndex + 1)?.since?.let(::parseCalendarDate)
    val nextIndex = next?.let { calendarToDayIndex(it, calendar) }
    if (nextIndex != null && resultIndex >= nextIndex) return null
    return result
}

private fun calendarToEraDate(
    coordinate: TemporalCoordinate.CalendarDate,
    spec: TemporalCoordinateSpec.Era,
    calendar: TemporalCoordinateSpec.Calendar,
): TemporalCoordinate.EraDate? {
    val value = calendarToDayIndex(coordinate, calendar) ?: return null
    val period = spec.periods.mapNotNull { it to (parseCalendarDate(it.since) ?: return@mapNotNull null) }
        .filter { (_, since) -> calendarToDayIndex(since, calendar)?.let { it <= value } == true }
        .maxByOrNull { (_, since) -> calendarToDayIndex(since, calendar)!! }
        ?: return null
    return TemporalCoordinate.EraDate(
        period.first.name,
        period.first.firstYear + coordinate.year - period.second.year,
        coordinate.month,
        coordinate.day,
    )
}

private fun timecodeToFrame(
    coordinate: TemporalCoordinate.Timecode,
    spec: TemporalCoordinateSpec.Timecode,
): Long {
    require(coordinate.hours >= 0)
    require(spec.wrapHours == null || coordinate.hours < spec.wrapHours)
    require(coordinate.minutes in 0..59 && coordinate.seconds in 0..59)
    require(coordinate.frames in 0 until spec.nominalFps)
    val totalMinutes = coordinate.hours.toLong() * 60 + coordinate.minutes
    val nominalFrames = ((coordinate.hours.toLong() * 3600 + coordinate.minutes * 60L + coordinate.seconds) * spec.nominalFps) + coordinate.frames
    if (!spec.dropFrame) return nominalFrames
    val drop = dropFrameCount(spec)
    require(!(coordinate.seconds == 0 && coordinate.minutes % 10 != 0 && coordinate.frames < drop)) {
        "Skipped drop-frame label"
    }
    return nominalFrames - drop.toLong() * (totalMinutes - totalMinutes / 10)
}

private fun frameToTimecode(frame: Long, spec: TemporalCoordinateSpec.Timecode): TemporalCoordinate.Timecode? {
    if (frame < 0) return null
    if (!spec.dropFrame) {
        val totalSeconds = frame / spec.nominalFps
        val frames = (frame % spec.nominalFps).toInt()
        val totalHours = totalSeconds / 3600
        if (totalHours > Int.MAX_VALUE || spec.wrapHours != null && totalHours >= spec.wrapHours.toLong()) return null
        val hours = totalHours.toInt()
        return TemporalCoordinate.Timecode(hours, ((totalSeconds / 60) % 60).toInt(), (totalSeconds % 60).toInt(), frames)
    }
    val drop = dropFrameCount(spec)
    val framesPer10Minutes = spec.nominalFps * 60L * 10 - drop * 9L
    val tenMinuteChunks = frame / framesPer10Minutes
    val remainder = frame % framesPer10Minutes
    val framesPerMinuteAfterFirst = spec.nominalFps * 60L - drop
    val additionalMinutes = if (remainder < spec.nominalFps * 60L) 0 else 1 + ((remainder - spec.nominalFps * 60L) / framesPerMinuteAfterFirst).toInt()
    val droppedLabels = drop.toLong() * (tenMinuteChunks * 9 + additionalMinutes.coerceAtMost(9))
    val labelFrames = frame + droppedLabels
    val totalSeconds = labelFrames / spec.nominalFps
    val totalHours = totalSeconds / 3600
    if (totalHours > Int.MAX_VALUE || spec.wrapHours != null && totalHours >= spec.wrapHours.toLong()) return null
    val hours = totalHours.toInt()
    return TemporalCoordinate.Timecode(hours, ((totalSeconds / 60) % 60).toInt(), (totalSeconds % 60).toInt(), (labelFrames % spec.nominalFps).toInt())
}

private fun dropFrameCount(spec: TemporalCoordinateSpec.Timecode): Int = when (spec.nominalFps) {
    30 -> 2
    60 -> 4
    else -> error("drop-frame requires nominalFps 30 or 60")
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    val remainder = value % divisor
    return if (remainder != 0L && (remainder < 0) != (divisor < 0)) quotient - 1 else quotient
}

private fun floorMod(value: Long, divisor: Long): Long = value - floorDiv(value, divisor) * divisor
