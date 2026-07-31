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

    @Test
    fun `mapped timelines do not broaden a body block relation assertion`() {
        val sources = listOf(
            SourceDocument(
                "---\nid: THE_IDOLMASTER\nkind: Timeline\ntimecode:\n  type: number\n---",
                "/the-idolmaster.md",
            ),
            SourceDocument(
                "---\nid: THE_IDOLMASTER2\nkind: Timeline\nextends: [THE_IDOLMASTER]\n---",
                "/the-idolmaster2.md",
            ),
            SourceDocument(
                """
                ---
                id: MILLION_LIVE
                kind: Timeline
                mappings:
                  - kind: offset
                    offset: 0
                    from: THE_IDOLMASTER2
                timecode:
                  type: number
                ---
                """.trimIndent(),
                "/million-live.md",
            ),
            SourceDocument("---\nid: Idol\nkind: NodeType\n---", "/idol.md"),
            SourceDocument("---\nid: Production\nkind: NodeType\n---", "/production.md"),
            SourceDocument(
                "---\nid: affWith\nkind: RelType\nfrom: [Idol]\nto: [Production]\n---",
                "/aff-with.md",
            ),
            SourceDocument(
                """
                ---
                id: 765Production
                kind: Node
                type: Production
                validTime:
                  - timeline: THE_IDOLMASTER
                  - timeline: THE_IDOLMASTER2
                  - timeline: MILLION_LIVE
                ---
                """.trimIndent(),
                "/765-production.md",
            ),
            SourceDocument(
                """
                ---
                id: AmamiHaruka
                kind: Node
                type: Idol
                validTime:
                  - timeline: THE_IDOLMASTER
                  - timeline: THE_IDOLMASTER2
                  - timeline: MILLION_LIVE
                ---
                ::: validTime=MILLION_LIVE
                @link[a](765Production affWith)
                :::
                """.trimIndent(),
                "/amami-haruka.md",
            ),
        )
        val compilation = GraphCompiler().compileSources(sources)
        val engine = GraphSearchEngine.build(compilation, sources)
        val millionLiveRelation = engine.graph.relationAssertions.single()
        assertEquals(
            setOf(TimelineId("MILLION_LIVE")),
            millionLiveRelation.validTime.intervals.mapTo(linkedSetOf()) { it.timelineId },
        )
        val queryPrefix = "MATCH (source)-[link:affWith]->(target) VALID ON "
        val querySuffix =
            " ANYTIME RETURN ID(link), TYPE(link), ID(source), ID(target), VALIDITY()"
        fun kotlinQuery(timeline: String) = GraphQuery(
            root = NodePattern(id = NodeId("AmamiHaruka")),
            temporalWindow = TemporalWindow.At(TimelineId(timeline), 0.0),
            expression = GraphQueryExpression.Relation(
                RelationPattern(
                    typeId = RelationTypeId("affWith"),
                    target = NodePattern(id = NodeId("765Production")),
                ),
            ),
        )

        val onMillionLive = runEngineSuspend {
            engine.queryGmql(queryPrefix + "MILLION_LIVE" + querySuffix)
        }
        val onTheIdolmaster = runEngineSuspend {
            engine.queryGmql(queryPrefix + "THE_IDOLMASTER" + querySuffix)
        }
        val loaded = GraphSearchEngine.loadStatic(engine.exportStatic())
        val staticOnTheIdolmaster = runEngineSuspend {
            loaded.queryGmql(queryPrefix + "THE_IDOLMASTER" + querySuffix)
        }
        val extendsScopeQuery =
            "MATCH (source:Idol) VALID ON THE_IDOLMASTER2 ANYTIME RETURN ID(source)"
        val onExtendedTimeline = runEngineSuspend { engine.queryGmql(extendsScopeQuery) }
        val staticOnExtendedTimeline = runEngineSuspend { loaded.queryGmql(extendsScopeQuery) }
        val kotlinOnMillionLive = runEngineSuspend { engine.search(kotlinQuery("MILLION_LIVE")) }
        val kotlinOnTheIdolmaster = runEngineSuspend { engine.search(kotlinQuery("THE_IDOLMASTER")) }

        assertEquals(1, onMillionLive.rows.size, onMillionLive.toString())
        assertTrue(onTheIdolmaster.rows.isEmpty(), onTheIdolmaster.toString())
        assertEquals(onTheIdolmaster, staticOnTheIdolmaster)
        assertEquals(1, onExtendedTimeline.rows.size, onExtendedTimeline.toString())
        assertEquals(onExtendedTimeline, staticOnExtendedTimeline)
        assertEquals(1, kotlinOnMillionLive.matches.size, kotlinOnMillionLive.toString())
        assertTrue(kotlinOnTheIdolmaster.matches.isEmpty(), kotlinOnTheIdolmaster.toString())
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
