package dev.usbharu.graphmd.query.model

import kotlin.jvm.JvmInline

@JvmInline
value class AssertionId(val value: Int)

@JvmInline
value class StableAssertionKey(val value: String)

@JvmInline
value class NodeId(val value: String)

@JvmInline
value class NodeTypeId(val value: String)

@JvmInline
value class PropertyId(val value: String)

@JvmInline
value class RelationTypeId(val value: String)

@JvmInline
value class TimelineId(val value: String)

@JvmInline
value class VariableId(val value: String)

data class PropertyPath(
    val segments: List<String>,
) {
    init {
        require(segments.isNotEmpty()) { "A property path must not be empty" }
        require(segments.none(String::isEmpty)) { "Property path segments must not be empty" }
    }

    constructor(first: String, vararg rest: String) : this(listOf(first) + rest)

    val propertyId: PropertyId
        get() = PropertyId(segments.first())

    override fun toString(): String = segments.joinToString(".")
}
