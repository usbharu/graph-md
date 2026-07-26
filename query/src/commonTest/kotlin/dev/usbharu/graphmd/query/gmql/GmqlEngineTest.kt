package dev.usbharu.graphmd.query.gmql

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.query.GraphSearchEngine
import dev.usbharu.graphmd.query.model.TimelineId
import kotlin.coroutines.*
import kotlin.test.*

class GmqlEngineTest {
    private val sources = listOf(
        source(
            "/timeline.md",
            """
            ---
            id: MainStory
            kind: Timeline
            timecode:
              type: number
            ---
            """,
        ),
        source(
            "/person.md",
            """
            ---
            id: Person
            kind: NodeType
            props:
              name:
                type: string
              biography:
                type: text
              age:
                type: number
              nickname:
                type: string
            ---
            """,
        ),
        source(
            "/friend.md",
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
            "/alice.md",
            """
            ---
            id: alice
            kind: Node
            type: Person
            validTime:
              - timeline: MainStory
                from:
                  timecode: 100
                to:
                  timecode: 300
            props:
              name: Alice
              biography:
                default: 勇者
              age:
                - value: 15
                  validTime:
                    - timeline: MainStory
                      from:
                        timecode: 100
                      to:
                        timecode: 199
                - value: 20
            ---
            # Alice

            Aliceは勇者だった。
            @link(validTime=MainStory(from=100,to=180)){weight=0.9}[Bob](bob friendOf)
            """,
        ),
        source(
            "/bob.md",
            """
            ---
            id: bob
            kind: Node
            type: Person
            validTime:
              - timeline: MainStory
                from:
                  timecode: 100
                to:
                  timecode: 300
            props:
              name: Bob
              biography:
                default: 魔王
              age: 18
            ---
            # Bob
            """,
        ),
    )
    private val engine = GraphSearchEngine.build(GraphCompiler().compileSources(sources), sources)

    @Test
    fun `complete query parses compiles and executes`() {
        val result = runSuspend {
            engine.queryGmql(
                """
                // comments and keyword case folding are accepted
                match (source:Person)-[relation:friendOf]->(target:Person)
                WHERE FULLTEXT(source, "勇者")
                  AND target.age >= ${'$'}minimumAge
                VALID ON MainStory OVERLAPS [100, 200)
                RETURN DISTINCT
                  ID(source) AS id,
                  ID(target) AS friendId,
                  MATCHED_VALIDITY() AS validity,
                  SCORE() AS score
                ORDER BY score DESC, id ASC
                LIMIT 50;
                """.trimIndent(),
                mapOf("minimumAge" to GmqlValue.IntegerValue(15)),
            )
        }

        assertTrue(result.isSuccess, result.diagnostics.toString())
        assertEquals("alice", (result.rows.single().values[0] as GmqlValue.StringValue).value)
        assertEquals("bob", (result.rows.single().values[1] as GmqlValue.StringValue).value)
        assertTrue((result.rows.single().values[3] as GmqlValue.DecimalValue).value > 0.0)
    }

    @Test
    fun `text stays structured while a member is a temporal string`() {
        val invalid = engine.compileGmql(
            """MATCH (n:Person) WHERE n.biography = "勇者" RETURN n""",
        )
        val valid = engine.compileGmql(
            """MATCH (n:Person) WHERE n.biography.default = "勇者" RETURN ID(n)""",
        )
        val fullText = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE FULLTEXT(n.biography, "勇者") RETURN ID(n) AS id""",
            )
        }

        assertFalse(invalid.isSuccess)
        assertTrue(invalid.diagnostics.any { it.code == "GMQL3001" })
        assertTrue(valid.isSuccess, valid.diagnostics.toString())
        assertEquals("alice", (fullText.rows.single().values.single() as GmqlValue.StringValue).value)
    }

    @Test
    fun `fallback property occupies only time not covered by timed values`() {
        val early = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE ID(n) = "alice" AND n.age = 20
                   VALID ON MainStory AT 150 RETURN ID(n)""",
            )
        }
        val late = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE ID(n) = "alice" AND n.age = 20
                   VALID ON MainStory AT 250 RETURN ID(n)""",
            )
        }

        assertTrue(early.rows.isEmpty(), early.toString())
        assertEquals(
            1,
            late.rows.size,
            "$late ages=${engine.graph.propertyAssertions.filter { it.path.toString() == "age" }}",
        )
    }

    @Test
    fun `validity remains whole while matched validity is clipped`() {
        val result = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE ID(n) = "alice"
                   VALID ON MainStory OVERLAPS [150, 160)
                   RETURN VALIDITY() AS whole, MATCHED_VALIDITY() AS matched""",
            )
        }
        val whole = (result.rows.single().values[0] as GmqlValue.TemporalExtentValue).value
        val matched = (result.rows.single().values[1] as GmqlValue.TemporalExtentValue).value

        assertTrue(whole.contains(TimelineId("MainStory"), 250.0))
        assertFalse(matched.contains(TimelineId("MainStory"), 250.0))
        assertTrue(matched.contains(TimelineId("MainStory"), 155.0))
    }

    @Test
    fun `non anytime valid requires an explicit timeline`() {
        val result = engine.compileGmql("""MATCH (n) VALID AT 1 RETURN n""")

        assertFalse(result.isSuccess)
        assertEquals("GMQL4001", result.diagnostics.single().code)
    }

    @Test
    fun `valid on timeline excludes documents without a validity assertion on that timeline`() {
        val localSources = sources + source(
            "/charlie.md",
            """
            ---
            id: charlie
            kind: Node
            type: Person
            props:
              name: Charlie
            ---
            """,
        )
        val localEngine = GraphSearchEngine.build(GraphCompiler().compileSources(localSources), localSources)

        val scopedAnytime = runSuspend {
            localEngine.queryGmql(
                """MATCH (n:Person) VALID ON MainStory ANYTIME RETURN ID(n) AS id ORDER BY id""",
            )
        }
        val scopedAt = runSuspend {
            localEngine.queryGmql(
                """MATCH (n:Person) VALID ON MainStory AT 150 RETURN ID(n) AS id ORDER BY id""",
            )
        }
        val unscopedAnytime = runSuspend {
            localEngine.queryGmql(
                """MATCH (n:Person) VALID ANYTIME RETURN ID(n) AS id ORDER BY id""",
            )
        }

        assertEquals(listOf("alice", "bob"), scopedAnytime.stringColumn())
        assertEquals(listOf("alice", "bob"), scopedAt.stringColumn())
        assertEquals(listOf("alice", "bob", "charlie"), unscopedAnytime.stringColumn())
    }

    @Test
    fun `static bundle retains schemas needed to compile queries`() {
        val loaded = GraphSearchEngine.loadStatic(engine.exportStatic())
        val result = loaded.compileGmql(
            """MATCH (n:Person) WHERE n.biography.default STARTS WITH "勇" RETURN ID(n)""",
        )

        assertTrue(result.isSuccess, result.diagnostics.toString())
    }

    @Test
    fun `indexed and reference execution agree across temporal operators and boolean expressions`() {
        val queries = listOf(
            """MATCH (n:Person) WHERE n.age >= 15 OR n.name = "Nobody"
               VALID ON MainStory AT 250
               RETURN ID(n) AS id, VALIDITY() AS validity ORDER BY id""",
            """MATCH (a:Person)-[r:friendOf]-(b:Person)
               VALID ON MainStory CONTAINS [110, 170)
               RETURN ID(a), ID(b), TYPE(r)""",
            """MATCH (n:Person) WHERE NOT (n.age = 15)
               VALID ON MainStory OVERLAPS [100, 300]
               RETURN ID(n), MATCHED_VALIDITY()""",
            """MATCH (a:Person)-[:friendOf]->(b)
               VALID ON MainStory DURING [90, 181)
               RETURN ID(a), ID(b)""",
        )

        queries.forEach { text ->
            val compiled = engine.compileGmql(text)
            assertTrue(compiled.isSuccess, compiled.diagnostics.toString())
            val indexed = runSuspend { engine.executeGmql(compiled.query!!) }
            val reference = runSuspend { engine.scanGmql(compiled.query!!) }
            assertEquals(reference, indexed, text)
        }
    }

    @Test
    fun `parser supports escaped strings quoted identifiers comments and all relation directions`() {
        val queries = listOf(
            """/* block */ MATCH (`MATCH`:`Person`)<-[r:friendOf]-(b) RETURN "line\n\"quoted\"" AS value""",
            """MATCH (a)-[:friendOf]-(b), (b)-[]->(c) RETURN a, b, c;""",
            """MATCH (n) WHERE n.name ENDS WITH "ice" OR n.name CONTAINS "lic" RETURN n""",
        )

        queries.forEach {
            val result = engine.compileGmql(it)
            assertTrue(result.isSuccess, "$it: ${result.diagnostics}")
        }
        val reserved = engine.compileGmql("""MATCH (MATCH:Person) RETURN MATCH""")
        assertFalse(reserved.isSuccess)
        assertEquals(GmqlDiagnosticKind.SYNTAX, reserved.diagnostics.single().kind)
    }

    @Test
    fun `missing property property comparison relation property and text query grammar execute temporally`() {
        val missing = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE n.nickname IS MISSING
                   RETURN ID(n) AS id ORDER BY id""",
            )
        }
        val comparison = runSuspend {
            engine.queryGmql(
                """MATCH (a:Person)-[r:friendOf]->(b:Person)
                   WHERE a.age < b.age AND r.weight >= 0.8
                   VALID ON MainStory AT 150
                   RETURN ID(a), ID(b)""",
            )
        }
        val text = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE FULLTEXT(n, "勇* OR 魔王")
                   RETURN ID(n) AS id ORDER BY id""",
            )
        }
        val scopedText = runSuspend {
            engine.queryGmql(
                """MATCH (a)-[r:friendOf]->(b)
                   WHERE FULLTEXT(a.body, "勇者") AND FULLTEXT(r.label, "Bob")
                   RETURN ID(a), TITLE(a), ID(b)""",
            )
        }

        assertEquals(listOf("alice", "bob"), missing.rows.map { (it.values[0] as GmqlValue.StringValue).value })
        assertEquals(1, comparison.rows.size, comparison.toString())
        assertEquals(listOf("alice", "bob"), text.rows.map { (it.values[0] as GmqlValue.StringValue).value })
        assertEquals(1, scopedText.rows.size, scopedText.toString())
    }

    @Test
    fun `offset limit parameters and execution profiles are checked without string substitution`() {
        val paged = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) RETURN ID(n) AS id ORDER BY id OFFSET ${'$'}offset LIMIT ${'$'}limit""",
                mapOf(
                    "offset" to GmqlValue.IntegerValue(1),
                    "limit" to GmqlValue.IntegerValue(1),
                ),
            )
        }
        val limited = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) RETURN ID(n)""",
                options = GmqlExecutionOptions(
                    profile = GmqlExecutionProfile.STATIC_WEB,
                    maxResults = 1,
                ),
            )
        }

        assertEquals("bob", (paged.rows.single().values.single() as GmqlValue.StringValue).value)
        assertEquals("GMQL5001", limited.diagnostics.single().code)
    }

    @Test
    fun `comparison projections preserve scalar and temporal false values`() {
        val result = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) WHERE ID(n) = "alice"
                   RETURN 1 > 100 AS scalar, n.age > 100 AS temporal""",
            )
        }

        assertTrue(result.isSuccess, result.diagnostics.toString())
        val values = result.rows.single().values
        assertEquals(GmqlValue.BooleanValue(false), values[0])
        val temporal = assertIs<GmqlValue.TemporalValue>(values[1])
        assertTrue(temporal.entries.isNotEmpty())
        assertTrue(temporal.entries.all { it.value == GmqlValue.BooleanValue(false) })
    }

    @Test
    fun `non finite decimals are rejected or reported as diagnostics`() {
        val literal = engine.compileGmql("""MATCH (n) RETURN 1e999 AS value""")
        val overflow = runSuspend {
            engine.queryGmql("""MATCH (n) RETURN 1e308 * 1e308 AS value""")
        }
        val invalidTemporalBoundary = runSuspend {
            engine.queryGmql(
                """MATCH (n:Person) VALID ON MainStory AT 1 / 0 RETURN ID(n)""",
            )
        }

        assertFalse(literal.isSuccess)
        assertEquals("GMQL1001", literal.diagnostics.single().code)
        assertEquals("GMQL5003", overflow.diagnostics.single().code)
        assertEquals("GMQL4003", invalidTemporalBoundary.diagnostics.single().code)
        assertFailsWith<IllegalArgumentException> {
            GmqlValue.DecimalValue(Double.POSITIVE_INFINITY)
        }
    }

    private fun source(path: String, text: String) = SourceDocument(text.trimIndent(), path)
}

private fun GmqlQueryResult.stringColumn(): List<String> =
    rows.map { (it.values.single() as GmqlValue.StringValue).value }

private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
        }
    })
    return checkNotNull(outcome).getOrThrow()
}
