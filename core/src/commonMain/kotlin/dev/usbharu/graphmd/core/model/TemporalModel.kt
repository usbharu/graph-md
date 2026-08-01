package dev.usbharu.graphmd.core.model

/**
 * An exact, normalized rational used only by the temporal model.
 *
 * Graph property `number` values intentionally remain [Double]. Keeping the
 * exact type scoped to temporal coordinates avoids silently changing normal
 * GraphMD numeric semantics.
 */
@ConsistentCopyVisibility
data class ExactRational private constructor(
    val numerator: Long,
    val denominator: Long,
) : Comparable<ExactRational> {
    init {
        require(denominator > 0) { "denominator must be positive" }
        require(gcdMagnitude(numerator, denominator) == 1uL) { "rational must be reduced" }
    }

    operator fun unaryMinus(): ExactRational {
        require(numerator != Long.MIN_VALUE) { "temporal rational overflow" }
        return of(-numerator, denominator)
    }

    operator fun plus(other: ExactRational): ExactRational {
        val common = gcdMagnitude(denominator, other.denominator).toLong()
        val leftMultiplier = other.denominator / common
        val rightMultiplier = denominator / common
        val numerator = checkedAdd(
            checkedMultiply(this.numerator, leftMultiplier),
            checkedMultiply(other.numerator, rightMultiplier),
        )
        return of(numerator, checkedMultiply(denominator, leftMultiplier))
    }

    operator fun minus(other: ExactRational): ExactRational = this + -other

    operator fun times(other: ExactRational): ExactRational {
        val leftCancellation = gcdMagnitude(numerator, other.denominator).toLong()
        val rightCancellation = gcdMagnitude(other.numerator, denominator).toLong()
        return of(
            checkedMultiply(numerator / leftCancellation, other.numerator / rightCancellation),
            checkedMultiply(denominator / rightCancellation, other.denominator / leftCancellation),
        )
    }

    operator fun div(other: ExactRational): ExactRational {
        require(other.numerator != 0L) { "division by zero" }
        require(other.numerator != Long.MIN_VALUE) { "temporal rational overflow" }
        val sign = if (other.numerator < 0) -1 else 1
        return this * of(sign.toLong() * other.denominator, magnitude(other.numerator).toLong())
    }

    override fun compareTo(other: ExactRational): Int {
        if (numerator == other.numerator && denominator == other.denominator) return 0
        if (numerator < 0 && other.numerator >= 0) return -1
        if (numerator >= 0 && other.numerator < 0) return 1
        val sign = if (numerator < 0) -1 else 1
        return sign * comparePositiveFractions(
            magnitude(numerator), denominator.toULong(),
            magnitude(other.numerator), other.denominator.toULong(),
        )
    }

    fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()

    override fun toString(): String = if (denominator == 1L) numerator.toString() else "$numerator/$denominator"

    companion object {
        val ZERO: ExactRational = ExactRational(0, 1)
        val ONE: ExactRational = ExactRational(1, 1)

        fun of(value: Long): ExactRational = if (value == 0L) ZERO else ExactRational(value, 1)

        fun of(numerator: Long, denominator: Long): ExactRational {
            require(denominator != 0L) { "denominator must not be zero" }
            require(denominator != Long.MIN_VALUE) { "temporal rational overflow" }
            if (numerator == 0L) return ZERO
            val normalizedNumerator: Long
            val normalizedDenominator: Long
            if (denominator < 0) {
                require(numerator != Long.MIN_VALUE) { "temporal rational overflow" }
                normalizedNumerator = -numerator
                normalizedDenominator = -denominator
            } else {
                normalizedNumerator = numerator
                normalizedDenominator = denominator
            }
            val divisor = gcdMagnitude(normalizedNumerator, normalizedDenominator).toLong()
            return ExactRational(normalizedNumerator / divisor, normalizedDenominator / divisor)
        }

        /** Parses integers, finite decimal literals, and `numerator/denominator`. */
        fun parse(raw: String): ExactRational {
            val value = raw.trim()
            require(value.isNotEmpty()) { "temporal rational must not be empty" }
            val slash = value.indexOf('/')
            if (slash >= 0) {
                require(slash == value.lastIndexOf('/')) { "invalid temporal rational: $raw" }
                return of(
                    value.substring(0, slash).trim().toLong(),
                    value.substring(slash + 1).trim().toLong(),
                )
            }
            val dot = value.indexOf('.')
            if (dot < 0) return of(value.toLong())
            require(dot == value.lastIndexOf('.')) { "invalid temporal decimal: $raw" }
            val negative = value.startsWith('-')
            val positive = value.removePrefix("+").removePrefix("-")
            val positiveDot = positive.indexOf('.')
            require(positiveDot >= 0) { "invalid temporal decimal: $raw" }
            val whole = positive.substring(0, positiveDot).ifEmpty { "0" }
            val fraction = positive.substring(positiveDot + 1)
            require(whole.all(Char::isDigit) && fraction.isNotEmpty() && fraction.all(Char::isDigit)) {
                "invalid temporal decimal: $raw"
            }
            var denominator = 1L
            repeat(fraction.length) { denominator = checkedMultiply(denominator, 10) }
            val absoluteNumerator = checkedAdd(checkedMultiply(whole.toLong(), denominator), fraction.toLong())
            val numerator = if (negative) {
                require(absoluteNumerator != Long.MIN_VALUE) { "temporal rational overflow" }
                -absoluteNumerator
            } else {
                absoluteNumerator
            }
            return of(numerator, denominator)
        }

        fun fromDouble(value: Double): ExactRational {
            require(value.isFinite()) { "temporal coordinate must be finite" }
            return parse(value.toString())
        }
    }
}

private fun magnitude(value: Long): ULong =
    if (value >= 0) value.toULong() else (-(value + 1)).toULong() + 1u

private fun gcdMagnitude(left: Long, right: Long): ULong = gcd(magnitude(left), magnitude(right))

private fun gcd(left: ULong, right: ULong): ULong {
    var a = left
    var b = right
    while (b != 0uL) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return a
}

private fun checkedAdd(left: Long, right: Long): Long {
    val result = left + right
    if (((left xor result) and (right xor result)) < 0) error("temporal rational overflow")
    return result
}

private fun checkedMultiply(left: Long, right: Long): Long {
    if (left == 0L || right == 0L) return 0L
    if (left == -1L && right == Long.MIN_VALUE || right == -1L && left == Long.MIN_VALUE) {
        error("temporal rational overflow")
    }
    val result = left * right
    if (result / right != left) error("temporal rational overflow")
    return result
}

/** Compares two non-negative fractions without cross-multiplication overflow. */
private fun comparePositiveFractions(
    leftNumerator: ULong,
    leftDenominator: ULong,
    rightNumerator: ULong,
    rightDenominator: ULong,
): Int {
    var a = leftNumerator
    var b = leftDenominator
    var c = rightNumerator
    var d = rightDenominator
    var direction = 1
    while (true) {
        val leftWhole = a / b
        val rightWhole = c / d
        if (leftWhole != rightWhole) return direction * leftWhole.compareTo(rightWhole)
        val leftRemainder = a % b
        val rightRemainder = c % d
        if (leftRemainder == 0uL || rightRemainder == 0uL) {
            return direction * leftRemainder.compareTo(rightRemainder)
        }
        a = b
        b = leftRemainder
        c = d
        d = rightRemainder
        direction = -direction
    }
}

sealed interface TemporalCoordinate {
    data class Rational(val value: ExactRational) : TemporalCoordinate
    data class CalendarDate(val year: Long, val month: Int, val day: Int) : TemporalCoordinate
    data class EraDate(val era: String, val year: Long, val month: Int, val day: Int) : TemporalCoordinate
    data class FrameIndex(val value: Long) : TemporalCoordinate
    data class Timecode(val hours: Int, val minutes: Int, val seconds: Int, val frames: Int) : TemporalCoordinate
    data class Label(val value: String) : TemporalCoordinate
}

sealed interface TemporalCoordinateSpec {
    data object Number : TemporalCoordinateSpec
    data class Calendar(
        val calendar: CalendarKind,
        val numbering: YearNumbering = YearNumbering.CommonEra,
    ) : TemporalCoordinateSpec
    data class Frame(val start: Long = 0) : TemporalCoordinateSpec
    data class Timecode(
        val actualFps: ExactRational,
        val nominalFps: Int,
        val dropFrame: Boolean,
        val wrapHours: Int? = null,
    ) : TemporalCoordinateSpec
    data class Era(val periods: List<EraPeriodSpec>) : TemporalCoordinateSpec
}

enum class CalendarKind { Gregorian, Julian }

sealed interface YearNumbering {
    data object CommonEra : YearNumbering
    data object Astronomical : YearNumbering
    data class Offset(val offset: Long, val yearZero: Boolean = false) : YearNumbering
}

data class EraPeriodSpec(
    val name: String,
    val aliases: List<String> = emptyList(),
    val since: String,
    val firstYear: Long = 1,
)

enum class AxisLineageKind { Fork, Simulation, Recording, Edit, Resample, Copy, Derived }

data class DerivedFromSpec(
    val timeline: String,
    val kind: AxisLineageKind = AxisLineageKind.Derived,
    val sourceAt: TemporalCoordinate? = null,
    val origin: TemporalCoordinate? = null,
    val metadata: Map<String, RawValue> = emptyMap(),
)

data class AxisLineage(
    val sourceAxisId: String,
    val derivedAxisId: String,
    val kind: AxisLineageKind,
    val sourceTimelineId: String,
    val sourceAt: TemporalCoordinate? = null,
    val origin: TemporalCoordinate? = null,
    val metadata: Map<String, RawValue> = emptyMap(),
)

data class TemporalDomain(val id: String)

data class TemporalAxis(
    val id: String,
    val domainId: String,
    val unit: TemporalAxisUnit,
    val lineage: AxisLineage? = null,
)

enum class TemporalAxisUnit { Tick, Day, Frame }

data class TemporalCoordinateSystem(
    val id: String,
    val axisId: String,
    val domainId: String,
    val coordinate: TemporalCoordinateSpec,
    val scaleToParent: ExactRational = ExactRational.ONE,
    val offsetFromParent: ExactRational = ExactRational.ZERO,
    val parentTimelineId: String? = null,
    val aliases: List<String> = emptyList(),
)

enum class TemporalMappingKind { Coercion, Isomorphism, Embedding, Projection, Alignment, Correspondence }
enum class TemporalPrecisionKind { Exact, Approximate, Uncertain }
enum class TemporalCardinality { OneToOne, OneToMany, ManyToOne, ManyToMany }
enum class TemporalTotality { Total, Partial }
enum class TemporalOrderBehavior { StrictlyIncreasing, StrictlyDecreasing, Monotonic, NonMonotonic }
enum class TemporalInvertibility { Invertible, ConditionallyInvertible, NonInvertible }
enum class TemporalContinuity { Continuous, Piecewise, Discrete }

data class TemporalMappingTraits(
    val cardinality: TemporalCardinality,
    val totality: TemporalTotality,
    val orderBehavior: TemporalOrderBehavior,
    val invertibility: TemporalInvertibility,
    val continuity: TemporalContinuity,
)

data class TemporalMappingTraitsOverride(
    val cardinality: TemporalCardinality? = null,
    val totality: TemporalTotality? = null,
    val orderBehavior: TemporalOrderBehavior? = null,
    val invertibility: TemporalInvertibility? = null,
    val continuity: TemporalContinuity? = null,
)

data class TemporalPrecision(
    val kind: TemporalPrecisionKind = TemporalPrecisionKind.Exact,
    val error: ExactRational? = null,
)

data class TemporalCoordinateRange(
    val from: TemporalCoordinate? = null,
    val to: TemporalCoordinate? = null,
)

data class TemporalMappingPair(
    val from: TemporalCoordinate,
    val to: List<TemporalCoordinate>,
)

data class TemporalMappingSegment(
    val source: TemporalCoordinateRange? = null,
    val target: TemporalCoordinateRange? = null,
    val scale: ExactRational = ExactRational.ONE,
    val offset: ExactRational = ExactRational.ZERO,
    val pairs: List<TemporalMappingPair> = emptyList(),
)

data class TemporalMappingSpec(
    val id: String? = null,
    val timeline: String,
    val kind: TemporalMappingKind = TemporalMappingKind.Isomorphism,
    val precision: TemporalPrecision = TemporalPrecision(),
    val scale: ExactRational = ExactRational.ONE,
    val offset: ExactRational = ExactRational.ZERO,
    val range: TemporalCoordinateRange? = null,
    val segments: List<TemporalMappingSegment> = emptyList(),
    val pairs: List<TemporalMappingPair> = emptyList(),
    val traits: TemporalMappingTraitsOverride? = null,
    val requiredContext: List<String> = emptyList(),
    val provenance: Map<String, RawValue> = emptyMap(),
)

data class TemporalMappingInstance(
    val id: String,
    val sourceTimelineId: String,
    val targetTimelineId: String,
    val sourceAxisId: String,
    val targetAxisId: String,
    val kind: TemporalMappingKind,
    val precision: TemporalPrecision,
    val scale: ExactRational,
    val offset: ExactRational,
    val range: TemporalCoordinateRange?,
    val segments: List<TemporalMappingSegment>,
    val pairs: List<TemporalMappingPair>,
    val traits: TemporalMappingTraits,
    val requiredContext: List<String>,
    val provenance: Map<String, RawValue>,
)

/** Public normalized-kernel names used by the temporal API specification. */
typealias MappingDefinition = TemporalMappingSpec
typealias MappingInstance = TemporalMappingInstance
typealias MappingSegment = TemporalMappingSegment

data class TemporalModel(
    val domains: List<TemporalDomain> = emptyList(),
    val axes: List<TemporalAxis> = emptyList(),
    val coordinateSystems: List<TemporalCoordinateSystem> = emptyList(),
    val mappings: List<TemporalMappingInstance> = emptyList(),
) {
    val domainById: Map<String, TemporalDomain> = domains.associateBy { it.id }
    val axisById: Map<String, TemporalAxis> = axes.associateBy { it.id }
    val coordinateSystemById: Map<String, TemporalCoordinateSystem> = coordinateSystems.associateBy { it.id }
}

sealed interface TemporalConversionResult {
    data class Exact(val value: TemporalValue) : TemporalConversionResult
    data class Alternatives(val values: List<TemporalValue>) : TemporalConversionResult
    data class Range(val from: TemporalValue, val to: TemporalValue) : TemporalConversionResult
    data class Approximate(val value: TemporalValue, val error: ExactRational?) : TemporalConversionResult
    data class Unmappable(val reason: String) : TemporalConversionResult
}

data class TemporalValue(
    val timeline: String,
    val coordinate: TemporalCoordinate,
)

enum class TemporalOrder { Before, Equal, After }

sealed interface TemporalComparisonResult {
    data class Ordered(val order: TemporalOrder) : TemporalComparisonResult
    data object Overlapping : TemporalComparisonResult
    data class Ambiguous(val alternatives: List<TemporalOrder>) : TemporalComparisonResult
    data class Approximate(val order: TemporalOrder?, val error: ExactRational?) : TemporalComparisonResult
    data class Unmappable(val reason: String) : TemporalComparisonResult
    data object Unrelated : TemporalComparisonResult
}
