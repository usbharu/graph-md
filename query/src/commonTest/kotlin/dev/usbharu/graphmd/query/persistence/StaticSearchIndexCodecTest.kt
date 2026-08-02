package dev.usbharu.graphmd.query.persistence

import dev.usbharu.graphmd.core.model.*
import dev.usbharu.graphmd.query.index.SearchIndexBuilder
import dev.usbharu.graphmd.query.indexedFixtureGraph
import dev.usbharu.graphmd.query.ir.AssertionOwner
import dev.usbharu.graphmd.query.ir.PropertyAssertion
import dev.usbharu.graphmd.query.ir.QueryNodeTypeSchema
import dev.usbharu.graphmd.query.model.*
import dev.usbharu.graphmd.query.runtime.IndexedQueryExecutor
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.*

class StaticSearchIndexCodecTest {
    @Test
    fun `deterministically shards and round trips a complete physical index`() {
        val graph = graphWithAllValueKinds()
        val index = SearchIndexBuilder().build(graph)
        val options = SearchIndexFormatOptions(compilerVersion = "0.1.0-test", maxEntriesPerShard = 1)

        val first = StaticSearchIndexCodec.encode(index, options)
        val second = StaticSearchIndexCodec.encode(index, options)
        val manifest = StaticSearchIndexCodec.readManifest(first)
        val decoded = StaticSearchIndexCodec.decode(first)

        assertEquals(first, second)
        assertEquals(StaticSearchIndexCodec.FORMAT_VERSION, manifest.formatVersion)
        assertEquals("0.1.0-test", manifest.compilerVersion)
        assertEquals(3, manifest.shards.getValue("nodes").size)
        assertTrue(manifest.shards.getValue("properties").size > 1)
        assertTrue(first.shards.values.any { "\"numerator\"" in it })
        assertEquals(index, decoded)
    }

    @Test
    fun `round trips raw enum values in schema metadata`() {
        val schema = ResolvedPropSchema(
            type = PropType.array,
            enumValues = listOf(
                RawString("text"),
                RawInteger(1),
                RawNumber(2.5),
                RawBoolean(true),
                RawNull,
                RawArray(listOf(RawString("nested"))),
                RawObject(mapOf("key" to RawString("value"))),
            ),
        )
        val graph = indexedFixtureGraph().copy(
            nodeTypeSchemas = mapOf(
                NodeTypeId("Person") to QueryNodeTypeSchema(
                    id = NodeTypeId("Person"),
                    properties = mapOf("choices" to schema),
                    ancestorTypeIds = emptySet(),
                ),
            ),
        )

        val bundle = StaticSearchIndexCodec.encode(SearchIndexBuilder().build(graph))
        val decoded = StaticSearchIndexCodec.decode(bundle)

        assertEquals(
            schema,
            decoded.graph.nodeTypeSchemas.getValue(NodeTypeId("Person")).properties.getValue("choices"),
        )
    }

    @Test
    fun `loaded static index executes without rebuilding`() {
        val graph = indexedFixtureGraph()
        val bundle = StaticSearchIndexCodec.encode(SearchIndexBuilder().build(graph))
        val loaded = StaticSearchIndexCodec.decode(bundle)
        val query = GraphQuery(
            root = NodePattern(id = NodeId("alice")),
            temporalWindow = TemporalWindow.At(TimelineId("TimelineA"), 110.0),
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
        )

        val result = runPersistenceSuspend { IndexedQueryExecutor(loaded).execute(query) }

        assertEquals(NodeId("alice"), result.matches.single().nodeId)
        assertEquals(NodeId("bob"), result.matches.single().binding.variables.getValue(VariableId("friend")))
    }

    @Test
    fun `rejects missing and modified shards`() {
        val bundle = StaticSearchIndexCodec.encode(SearchIndexBuilder().build(indexedFixtureGraph()))
        val file = bundle.shards.keys.first()

        val missing = bundle.copy(shards = bundle.shards - file)
        val modified = bundle.copy(shards = bundle.shards + (file to "[]"))

        assertFailsWith<IllegalArgumentException> { StaticSearchIndexCodec.decode(missing) }
        assertFailsWith<IllegalArgumentException> { StaticSearchIndexCodec.decode(modified) }
    }

    private fun graphWithAllValueKinds() = indexedFixtureGraph().let { graph ->
        val owner = AssertionOwner.Node(NodeId("alice"))
        val source = SourceInfo("/graph/values.md")
        val nestedTime = listOf(
            ValidTime(
                "TimelineA",
                TimePoint(1.0, "one"),
                TimePoint(2.0, "two"),
            ),
        )
        val values = listOf<NormalizedValue>(
            IntegerValue(42),
            BooleanValue(true),
            NullValue,
            TextValue(
                mapOf(
                    "en" to NormalizedPropEntry(StringValue("Hero"), nestedTime),
                    "ja" to NormalizedPropEntry(StringValue("勇者")),
                ),
            ),
            ArrayValue(
                values = listOf(StringValue("one"), NumberValue(2.0)),
                elements = listOf(
                    NormalizedArrayElement(StringValue("one")),
                    NormalizedArrayElement(NumberValue(2.0), nestedTime),
                ),
            ),
            ObjectValue(
                values = mapOf("city" to StringValue("Tokyo")),
                members = mapOf("city" to NormalizedPropEntry(StringValue("Tokyo"), nestedTime)),
            ),
            InstantValue("TimelineA", "now", NumberTimecode(12.5)),
            DurationValue(
                "TimelineA",
                TemporalPoint(1.0, "begin", "TimelineA"),
                TemporalPoint(3.0, "end", "TimelineA"),
            ),
        )
        val sourceTimeline = graph.timelines.single()
        val targetTimeline = QueryTimeline(TimelineId("TimelineB"), TimelineId("TimelineB"), 0.0)
        val mapping = TemporalMappingInstance(
            id = "TimelineA->TimelineB#0",
            sourceTimelineId = "TimelineA",
            targetTimelineId = "TimelineB",
            sourceAxisId = "TimelineA",
            targetAxisId = "TimelineB",
            kind = TemporalMappingKind.Alignment,
            precision = TemporalPrecision(),
            scale = ExactRational.ONE,
            offset = ExactRational.ZERO,
            range = null,
            segments = emptyList(),
            pairs = emptyList(),
            traits = TemporalMappingTraits(
                TemporalCardinality.OneToOne,
                TemporalTotality.Total,
                TemporalOrderBehavior.StrictlyIncreasing,
                TemporalInvertibility.Invertible,
                TemporalContinuity.Continuous,
            ),
            requiredContext = emptyList(),
            provenance = mapOf("source" to RawString("spec sample")),
        )
        graph.copy(
            nodes = graph.nodes + graph.nodes.first().copy(id = NodeId("value-holder")),
            timelines = listOf(sourceTimeline.copy(mappings = listOf(mapping)), targetTimeline),
            propertyAssertions = graph.propertyAssertions + values.mapIndexed { index, value ->
                PropertyAssertion(
                    id = AssertionId(6 + index),
                    stableKey = StableAssertionKey("value:$index"),
                    owner = owner,
                    propertyId = PropertyId("value$index"),
                    path = PropertyPath("value$index"),
                    value = value,
                    validTime = IntervalSet.universal(),
                    source = source,
                )
            },
        )
    }
}

private fun <T> runPersistenceSuspend(block: suspend () -> T): T {
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
