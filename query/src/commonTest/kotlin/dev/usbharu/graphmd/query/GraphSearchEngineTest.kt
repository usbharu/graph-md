package dev.usbharu.graphmd.query

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.NumberValue
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.model.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphSearchEngineTest {
    @Test
    fun `facade searches and reloads a static bundle`() {
        val engine = GraphSearchEngine.fromGraph(indexedFixtureGraph())
        val query = GraphQuery(
            root = NodePattern(id = NodeId("bob")),
            temporalWindow = TemporalWindow.At(testTimeline, 150.0),
            expression = GraphQueryExpression.Property(
                PropertyPredicate(
                    PropertyPath("age"),
                    ValueOperator.GREATER_THAN,
                    NumberValue(15.0),
                ),
            ),
        )

        val indexed = runEngineSuspend { engine.search(query) }
        val scanned = runEngineSuspend { engine.scan(query) }
        val loaded = GraphSearchEngine.loadStatic(engine.exportStatic())
        val reloaded = runEngineSuspend { loaded.search(query) }

        assertEquals(scanned, indexed)
        assertEquals(indexed, reloaded)
        assertEquals(NodeId("bob"), reloaded.matches.single().nodeId)
    }

    @Test
    fun `body block validTime applies to Kotlin text search and static bundles`() {
        val sources = listOf(
            SourceDocument("---\nid: Story\nkind: Timeline\n---", "/story.md"),
            SourceDocument(
                "---\nid: Person\nkind: NodeType\nprops:\n  score:\n    type: number\n---",
                "/person.md",
            ),
            SourceDocument(
                "---\nid: friendOf\nkind: RelType\nfrom: [Person]\nto: [Person]\n---",
                "/friend.md",
            ),
            SourceDocument("---\nid: bob\nkind: Node\ntype: Person\n---", "/bob.md"),
            SourceDocument(
                """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                outside phrase
                ::: chapter validTime=Story(from=0,to=10)
                limited phrase
                @props{score=5}
                @link[Bob](bob friendOf)
                :::
                """.trimIndent(),
                "/alice.md",
            ),
        )
        val engine = GraphSearchEngine.build(GraphCompiler().compileSources(sources), sources)
        fun query(at: Double) = GraphQuery(
            root = NodePattern(id = NodeId("alice")),
            temporalWindow = TemporalWindow.At(TimelineId("Story"), at),
            expression = GraphQueryExpression.And(
                listOf(
                    GraphQueryExpression.Text(TextPredicate("limited phrase")),
                    GraphQueryExpression.Property(
                        PropertyPredicate(PropertyPath("score"), ValueOperator.EQUALS, NumberValue(5.0)),
                    ),
                    GraphQueryExpression.Relation(
                        RelationPattern(
                            typeId = RelationTypeId("friendOf"),
                            target = NodePattern(id = NodeId("bob")),
                        ),
                    ),
                ),
            ),
        )
        fun outsideQuery(at: Double) = GraphQuery(
            root = NodePattern(id = NodeId("alice")),
            temporalWindow = TemporalWindow.At(TimelineId("Story"), at),
            expression = GraphQueryExpression.Text(TextPredicate("outside phrase")),
        )

        val active = runEngineSuspend { engine.search(query(5.0)) }
        val inactive = runEngineSuspend { engine.search(query(20.0)) }
        val outside = runEngineSuspend { engine.search(outsideQuery(20.0)) }
        val scanned = runEngineSuspend { engine.scan(query(5.0)) }
        val reloaded = GraphSearchEngine.loadStatic(engine.exportStatic())
        val staticResult = runEngineSuspend { reloaded.search(query(5.0)) }

        assertEquals(listOf(NodeId("alice")), active.matches.map { it.nodeId })
        assertTrue(inactive.matches.isEmpty())
        assertEquals(listOf(NodeId("alice")), outside.matches.map { it.nodeId })
        assertEquals(scanned, active)
        assertEquals(active, staticResult)
    }
}

private fun <T> runEngineSuspend(block: suspend () -> T): T {
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
