package dev.usbharu.graphmd.core.model

sealed interface RawValue

data class RawString(val value: String) : RawValue
data class RawInteger(val value: Long) : RawValue
data class RawNumber(val value: Double) : RawValue
data class RawBoolean(val value: Boolean) : RawValue
data object RawNull : RawValue
data class RawArray(val values: List<RawValue>) : RawValue
data class RawObject(val values: Map<String, RawValue>) : RawValue
