package dev.usbharu.graphmd.query

import dev.usbharu.graphmd.core.model.NumberValue
import dev.usbharu.graphmd.query.model.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

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
