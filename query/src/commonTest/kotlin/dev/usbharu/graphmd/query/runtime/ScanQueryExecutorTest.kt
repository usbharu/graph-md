package dev.usbharu.graphmd.query.runtime

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.NumberValue
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.ir.QueryableGraph
import dev.usbharu.graphmd.query.ir.QueryableGraphBuilder
import dev.usbharu.graphmd.query.model.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.*

class ScanQueryExecutorTest {
    private val graph: QueryableGraph by lazy {
        val sources = fixture()
        QueryableGraphBuilder(sources).build(GraphCompiler().compileSources(sources))
    }
    private val executor = ScanQueryExecutor()

    @Test
    fun `relation and target property must be true at the same time`() {
        val query = GraphQuery(
            root = NodePattern(id = NodeId("alice")),
            temporalWindow = TemporalWindow.ClosedRange(TimelineId("TimelineA"), 100.0, 200.0),
            expression = GraphQueryExpression.Relation(
                RelationPattern(
                    typeId = RelationTypeId("friendOf"),
                    target = NodePattern(id = NodeId("bob")),
                    targetVariable = VariableId("friend"),
                    targetExpression = GraphQueryExpression.Property(
                        PropertyPredicate(
                            path = PropertyPath("age"),
                            operator = ValueOperator.GREATER_THAN_OR_EQUALS,
                            value = NumberValue(15.0),
                        ),
                    ),
                ),
            ),
        )

        val result = execute(query)

        assertTrue(result.isSuccess)
        assertTrue(
            result.matches.isEmpty(),
            "relations=${graph.relationAssertions}; ages=${graph.propertyAssertions.filter { it.path == PropertyPath("age") }}; result=$result",
        )
    }

    @Test
    fun `relation join narrows the binding to the actual simultaneous interval`() {
        val query = GraphQuery(
            root = NodePattern(id = NodeId("alice")),
            temporalWindow = TemporalWindow.ClosedRange(TimelineId("TimelineA"), 100.0, 200.0),
            expression = GraphQueryExpression.Relation(
                RelationPattern(
                    typeId = RelationTypeId("friendOf"),
                    target = NodePattern(id = NodeId("bob")),
                    targetVariable = VariableId("friend"),
                    targetExpression = GraphQueryExpression.Property(
                        PropertyPredicate(PropertyPath("age"), ValueOperator.LESS_THAN, NumberValue(15.0)),
                    ),
                ),
            ),
        )

        val match = execute(query).matches.single()

        assertEquals(NodeId("alice"), match.nodeId)
        assertEquals(NodeId("bob"), match.binding.variables.getValue(VariableId("friend")))
        assertTrue(match.binding.validTime.contains(TimelineId("TimelineA"), 120.0))
        assertTrue(match.binding.validTime.contains(TimelineId("TimelineA"), 140.0))
        assertFalse(match.binding.validTime.contains(TimelineId("TimelineA"), 141.0))
    }

    @Test
    fun `property AT query returns only the assertion active at the instant`() {
        val result = execute(
            GraphQuery(
                root = NodePattern(typeId = NodeTypeId("Person")),
                temporalWindow = TemporalWindow.At(TimelineId("TimelineA"), 170.0),
                expression = GraphQueryExpression.Property(
                    PropertyPredicate(
                        PropertyPath("age"),
                        ValueOperator.GREATER_THAN_OR_EQUALS,
                        NumberValue(15.0),
                    ),
                ),
            ),
        )

        assertEquals(listOf(NodeId("bob")), result.matches.map { it.nodeId })
        assertTrue(result.matches.single().binding.validTime.contains(TimelineId("TimelineA"), 170.0))
    }

    @Test
    fun `NOT subtracts only the time where its operand holds`() {
        val result = execute(
            GraphQuery(
                root = NodePattern(id = NodeId("bob")),
                temporalWindow = TemporalWindow.Range(TimelineId("TimelineA"), 100.0, 201.0),
                expression = GraphQueryExpression.Not(
                    GraphQueryExpression.Property(
                        PropertyPredicate(
                            PropertyPath("age"),
                            ValueOperator.GREATER_THAN_OR_EQUALS,
                            NumberValue(15.0),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            1,
            result.matches.size,
            "ages=${graph.propertyAssertions.filter { it.path == PropertyPath("age") }}; result=$result",
        )
        val time = result.matches.single().binding.validTime
        assertTrue(time.contains(TimelineId("TimelineA"), 159.0))
        assertFalse(time.contains(TimelineId("TimelineA"), 160.0))
        assertFalse(time.contains(TimelineId("TimelineA"), 200.0))
    }

    @Test
    fun `text search covers markdown body property values and relation labels`() {
        val body = execute(
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                expression = GraphQueryExpression.Text(TextPredicate("勇者")),
            ),
        )
        val property = execute(
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                expression = GraphQueryExpression.Text(TextPredicate("Alice")),
            ),
        )
        val label = execute(
            GraphQuery(
                root = NodePattern(id = NodeId("alice")),
                expression = GraphQueryExpression.Relation(
                    RelationPattern(label = TextPredicate("Bob")),
                ),
            ),
        )

        assertEquals(NodeId("alice"), body.matches.single().nodeId)
        assertEquals(NodeId("alice"), property.matches.single().nodeId)
        assertEquals(NodeId("alice"), label.matches.single().nodeId)
        assertTrue(body.matches.single().binding.score > 0.0, "body=$body")
    }

    @Test
    fun `directional temporal operators distinguish containment`() {
        val containsQuery = execute(
            GraphQuery(
                root = NodePattern(id = NodeId("bob")),
                temporalWindow = TemporalWindow.Range(TimelineId("TimelineA"), 110.0, 130.0),
                temporalOperator = TemporalOperator.ASSERTION_CONTAINS_QUERY,
                expression = GraphQueryExpression.Property(
                    PropertyPredicate(PropertyPath("age"), ValueOperator.LESS_THAN, NumberValue(15.0)),
                ),
            ),
        )
        val queryContains = execute(
            GraphQuery(
                root = NodePattern(id = NodeId("bob")),
                temporalWindow = TemporalWindow.Range(TimelineId("TimelineA"), 110.0, 130.0),
                temporalOperator = TemporalOperator.QUERY_CONTAINS_ASSERTION,
                expression = GraphQueryExpression.Property(
                    PropertyPredicate(PropertyPath("age"), ValueOperator.LESS_THAN, NumberValue(15.0)),
                ),
            ),
        )

        assertEquals(1, containsQuery.matches.size)
        assertTrue(queryContains.matches.isEmpty())
    }

    @Test
    fun `unknown query types and timelines are diagnostics instead of exceptions`() {
        val timeline = execute(
            GraphQuery(temporalWindow = TemporalWindow.At(TimelineId("missing"), 1.0)),
        )
        val relation = execute(
            GraphQuery(
                expression = GraphQueryExpression.Relation(
                    RelationPattern(typeId = RelationTypeId("missing")),
                ),
            ),
        )

        assertEquals(QueryDiagnosticCode.UNKNOWN_TIMELINE, timeline.diagnostics.single().code)
        assertEquals(QueryDiagnosticCode.UNKNOWN_RELATION_TYPE, relation.diagnostics.single().code)
    }

    private fun execute(query: GraphQuery) = runSuspend { executor.execute(graph, query) }

    private fun fixture(): List<SourceDocument> = listOf(
        source(
            "/graph/timeline.md",
            """
                ---
                id: TimelineA
                kind: Timeline
                timecode:
                  type: number
                ---
            """,
        ),
        source(
            "/graph/person.md",
            """
                ---
                id: Person
                kind: NodeType
                props:
                  name:
                    type: string
                  age:
                    type: number
                ---
            """,
        ),
        source(
            "/graph/friend.md",
            """
                ---
                id: friendOf
                kind: RelType
                from: [Person]
                to: [Person]
                props:
                  weight:
                    type: number
                ---
            """,
        ),
        source(
            "/graph/alice.md",
            """
                ---
                id: alice
                kind: Node
                type: Person
                validTime:
                  - timeline: TimelineA
                    from:
                      timecode: 100
                    to:
                      timecode: 200
                props:
                  name: Alice
                ---
                # Alice

                Aliceは勇者として活動していた。
                @link(validTime=TimelineA(from=100,to=140)){weight=0.9}[Bob](bob friendOf)
            """,
        ),
        source(
            "/graph/bob.md",
            """
                ---
                id: bob
                kind: Node
                type: Person
                validTime:
                  - timeline: TimelineA
                    from:
                      timecode: 100
                    to:
                      timecode: 200
                props:
                  name: Bob
                  age:
                    - value: 10
                      validTime:
                        - timeline: TimelineA
                          from:
                            timecode: 100
                          to:
                            timecode: 140
                    - value: 15
                      validTime:
                        - timeline: TimelineA
                          from:
                            timecode: 160
                          to:
                            timecode: 200
                ---
                # Bob
            """,
        ),
    )

    private fun source(path: String, text: String): SourceDocument =
        SourceDocument(text.trimIndent(), path)
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "The test coroutine suspended unexpectedly" }.getOrThrow()
}
