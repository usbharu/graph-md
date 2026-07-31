package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.DiagnosticCategory
import dev.usbharu.graphmd.core.model.DocumentKind
import dev.usbharu.graphmd.core.model.NodeDocument
import dev.usbharu.graphmd.core.model.NodeTypeDocument
import dev.usbharu.graphmd.core.model.OffsetTimelineMapping
import dev.usbharu.graphmd.core.model.PropSchema
import dev.usbharu.graphmd.core.model.PropType
import dev.usbharu.graphmd.core.model.RawArray
import dev.usbharu.graphmd.core.model.RawInteger
import dev.usbharu.graphmd.core.model.RawObject
import dev.usbharu.graphmd.core.model.RawString
import dev.usbharu.graphmd.core.model.RelTypeDocument
import dev.usbharu.graphmd.core.model.TimecodeSchema
import dev.usbharu.graphmd.core.model.TimecodeType
import dev.usbharu.graphmd.core.model.TimelineDocument
import dev.usbharu.graphmd.core.model.TimelineSelector
import dev.usbharu.graphmd.core.model.ValidTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCompilerDuplicateIdTest {
    private val compiler = GraphCompiler()

    @Test
    fun `drops every same-kind duplicate from normalized lists`() {
        val result = compiler.compile(
            listOf(
                TimelineDocument("time", sourcePath = "/tmp/time-a.md"),
                TimelineDocument("time", sourcePath = "/tmp/time-b.md"),
                NodeTypeDocument("Thing", sourcePath = "/tmp/thing-a.md"),
                NodeTypeDocument("Thing", sourcePath = "/tmp/thing-b.md"),
                RelTypeDocument("related", sourcePath = "/tmp/related-a.md"),
                RelTypeDocument("related", sourcePath = "/tmp/related-b.md"),
                NodeTypeDocument("UniqueType", sourcePath = "/tmp/unique-type.md"),
                NodeDocument("item", "UniqueType", sourcePath = "/tmp/item-a.md"),
                NodeDocument(
                    "item",
                    "UniqueType",
                    url = "https://example.com/item",
                    sourcePath = "/tmp/item-b.md",
                    documentKind = DocumentKind.Media,
                ),
            ),
        )

        assertTrue(result.timelines.none { it.id == "time" })
        assertTrue(result.nodeTypes.none { it.id == "Thing" })
        assertTrue(result.relTypes.none { it.id == "related" })
        assertTrue(result.nodes.none { it.id == "item" })
        assertEquals(2, duplicateDiagnostics(result, "Timeline", "time").size)
        assertEquals(2, duplicateDiagnostics(result, "NodeType", "Thing").size)
        assertEquals(2, duplicateDiagnostics(result, "RelType", "related").size)
        assertEquals(2, duplicateDiagnostics(result, "Node", "item").size)
        assertEquals(
            setOf("/tmp/thing-a.md", "/tmp/thing-b.md"),
            duplicateDiagnostics(result, "NodeType", "Thing").map { it.source?.path }.toSet(),
        )
    }

    @Test
    fun `does not resolve ambiguous ids from inheritance schemas endpoints or links`() {
        val documents = listOf(
            TimelineDocument("time", sourcePath = "/tmp/time-a.md"),
            TimelineDocument("time", sourcePath = "/tmp/time-b.md"),
            TimelineDocument("child-time", extends = listOf("time"), sourcePath = "/tmp/child-time.md"),
            TimelineDocument(
                "mapped-time",
                timecode = TimecodeSchema(TimecodeType.number),
                mappings = listOf(OffsetTimelineMapping(from = "time", offset = 1.0)),
                sourcePath = "/tmp/mapped-time.md",
            ),
            NodeTypeDocument("Base", sourcePath = "/tmp/base-a.md"),
            NodeTypeDocument("Base", sourcePath = "/tmp/base-b.md"),
            NodeTypeDocument(
                "Child",
                extends = listOf("Base"),
                props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("time"))),
                sourcePath = "/tmp/child.md",
            ),
            RelTypeDocument("related", sourcePath = "/tmp/related-a.md"),
            RelTypeDocument("related", sourcePath = "/tmp/related-b.md"),
            RelTypeDocument(
                "child-related",
                extends = listOf("related"),
                from = listOf("Base"),
                to = listOf("Base"),
                sourcePath = "/tmp/child-related.md",
            ),
            NodeDocument("target", "Child", sourcePath = "/tmp/target-a.md"),
            NodeDocument(
                "target",
                "Child",
                url = "https://example.com/last-winner",
                sourcePath = "/tmp/target-b.md",
                documentKind = DocumentKind.Media,
            ),
            NodeDocument(
                "source",
                "Base",
                validTime = listOf(ValidTime("time")),
                body = "@link{}[Target](target related)",
                sourcePath = "/tmp/source.md",
            ),
        )

        val result = compiler.compile(documents)

        fun assertAmbiguous(messagePart: String, sourcePath: String) {
            assertTrue(
                result.diagnostics.any {
                    it.category == DiagnosticCategory.ReferenceError &&
                        messagePart in it.message &&
                        it.source?.path == sourcePath
                },
                "Expected ambiguous reference '$messagePart' at $sourcePath, got ${result.diagnostics}",
            )
        }
        assertAmbiguous("Ambiguous parent Timeline: time", "/tmp/child-time.md")
        assertAmbiguous("Ambiguous mapped Timeline: time", "/tmp/mapped-time.md")
        assertAmbiguous("Ambiguous parent NodeType: Base", "/tmp/child.md")
        assertAmbiguous("Ambiguous Timeline: time", "/tmp/child.md")
        assertAmbiguous("Ambiguous parent RelType: related", "/tmp/child-related.md")
        assertAmbiguous("Ambiguous NodeType in RelType from: Base", "/tmp/child-related.md")
        assertAmbiguous("Ambiguous NodeType in RelType to: Base", "/tmp/child-related.md")
        assertAmbiguous("Ambiguous NodeType: Base", "/tmp/source.md")
        assertAmbiguous("Ambiguous Timeline: time", "/tmp/source.md")
        assertAmbiguous("Ambiguous RelType: related", "/tmp/source.md")
        assertAmbiguous("Ambiguous Node target: target", "/tmp/source.md")
        assertEquals(null, result.relTypes.single { it.id == "child-related" }.from)
        assertEquals(null, result.relTypes.single { it.id == "child-related" }.to)
        assertEquals(null, result.relations.single().targetUrl)
        assertTrue(result.diagnostics.any {
            it.message == "Ambiguous RelType: related" &&
                it.source?.path == "/tmp/source.md" &&
                it.source.range != null
        })
    }

    @Test
    fun `duplicate definition order cannot select a winner`() {
        val first = NodeTypeDocument(
            "Ambiguous",
            props = mapOf("first" to PropSchema(PropType.string)),
            sourcePath = "/tmp/first.md",
        )
        val second = NodeTypeDocument(
            "Ambiguous",
            props = mapOf("second" to PropSchema(PropType.number)),
            sourcePath = "/tmp/second.md",
        )
        val stable = listOf(
            NodeTypeDocument("Unique", sourcePath = "/tmp/unique.md"),
            NodeDocument(
                "item",
                "Ambiguous",
                props = mapOf("value" to RawString("unchanged")),
                sourcePath = "/tmp/item.md",
            ),
        )

        val forward = compiler.compile(listOf(first, second) + stable)
        val reversed = compiler.compile(listOf(second, first) + stable)

        assertEquals(forward.nodeTypes, reversed.nodeTypes)
        assertEquals(forward.nodes, reversed.nodes)
        assertTrue(forward.nodeTypes.none { it.id == "Ambiguous" })
        assertTrue(reversed.nodeTypes.none { it.id == "Ambiguous" })
        assertTrue(forward.diagnostics.any { it.message == "Ambiguous NodeType: Ambiguous" })
        assertTrue(reversed.diagnostics.any { it.message == "Ambiguous NodeType: Ambiguous" })
    }

    @Test
    fun `unreferenced duplicates do not create reference errors and unique ids remain unaffected`() {
        val result = compiler.compile(
            listOf(
                TimelineDocument("shared", sourcePath = "/tmp/shared-timeline.md"),
                NodeTypeDocument("shared", sourcePath = "/tmp/shared-type.md"),
                RelTypeDocument("unused", sourcePath = "/tmp/unused-a.md"),
                RelTypeDocument("unused", sourcePath = "/tmp/unused-b.md"),
                NodeDocument("node", "shared", sourcePath = "/tmp/node.md"),
            ),
        )

        assertEquals(listOf("shared"), result.timelines.map { it.id })
        assertEquals(listOf("shared"), result.nodeTypes.map { it.id })
        assertEquals(listOf("node"), result.nodes.map { it.id })
        assertFalse(result.diagnostics.any {
            it.category == DiagnosticCategory.ReferenceError && "unused" in it.message
        })
    }

    @Test
    fun `invalid reltype endpoints do not become deny-all constraints`() {
        val result = compiler.compile(
            listOf(
                NodeTypeDocument("Base", sourcePath = "/tmp/base-a.md"),
                NodeTypeDocument("Base", sourcePath = "/tmp/base-b.md"),
                NodeTypeDocument("S", sourcePath = "/tmp/s.md"),
                NodeTypeDocument("T", sourcePath = "/tmp/t.md"),
                RelTypeDocument(
                    "all-invalid",
                    from = listOf("Base"),
                    to = listOf("Base"),
                    sourcePath = "/tmp/all-invalid.md",
                ),
                RelTypeDocument(
                    "partial",
                    from = listOf("S", "Base"),
                    to = listOf("T", "Base"),
                    sourcePath = "/tmp/partial.md",
                ),
                NodeDocument(
                    "source",
                    "S",
                    body = """
                        @link{}[Source](source all-invalid)
                        @link{}[Target](target partial)
                    """.trimIndent(),
                    sourcePath = "/tmp/source.md",
                ),
                NodeDocument("target", "T", sourcePath = "/tmp/target.md"),
            ),
        )

        val allInvalid = result.relTypes.single { it.id == "all-invalid" }
        val partial = result.relTypes.single { it.id == "partial" }
        assertEquals(null, allInvalid.from)
        assertEquals(null, allInvalid.to)
        assertEquals(listOf("S"), partial.from)
        assertEquals(listOf("T"), partial.to)
        assertTrue(result.diagnostics.any { it.message == "Ambiguous NodeType in RelType from: Base" })
        assertTrue(result.diagnostics.any { it.message == "Ambiguous NodeType in RelType to: Base" })
        assertFalse(result.diagnostics.any {
            it.category == DiagnosticCategory.ConstraintError &&
                ("Relation source type" in it.message || "Relation target type" in it.message)
        })
    }

    @Test
    fun `invalid timeline selectors are excluded from later constraint checks recursively`() {
        val result = compiler.compile(
            listOf(
                TimelineDocument("ambiguous", sourcePath = "/tmp/ambiguous-a.md"),
                TimelineDocument("ambiguous", sourcePath = "/tmp/ambiguous-b.md"),
                TimelineDocument("valid", sourcePath = "/tmp/valid.md"),
                NodeTypeDocument(
                    "Event",
                    props = mapOf(
                        "allInvalid" to PropSchema(
                            PropType.instant,
                            timeline = TimelineSelector.Id("ambiguous"),
                        ),
                        "partial" to PropSchema(
                            PropType.instant,
                            timelines = listOf(
                                TimelineSelector.Id("ambiguous"),
                                TimelineSelector.Id("valid"),
                            ),
                        ),
                        "duration" to PropSchema(
                            PropType.duration,
                            timelines = listOf(
                                TimelineSelector.Id("ambiguous"),
                                TimelineSelector.Id("valid"),
                            ),
                        ),
                        "nested" to PropSchema(
                            PropType.array,
                            items = PropSchema(
                                PropType.instant,
                                timelines = listOf(
                                    TimelineSelector.Id("ambiguous"),
                                    TimelineSelector.Id("valid"),
                                ),
                            ),
                        ),
                        "text" to PropSchema(
                            PropType.text,
                            timeline = TimelineSelector.Id("ambiguous"),
                        ),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    "event",
                    "Event",
                    props = mapOf(
                        "allInvalid" to instant("valid", 1),
                        "partial" to instant("valid", 2),
                        "duration" to RawObject(
                            mapOf(
                                "timeline" to RawString("valid"),
                                "from" to RawInteger(1),
                            ),
                        ),
                        "nested" to RawArray(listOf(instant("valid", 3))),
                        "text" to RawString("value"),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        val props = result.nodeTypes.single { it.id == "Event" }.props
        assertEquals(null, props.getValue("allInvalid").timeline)
        assertEquals(listOf(TimelineSelector.Id("valid")), props.getValue("partial").timelines)
        assertEquals(
            listOf(TimelineSelector.Id("valid")),
            props.getValue("nested").items?.timelines,
        )
        assertEquals(null, props.getValue("text").timeline)
        assertTrue(result.diagnostics.any { it.message == "Ambiguous Timeline: ambiguous" })
        assertFalse(result.diagnostics.any { "timeline valid is not allowed" in it.message })
        assertEquals(
            setOf("allInvalid", "partial", "duration", "nested", "text"),
            result.nodes.single().props.keys,
        )
    }

    @Test
    fun `invalid duration endpoint references do not imply missing endpoints`() {
        val result = compiler.compile(
            listOf(
                TimelineDocument("ambiguous", sourcePath = "/tmp/ambiguous-a.md"),
                TimelineDocument("ambiguous", sourcePath = "/tmp/ambiguous-b.md"),
                NodeTypeDocument(
                    "Event",
                    props = mapOf(
                        "fromAmbiguous" to PropSchema(PropType.duration),
                        "bothInvalid" to PropSchema(PropType.duration),
                        "missing" to PropSchema(PropType.duration),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    "event",
                    "Event",
                    props = mapOf(
                        "fromAmbiguous" to RawObject(
                            mapOf("from" to temporalPoint("ambiguous", 1)),
                        ),
                        "bothInvalid" to RawObject(
                            mapOf(
                                "from" to temporalPoint("ambiguous", 1),
                                "to" to temporalPoint("unknown", 2),
                            ),
                        ),
                        "missing" to RawObject(emptyMap()),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { it.message == "Ambiguous Timeline: ambiguous" })
        assertTrue(result.diagnostics.any { it.message == "Unknown Timeline: unknown" })
        assertEquals(
            listOf("missing duration must define from or to"),
            result.diagnostics.map { it.message }.filter { "duration must define from or to" in it },
        )
    }

    private fun instant(timeline: String, timecode: Long) = RawObject(
        mapOf(
            "timeline" to RawString(timeline),
            "timecode" to RawInteger(timecode),
        ),
    )

    private fun temporalPoint(timeline: String, timecode: Long) = RawObject(
        mapOf(
            "timeline" to RawString(timeline),
            "timecode" to RawInteger(timecode),
        ),
    )

    private fun duplicateDiagnostics(
        result: dev.usbharu.graphmd.core.model.GraphCompilationResult,
        kind: String,
        id: String,
    ) = result.diagnostics.filter { it.message == "$kind id must be unique: $id" }
}
