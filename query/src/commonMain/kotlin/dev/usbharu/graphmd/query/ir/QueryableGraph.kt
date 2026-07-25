package dev.usbharu.graphmd.query.ir

import dev.usbharu.graphmd.core.model.DocumentKind
import dev.usbharu.graphmd.core.model.NormalizedValue
import dev.usbharu.graphmd.core.model.SourceInfo
import dev.usbharu.graphmd.core.model.SourceRange
import dev.usbharu.graphmd.query.model.*

data class QueryNode(
    val id: NodeId,
    val typeId: NodeTypeId,
    val ancestorTypeIds: Set<NodeTypeId>,
    val kind: DocumentKind,
    val url: String?,
    val validTime: IntervalSet,
    val source: SourceInfo,
)

sealed interface AssertionOwner {
    data class Node(val nodeId: NodeId) : AssertionOwner
    data class Relation(val relationAssertionId: AssertionId) : AssertionOwner
}

data class PropertyAssertion(
    val id: AssertionId,
    val stableKey: StableAssertionKey,
    val owner: AssertionOwner,
    val propertyId: PropertyId,
    val path: PropertyPath,
    val value: NormalizedValue,
    val validTime: IntervalSet,
    val source: SourceInfo,
)

data class RelationAssertion(
    val id: AssertionId,
    val stableKey: StableAssertionKey,
    val sourceNodeId: NodeId,
    val targetNodeId: NodeId,
    val relTypeId: RelationTypeId,
    val ancestorRelTypeIds: Set<RelationTypeId>,
    val properties: List<PropertyAssertion>,
    val label: String,
    val validTime: IntervalSet,
    val source: SourceInfo,
)

enum class TextKind {
    TITLE,
    HEADING,
    PARAGRAPH,
    PROPERTY_VALUE,
    RELATION_LABEL,
    CODE,
}

data class TextAssertion(
    val id: AssertionId,
    val stableKey: StableAssertionKey,
    val owner: AssertionOwner,
    val kind: TextKind,
    val text: String,
    val validTime: IntervalSet,
    val source: SourceInfo,
    val sourceRange: SourceRange? = source.range,
)

data class QueryableGraph(
    val nodes: List<QueryNode>,
    val propertyAssertions: List<PropertyAssertion>,
    val relationAssertions: List<RelationAssertion>,
    val textAssertions: List<TextAssertion>,
    val timelines: List<QueryTimeline>,
    val nodeTypeIds: Set<NodeTypeId>,
    val relationTypeIds: Set<RelationTypeId>,
) {
    val timelineCatalog: TimelineCatalog
        get() = TimelineCatalog.fromQueryTimelines(timelines)
}
