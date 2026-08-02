package dev.usbharu.graphmd.core.model

sealed interface RawValue

data class RawString(val value: String) : RawValue
data class RawInteger(val value: Long) : RawValue
data class RawNumber(val value: Double) : RawValue
data class RawBoolean(val value: Boolean) : RawValue
data object RawNull : RawValue
data class RawArray(val values: List<RawValue>) : RawValue
data class RawObject(val values: Map<String, RawValue>) : RawValue

internal sealed interface CanonicalRawValue
internal data class CanonicalString(val value: String) : CanonicalRawValue
internal data class CanonicalNumber(val value: Double) : CanonicalRawValue
internal data class CanonicalBoolean(val value: Boolean) : CanonicalRawValue
internal data object CanonicalNull : CanonicalRawValue
internal data class CanonicalArray(val values: List<CanonicalRawValue>) : CanonicalRawValue
internal data class CanonicalObject(val values: Map<String, CanonicalRawValue>) : CanonicalRawValue

internal sealed interface RawValidTimeKey
internal data object FallbackValidTimeKey : RawValidTimeKey
internal data class ValidTimeSetKey(val entries: Set<CanonicalRawValue>) : RawValidTimeKey
internal data class InvalidValidTimeKey(val value: CanonicalRawValue) : RawValidTimeKey

internal fun rawValidTimeKey(validTime: RawValue?): RawValidTimeKey = when (validTime) {
    null -> FallbackValidTimeKey
    is RawArray -> ValidTimeSetKey(validTime.values.mapTo(linkedSetOf(), ::canonicalRawValue))
    else -> InvalidValidTimeKey(canonicalRawValue(validTime))
}

private fun canonicalRawValue(value: RawValue): CanonicalRawValue = when (value) {
    is RawString -> CanonicalString(value.value)
    is RawInteger -> canonicalNumber(value.value.toDouble())
    is RawNumber -> canonicalNumber(value.value)
    is RawBoolean -> CanonicalBoolean(value.value)
    RawNull -> CanonicalNull
    is RawArray -> CanonicalArray(value.values.map(::canonicalRawValue))
    is RawObject -> CanonicalObject(value.values.mapValues { canonicalRawValue(it.value) })
}

private fun canonicalNumber(value: Double): CanonicalNumber =
    CanonicalNumber(if (value == 0.0) 0.0 else value)

internal fun rawValuesEqual(left: RawValue, right: RawValue): Boolean =
    canonicalRawValue(left) == canonicalRawValue(right)

internal fun rawValuesAreUnique(values: List<RawValue>): Boolean =
    values.indices.all { index ->
        values.subList(0, index).none { rawValuesEqual(it, values[index]) }
    }
