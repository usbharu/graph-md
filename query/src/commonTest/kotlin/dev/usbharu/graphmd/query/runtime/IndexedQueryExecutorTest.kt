package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.core.model.NumberValue
import dev.usbharu.graphmd.core.model.IntegerValue
import dev.usbharu.graphmd.core.model.SourceInfo
import dev.usbharu.graphmd.core.model.StringValue
import dev.usbharu.graphmd.query.index.SearchIndexBuilder
import dev.usbharu.graphmd.query.indexedFixtureGraph
import dev.usbharu.graphmd.query.ir.AssertionOwner
import dev.usbharu.graphmd.query.ir.PropertyAssertion
import dev.usbharu.graphmd.query.model.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndexedQueryExecutorTest {
    private val graph = indexedFixtureGraph()
    private val scan = ScanQueryExecutor()
    private val indexed = IndexedQueryExecutor(SearchIndexBuilder().build(graph))

    @Test
    fun `indexed execution exactly matches scan semantics across query shapes`() {
        val queries = listOf(
            GraphQuery(
                expression = GraphQueryExpression.Property(
                    PropertyPredicate(PropertyPath("name"), ValueOperator.EQUALS, StringValue("Alice")),
                ),
            ),
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                temporalWindow = TemporalWindow.Range(TimelineId("TimelineA"), 50.0, 100.0),
                temporalOperator = TemporalOperator.QUERY_CONTAINS_ASSERTION,
                expression = GraphQueryExpression.Property(
                    PropertyPredicate(PropertyPath("name"), ValueOperator.EQUALS, StringValue("Alice")),
                ),
            ),
            GraphQuery(
                root = NodePattern(id = NodeId("bob")),
                temporalWindow = TemporalWindow.At(TimelineId("TimelineA"), 110.0),
                expression = GraphQueryExpression.Property(
                    PropertyPredicate(PropertyPath("age"), ValueOperator.GREATER_THAN, NumberValue(15.0)),
                ),
            ),
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                temporalWindow = TemporalWindow.ClosedRange(TimelineId("TimelineA"), 0.0, 200.0),
                expression = GraphQueryExpression.Relation(
                    RelationPattern(
                        typeId = RelationTypeId("friendOf"),
                        targetVariable = VariableId("friend"),
                        targetExpression = GraphQueryExpression.Property(
                            PropertyPredicate(
                                PropertyPath("age"),
                                ValueOperator.GREATER_THAN,
                                NumberValue(15.0),
                            ),
                        ),
                    ),
                ),
            ),
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                expression = GraphQueryExpression.Text(
                    TextPredicate("勇者", TextMatchMode.ALL_TERMS),
                ),
            ),
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                expression = GraphQueryExpression.Text(TextPredicate("raphM")),
            ),
            GraphQuery(
                root = NodePattern(id = NodeId("bob")),
                temporalWindow = TemporalWindow.Range(TimelineId("TimelineA"), 0.0, 201.0),
                expression = GraphQueryExpression.Not(
                    GraphQueryExpression.Property(
                        PropertyPredicate(
                            PropertyPath("age"),
                            ValueOperator.GREATER_THAN_OR_EQUALS,
                            NumberValue(20.0),
                        ),
                    ),
                ),
            ),
            GraphQuery(
                root = NodePattern(typeId = NodeTypeId("Person")),
                expression = GraphQueryExpression.Or(
                    listOf(
                        GraphQueryExpression.Property(
                            PropertyPredicate(PropertyPath("name"), ValueOperator.EQUALS, StringValue("Alice")),
                        ),
                        GraphQueryExpression.Property(
                            PropertyPredicate(PropertyPath("age"), ValueOperator.EQUALS, NumberValue(20.0)),
                        ),
                    ),
                ),
            ),
        )

        queries.forEach { query ->
            val expected = runIndexedSuspend { scan.execute(graph, query) }
            val actual = runIndexedSuspend { indexed.execute(graph, query) }
            assertEquals(expected, actual, "Parity failed for $query")
        }
    }

    @Test
    fun `hundreds of temporal property plans retain differential parity`() {
        val operators = ValueOperator.entries - ValueOperator.CONTAINS
        val thresholds = listOf(0.0, 10.0, 15.0, 20.0, 25.0)
        val instants = (0..200 step 10).map(Int::toDouble)
        var compared = 0

        operators.forEach { operator ->
            thresholds.forEach { threshold ->
                instants.forEach { instant ->
                    val query = GraphQuery(
                        root = NodePattern(id = NodeId("bob")),
                        temporalWindow = TemporalWindow.At(TimelineId("TimelineA"), instant),
                        expression = GraphQueryExpression.Property(
                            PropertyPredicate(
                                PropertyPath("age"),
                                operator,
                                NumberValue(threshold),
                            ),
                        ),
                    )
                    val expected = runIndexedSuspend { scan.execute(graph, query) }
                    val actual = runIndexedSuspend { indexed.execute(graph, query) }
                    assertEquals(expected, actual, "Parity failed for $query")
                    compared++
                }
            }
        }

        assertEquals(630, compared)
    }

    @Test
    fun `NOT without a temporal window preserves times outside a finite assertion`() {
        val timelessGraph = graph.copy(
            nodes = graph.nodes.map { node ->
                if (node.id == NodeId("bob")) node.copy(validTime = IntervalSet.universal()) else node
            },
        )
        val query = GraphQuery(
            root = NodePattern(id = NodeId("bob")),
            expression = GraphQueryExpression.Not(
                GraphQueryExpression.Property(
                    PropertyPredicate(
                        PropertyPath("age"),
                        ValueOperator.GREATER_THAN_OR_EQUALS,
                        NumberValue(20.0),
                    ),
                ),
            ),
        )

        val expected = runIndexedSuspend { scan.execute(timelessGraph, query) }
        val actual = runIndexedSuspend {
            IndexedQueryExecutor(SearchIndexBuilder().build(timelessGraph)).execute(query)
        }

        assertEquals(expected, actual)
        val validTime = actual.matches.single().binding.validTime
        assertTrue(validTime.contains(TimelineId("TimelineA"), 99.0))
        assertFalse(validTime.contains(TimelineId("TimelineA"), 100.0))
        assertFalse(validTime.contains(TimelineId("TimelineA"), 200.0))
        assertTrue(validTime.contains(TimelineId("TimelineA"), 201.0))
    }

    @Test
    fun `adjacent integers above double precision remain distinct for exact and range queries`() {
        val lower = 9_007_199_254_740_992L
        val upper = 9_007_199_254_740_993L
        val source = SourceInfo("/graph/large-integers.md")
        val integerGraph = graph.copy(
            nodes = graph.nodes.map { it.copy(validTime = IntervalSet.universal()) },
            propertyAssertions = listOf(
                PropertyAssertion(
                    id = AssertionId(10),
                    stableKey = StableAssertionKey("alice:sequence"),
                    owner = AssertionOwner.Node(NodeId("alice")),
                    propertyId = PropertyId("sequence"),
                    path = PropertyPath("sequence"),
                    value = IntegerValue(lower),
                    validTime = IntervalSet.universal(),
                    source = source,
                ),
                PropertyAssertion(
                    id = AssertionId(11),
                    stableKey = StableAssertionKey("bob:sequence"),
                    owner = AssertionOwner.Node(NodeId("bob")),
                    propertyId = PropertyId("sequence"),
                    path = PropertyPath("sequence"),
                    value = IntegerValue(upper),
                    validTime = IntervalSet.universal(),
                    source = source,
                ),
            ),
            relationAssertions = emptyList(),
            textAssertions = emptyList(),
        )
        val exact = GraphQuery(
            expression = GraphQueryExpression.Property(
                PropertyPredicate(PropertyPath("sequence"), ValueOperator.EQUALS, IntegerValue(lower)),
            ),
        )
        val greater = GraphQuery(
            expression = GraphQueryExpression.Property(
                PropertyPredicate(PropertyPath("sequence"), ValueOperator.GREATER_THAN, IntegerValue(lower)),
            ),
        )
        val integerIndex = SearchIndexBuilder().build(integerGraph)
        val integerExecutor = IndexedQueryExecutor(integerIndex)

        assertEquals(
            listOf(NodeId("alice")),
            runIndexedSuspend { integerExecutor.execute(exact) }.matches.map { it.nodeId },
        )
        assertEquals(
            listOf(NodeId("bob")),
            runIndexedSuspend { integerExecutor.execute(greater) }.matches.map { it.nodeId },
        )
        assertEquals(
            runIndexedSuspend { scan.execute(integerGraph, exact) },
            runIndexedSuspend { integerExecutor.execute(exact) },
        )
        assertEquals(
            runIndexedSuspend { scan.execute(integerGraph, greater) },
            runIndexedSuspend { integerExecutor.execute(greater) },
        )
    }
}

private fun <T> runIndexedSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome).getOrThrow()
}
