package dev.usbharu.graphmd.query

import dev.usbharu.graphmd.core.model.DocumentKind
import dev.usbharu.graphmd.core.model.NumberValue
import dev.usbharu.graphmd.core.model.SourceInfo
import dev.usbharu.graphmd.core.model.StringValue
import dev.usbharu.graphmd.query.ir.*
import dev.usbharu.graphmd.query.model.*

internal val testTimeline = TimelineId("TimelineA")

internal fun indexedFixtureGraph(): QueryableGraph {
    val alice = NodeId("alice")
    val bob = NodeId("bob")
    val person = NodeTypeId("Person")
    val source = SourceInfo("/graph/fixture.md")
    val graphTime = testInterval(0.0, 200.0)
    val aliceName = PropertyAssertion(
        id = AssertionId(0),
        stableKey = StableAssertionKey("alice:name"),
        owner = AssertionOwner.Node(alice),
        propertyId = PropertyId("name"),
        path = PropertyPath("name"),
        value = StringValue("Alice"),
        validTime = IntervalSet.universal(),
        source = source,
    )
    val youngAge = PropertyAssertion(
        id = AssertionId(1),
        stableKey = StableAssertionKey("bob:age:young"),
        owner = AssertionOwner.Node(bob),
        propertyId = PropertyId("age"),
        path = PropertyPath("age"),
        value = NumberValue(10.0),
        validTime = testInterval(0.0, 99.0),
        source = source,
    )
    val adultAge = PropertyAssertion(
        id = AssertionId(2),
        stableKey = StableAssertionKey("bob:age:adult"),
        owner = AssertionOwner.Node(bob),
        propertyId = PropertyId("age"),
        path = PropertyPath("age"),
        value = NumberValue(20.0),
        validTime = testInterval(100.0, 200.0),
        source = source,
    )
    val relation = RelationAssertion(
        id = AssertionId(3),
        stableKey = StableAssertionKey("alice:friend:bob"),
        sourceNodeId = alice,
        targetNodeId = bob,
        relTypeId = RelationTypeId("friendOf"),
        ancestorRelTypeIds = emptySet(),
        properties = emptyList(),
        label = "Bob",
        validTime = testInterval(50.0, 120.0),
        source = source,
    )
    val body = TextAssertion(
        id = AssertionId(4),
        stableKey = StableAssertionKey("alice:body"),
        owner = AssertionOwner.Node(alice),
        kind = TextKind.PARAGRAPH,
        text = "GraphMDの勇者 Alice",
        validTime = graphTime,
        source = source,
    )
    val label = TextAssertion(
        id = AssertionId(5),
        stableKey = StableAssertionKey("relation:label"),
        owner = AssertionOwner.Relation(relation.id),
        kind = TextKind.RELATION_LABEL,
        text = "Bob",
        validTime = relation.validTime,
        source = source,
    )
    return QueryableGraph(
        nodes = listOf(
            QueryNode(alice, person, emptySet(), DocumentKind.Node, null, graphTime, source),
            QueryNode(bob, person, emptySet(), DocumentKind.Node, null, graphTime, source),
        ),
        propertyAssertions = listOf(aliceName, youngAge, adultAge),
        relationAssertions = listOf(relation),
        textAssertions = listOf(body, label),
        timelines = listOf(QueryTimeline(testTimeline, testTimeline, 0.0)),
        nodeTypeIds = setOf(person),
        relationTypeIds = setOf(RelationTypeId("friendOf")),
    )
}

internal fun testInterval(start: Double, endInclusive: Double): IntervalSet =
    IntervalSet.of(
        TemporalInterval(
            testTimeline,
            IntervalBoundary(start, inclusive = true),
            IntervalBoundary(endInclusive, inclusive = true),
        ),
    )
