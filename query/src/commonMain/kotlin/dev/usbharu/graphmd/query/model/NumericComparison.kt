package dev.usbharu.graphmd.query.model

private const val LONG_MAX_EXCLUSIVE_AS_DOUBLE = 9_223_372_036_854_775_808.0

internal fun compareLongToDouble(integer: Long, number: Double): Int {
    if (number.isNaN()) return -1
    if (number >= LONG_MAX_EXCLUSIVE_AS_DOUBLE) return -1
    if (number < Long.MIN_VALUE.toDouble()) return 1

    val truncated = number.toLong()
    val integerComparison = integer.compareTo(truncated)
    return if (integerComparison != 0) {
        integerComparison
    } else {
        truncated.toDouble().compareTo(number)
    }
}

internal fun Double.exactLongValueOrNull(): Long? {
    if (!isFinite() || this >= LONG_MAX_EXCLUSIVE_AS_DOUBLE || this < Long.MIN_VALUE.toDouble()) return null
    val integer = toLong()
    return integer.takeIf { integer.toDouble() == this }
}
