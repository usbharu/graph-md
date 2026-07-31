package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.DiagnosticCategory
import dev.usbharu.graphmd.core.model.NodeDocument
import dev.usbharu.graphmd.core.model.NodeTypeDocument
import dev.usbharu.graphmd.core.model.OffsetTimelineMapping
import dev.usbharu.graphmd.core.model.PropSchema
import dev.usbharu.graphmd.core.model.PropType
import dev.usbharu.graphmd.core.model.RawObject
import dev.usbharu.graphmd.core.model.RawString
import dev.usbharu.graphmd.core.model.RelTypeDocument
import dev.usbharu.graphmd.core.model.Severity
import dev.usbharu.graphmd.core.model.TimecodeSchema
import dev.usbharu.graphmd.core.model.TimecodeType
import dev.usbharu.graphmd.core.model.TimelineDocument
import dev.usbharu.graphmd.core.model.TimelineSelector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertySchemaInheritanceCompatibilityTest {
    @Test
    fun `accepts order independent timeline OR selectors and recursively equal items`() {
        val leftSchema = PropSchema(
            type = PropType.array,
            items = PropSchema(
                type = PropType.array,
                items = PropSchema(
                    type = PropType.instant,
                    timelines = listOf(TimelineSelector.Mapped("Root"), TimelineSelector.Id("Other")),
                ),
            ),
        )
        val rightSchema = PropSchema(
            type = PropType.array,
            items = PropSchema(
                type = PropType.array,
                items = PropSchema(
                    type = PropType.instant,
                    timelines = listOf(
                        TimelineSelector.Id("Other"),
                        TimelineSelector.Mapped("Root"),
                        TimelineSelector.Id("Other"),
                    ),
                ),
            ),
        )
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "Left",
                    props = mapOf(
                        "events" to leftSchema,
                        "optionalItems" to PropSchema(PropType.array),
                        "ignoredItems" to PropSchema(PropType.text, items = PropSchema(PropType.string)),
                    ),
                    sourcePath = "/types/left.md",
                ),
                NodeTypeDocument(
                    "Right",
                    props = mapOf(
                        "events" to rightSchema,
                        "optionalItems" to PropSchema(PropType.array, items = PropSchema(PropType.string)),
                        "ignoredItems" to PropSchema(PropType.text, items = PropSchema(PropType.number)),
                    ),
                    sourcePath = "/types/right.md",
                ),
                NodeTypeDocument("Both", extends = listOf("Left", "Right"), sourcePath = "/types/both.md"),
                NodeTypeDocument("BothReversed", extends = listOf("Right", "Left"), sourcePath = "/types/both-reversed.md"),
            ),
        )

        assertTrue(result.diagnostics.none { "Incompatible inherited prop schemas" in it.message })
        assertEquals(
            listOf(TimelineSelector.Mapped("Root"), TimelineSelector.Id("Other")),
            result.nodeTypes.single { it.id == "Both" }.props.getValue("events").items?.items?.timelines,
        )
        assertEquals(
            listOf(TimelineSelector.Id("Other"), TimelineSelector.Mapped("Root"), TimelineSelector.Id("Other")),
            result.nodeTypes.single { it.id == "BothReversed" }.props.getValue("events").items?.items?.timelines,
        )
        assertEquals(null, result.nodeTypes.single { it.id == "Both" }.props.getValue("optionalItems").items)
        assertEquals(null, result.nodeTypes.single { it.id == "Both" }.props.getValue("ignoredItems").items)
    }

    @Test
    fun `diagnoses incompatible type timeline and nested item schemas from multiple parents`() {
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "Left",
                    props = mapOf(
                        "typeDiff" to PropSchema(PropType.string),
                        "timelineDiff" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root")),
                        "nestedTypeDiff" to nested(PropSchema(PropType.string)),
                        "nestedTimelineDiff" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root")),
                        ),
                    ),
                    sourcePath = "/types/left.md",
                ),
                NodeTypeDocument(
                    "Right",
                    props = mapOf(
                        "typeDiff" to PropSchema(PropType.number),
                        "timelineDiff" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Other")),
                        "nestedTypeDiff" to nested(PropSchema(PropType.number)),
                        "nestedTimelineDiff" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Other")),
                        ),
                    ),
                    sourcePath = "/types/right.md",
                ),
                NodeTypeDocument("Both", extends = listOf("Left", "Right"), sourcePath = "/types/both.md"),
            ),
        )

        val warnings = result.diagnostics.filter {
            it.category == DiagnosticCategory.SchemaError &&
                it.severity == Severity.Warning &&
                it.source?.path == "/types/both.md" &&
                it.source.documentId == "Both" &&
                it.message.startsWith("Incompatible inherited prop schemas for ")
        }
        assertEquals(
            setOf("typeDiff", "timelineDiff", "nestedTypeDiff", "nestedTimelineDiff"),
            warnings.map { it.message.substringAfterLast(' ') }.toSet(),
        )
        val resolved = result.nodeTypes.single { it.id == "Both" }.props
        assertEquals(PropType.string, resolved.getValue("typeDiff").type)
        assertEquals(TimelineSelector.Id("Root"), resolved.getValue("timelineDiff").timeline)
        assertEquals(PropType.string, resolved.getValue("nestedTypeDiff").items?.items?.type)
    }

    @Test
    fun `diagnoses incompatible child refinements including missing and nested items`() {
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "Parent",
                    props = mapOf(
                        "events" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root")),
                        ),
                    ),
                    sourcePath = "/types/parent.md",
                ),
                NodeTypeDocument(
                    "Narrowed",
                    extends = listOf("Parent"),
                    props = mapOf(
                        "events" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Child")),
                        ),
                    ),
                    sourcePath = "/types/narrowed.md",
                ),
                NodeTypeDocument(
                    "MissingItems",
                    extends = listOf("Parent"),
                    props = mapOf("events" to PropSchema(PropType.array)),
                    sourcePath = "/types/missing-items.md",
                ),
                NodeTypeDocument(
                    "NestedType",
                    extends = listOf("Parent"),
                    props = mapOf("events" to PropSchema(PropType.array, items = PropSchema(PropType.number))),
                    sourcePath = "/types/nested-type.md",
                ),
                NodeTypeDocument(
                    "NestedTimeline",
                    extends = listOf("Parent"),
                    props = mapOf(
                        "events" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Other")),
                        ),
                    ),
                    sourcePath = "/types/nested-timeline.md",
                ),
                NodeTypeDocument(
                    "DifferentType",
                    extends = listOf("Parent"),
                    props = mapOf("events" to PropSchema(PropType.number)),
                    sourcePath = "/types/different-type.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none {
            it.source?.documentId == "Narrowed" && "Invalid refinement for prop events" in it.message
        })
        listOf("MissingItems", "NestedType", "NestedTimeline", "DifferentType").forEach { id ->
            val diagnostic = result.diagnostics.single {
                it.source?.documentId == id && it.message == "Invalid refinement for prop events"
            }
            assertEquals(DiagnosticCategory.SchemaError, diagnostic.category)
            assertEquals(Severity.Error, diagnostic.severity)
        }
        val inheritedSchema = result.nodeTypes.single { it.id == "Narrowed" }.props.getValue("events")
        assertEquals(TimelineSelector.Id("Root"), inheritedSchema.items?.timeline)
    }

    @Test
    fun `uses semantic subset direction for descendants mappings unresolved selectors and empty OR`() {
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "SemanticParent",
                    props = mapOf(
                        "descendant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root")),
                        "mapped" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("Root")),
                        "openNested" to PropSchema(PropType.array),
                    ),
                    sourcePath = "/types/semantic-parent.md",
                ),
                NodeTypeDocument(
                    "SemanticChild",
                    extends = listOf("SemanticParent"),
                    props = mapOf(
                        "descendant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Child")),
                        "mapped" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("MappedPeer")),
                        "openNested" to nested(
                            PropSchema(PropType.instant, timeline = TimelineSelector.Id("Child")),
                        ),
                    ),
                    sourcePath = "/types/semantic-child.md",
                ),
                NodeTypeDocument(
                    "NarrowTimelineParent",
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Child"))),
                    sourcePath = "/types/narrow-timeline-parent.md",
                ),
                NodeTypeDocument(
                    "WidenedTimelineChild",
                    extends = listOf("NarrowTimelineParent"),
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root"))),
                    sourcePath = "/types/widened-timeline-child.md",
                ),
                NodeTypeDocument(
                    "BroadTimelineParent",
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root"))),
                    sourcePath = "/types/broad-timeline-parent.md",
                ),
                NodeTypeDocument(
                    "BroadThenNarrow",
                    extends = listOf("BroadTimelineParent", "NarrowTimelineParent"),
                    sourcePath = "/types/broad-then-narrow.md",
                ),
                NodeTypeDocument(
                    "NarrowThenBroad",
                    extends = listOf("NarrowTimelineParent", "BroadTimelineParent"),
                    sourcePath = "/types/narrow-then-broad.md",
                ),
                NodeTypeDocument(
                    "UnknownParent",
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Missing"))),
                    sourcePath = "/types/unknown-parent.md",
                ),
                NodeTypeDocument(
                    "SameUnknownChild",
                    extends = listOf("UnknownParent"),
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Missing"))),
                    sourcePath = "/types/same-unknown-child.md",
                ),
                NodeTypeDocument(
                    "DifferentUnknownChild",
                    extends = listOf("UnknownParent"),
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("AlsoMissing"))),
                    sourcePath = "/types/different-unknown-child.md",
                ),
                NodeTypeDocument(
                    "EmptyParent",
                    props = mapOf("at" to PropSchema(PropType.instant, timelines = emptyList())),
                    sourcePath = "/types/empty-parent.md",
                ),
                NodeTypeDocument(
                    "NonemptyChild",
                    extends = listOf("EmptyParent"),
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root"))),
                    sourcePath = "/types/nonempty-child.md",
                ),
                NodeTypeDocument(
                    "NonemptyParent",
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root"))),
                    sourcePath = "/types/nonempty-parent.md",
                ),
                NodeTypeDocument(
                    "EmptyChild",
                    extends = listOf("NonemptyParent"),
                    props = mapOf("at" to PropSchema(PropType.instant, timelines = emptyList())),
                    sourcePath = "/types/empty-child.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none {
            it.source?.documentId == "SemanticChild" && it.message.startsWith("Invalid refinement")
        })
        assertTrue(result.diagnostics.any {
            it.source?.documentId == "WidenedTimelineChild" && it.message == "Invalid refinement for prop at"
        })
        listOf("BroadThenNarrow", "NarrowThenBroad").forEach { id ->
            assertTrue(result.diagnostics.none {
                it.source?.documentId == id && it.message == "Incompatible inherited prop schemas for at"
            })
        }
        assertTrue(result.diagnostics.none {
            it.source?.documentId == "SameUnknownChild" && it.message.startsWith("Invalid refinement")
        })
        assertTrue(result.diagnostics.any {
            it.source?.documentId == "DifferentUnknownChild" && it.message == "Invalid refinement for prop at"
        })
        assertTrue(result.diagnostics.any {
            it.source?.documentId == "NonemptyChild" && it.message == "Invalid refinement for prop at"
        })
        assertTrue(result.diagnostics.none {
            it.source?.documentId == "EmptyChild" && it.message.startsWith("Invalid refinement")
        })
        assertEquals(3, result.diagnostics.count { it.message.startsWith("Unknown Timeline:") })
        assertEquals(
            null,
            result.nodeTypes.single { it.id == "SemanticChild" }.props.getValue("openNested").items,
        )
    }

    @Test
    fun `applies recursive compatibility checks to relation property schemas`() {
        val result = compiler().compile(
            timelines() + listOf(
                RelTypeDocument(
                    "LeftRel",
                    props = mapOf("events" to PropSchema(PropType.array, items = PropSchema(PropType.string))),
                    sourcePath = "/rels/left.md",
                ),
                RelTypeDocument(
                    "RightRel",
                    props = mapOf("events" to PropSchema(PropType.array, items = PropSchema(PropType.number))),
                    sourcePath = "/rels/right.md",
                ),
                RelTypeDocument("BothRel", extends = listOf("LeftRel", "RightRel"), sourcePath = "/rels/both.md"),
                RelTypeDocument(
                    "ChildRel",
                    extends = listOf("LeftRel"),
                    props = mapOf("events" to PropSchema(PropType.array)),
                    sourcePath = "/rels/child.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any {
            it.source?.documentId == "BothRel" &&
                it.message == "Incompatible inherited prop schemas for events" &&
                it.severity == Severity.Warning
        })
        assertTrue(result.diagnostics.any {
            it.source?.documentId == "ChildRel" &&
                it.message == "Invalid refinement for prop events" &&
                it.severity == Severity.Error
        })
        assertEquals(PropType.string, result.relTypes.single { it.id == "BothRel" }.props.getValue("events").items?.type)
    }

    @Test
    fun `compares every parent schema pair and still aggregates required`() {
        val nodeParents = listOf(
            NodeTypeDocument(
                "N0",
                props = mapOf(
                    "items" to PropSchema(PropType.array, required = true),
                    "timeline" to PropSchema(
                        PropType.instant,
                        required = true,
                        timeline = TimelineSelector.Id("Root"),
                    ),
                ),
                sourcePath = "/types/n0.md",
            ),
            NodeTypeDocument(
                "N1",
                props = mapOf(
                    "items" to PropSchema(PropType.array, required = true, items = PropSchema(PropType.string)),
                    "timeline" to PropSchema(
                        PropType.instant,
                        required = true,
                        timeline = TimelineSelector.Id("ChildA"),
                    ),
                ),
                sourcePath = "/types/n1.md",
            ),
            NodeTypeDocument(
                "N2",
                props = mapOf(
                    "items" to PropSchema(PropType.array, items = PropSchema(PropType.number)),
                    "timeline" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildB")),
                ),
                sourcePath = "/types/n2.md",
            ),
        )
        val result = compiler().compile(
            timelines() + nodeParents + listOf(
                NodeTypeDocument("N012", extends = listOf("N0", "N1", "N2"), sourcePath = "/types/n012.md"),
                NodeTypeDocument("N201", extends = listOf("N2", "N0", "N1"), sourcePath = "/types/n201.md"),
            ),
        )

        listOf("N012", "N201").forEach { id ->
            val warnings = result.diagnostics.filter {
                it.source?.documentId == id && it.message.startsWith("Incompatible inherited prop schemas for ")
            }
            assertEquals(setOf("items", "timeline"), warnings.map { it.message.substringAfterLast(' ') }.toSet())
            assertEquals(2, warnings.size)
            assertEquals(false, result.nodeTypes.single { it.id == id }.props.getValue("items").required)
            assertEquals(false, result.nodeTypes.single { it.id == id }.props.getValue("timeline").required)
        }
        assertEquals(null, result.nodeTypes.single { it.id == "N012" }.props.getValue("items").items)
        assertEquals(PropType.number, result.nodeTypes.single { it.id == "N201" }.props.getValue("items").items?.type)
    }

    @Test
    fun `child refinements must satisfy every parent constraint for nodes and relations`() {
        val openItems = PropSchema(PropType.array)
        val stringItems = PropSchema(PropType.array, items = PropSchema(PropType.string))
        val numberItems = PropSchema(PropType.array, items = PropSchema(PropType.number))
        val rootTimeline = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root"))
        val childATimeline = PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildA"))
        val childBTimeline = PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildB"))
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "NodeOpen",
                    props = mapOf("items" to openItems, "timeline" to rootTimeline),
                    sourcePath = "/types/node-open.md",
                ),
                NodeTypeDocument(
                    "NodeNarrow",
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/types/node-narrow.md",
                ),
                NodeTypeDocument(
                    "NodeGood",
                    extends = listOf("NodeOpen", "NodeNarrow"),
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/types/node-good.md",
                ),
                NodeTypeDocument(
                    "NodeGoodReversed",
                    extends = listOf("NodeNarrow", "NodeOpen"),
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/types/node-good-reversed.md",
                ),
                NodeTypeDocument(
                    "NodeBad",
                    extends = listOf("NodeOpen", "NodeNarrow"),
                    props = mapOf("items" to numberItems, "timeline" to childBTimeline),
                    sourcePath = "/types/node-bad.md",
                ),
                NodeTypeDocument(
                    "NodeBadReversed",
                    extends = listOf("NodeNarrow", "NodeOpen"),
                    props = mapOf("items" to numberItems, "timeline" to childBTimeline),
                    sourcePath = "/types/node-bad-reversed.md",
                ),
                RelTypeDocument(
                    "RelOpen",
                    props = mapOf("items" to openItems, "timeline" to rootTimeline),
                    sourcePath = "/rels/rel-open.md",
                ),
                RelTypeDocument(
                    "RelNarrow",
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/rels/rel-narrow.md",
                ),
                RelTypeDocument(
                    "RelGood",
                    extends = listOf("RelOpen", "RelNarrow"),
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/rels/rel-good.md",
                ),
                RelTypeDocument(
                    "RelGoodReversed",
                    extends = listOf("RelNarrow", "RelOpen"),
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/rels/rel-good-reversed.md",
                ),
                RelTypeDocument(
                    "RelBad",
                    extends = listOf("RelOpen", "RelNarrow"),
                    props = mapOf("items" to numberItems, "timeline" to childBTimeline),
                    sourcePath = "/rels/rel-bad.md",
                ),
                RelTypeDocument(
                    "RelBadReversed",
                    extends = listOf("RelNarrow", "RelOpen"),
                    props = mapOf("items" to numberItems, "timeline" to childBTimeline),
                    sourcePath = "/rels/rel-bad-reversed.md",
                ),
            ),
        )

        listOf("NodeGood", "NodeGoodReversed", "RelGood", "RelGoodReversed").forEach { id ->
            assertTrue(result.diagnostics.none {
                it.source?.documentId == id && it.message.startsWith("Invalid refinement")
            })
        }
        listOf("NodeBad", "NodeBadReversed", "RelBad", "RelBadReversed").forEach { id ->
            assertEquals(
                setOf("items", "timeline"),
                result.diagnostics.filter {
                    it.source?.documentId == id && it.message.startsWith("Invalid refinement for prop ")
                }.map { it.message.substringAfterLast(' ') }.toSet(),
            )
        }
    }

    @Test
    fun `empty timeline OR rejects runtime values and parsed empty lists`() {
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "EmptyTimelineType",
                    props = mapOf("at" to PropSchema(PropType.instant, timelines = emptyList())),
                    sourcePath = "/types/empty-timeline.md",
                ),
                NodeDocument(
                    "event",
                    "EmptyTimelineType",
                    props = mapOf(
                        "at" to RawObject(
                            mapOf("timeline" to RawString("Root"), "value" to RawString("now")),
                        ),
                    ),
                    sourcePath = "/nodes/event.md",
                ),
            ),
        )
        assertTrue(result.diagnostics.any {
            it.source?.documentId == "event" && it.message == "at timeline Root is not allowed"
        })

        val parsed = compiler().parseDocument(
            """
                ---
                id: EmptyTimelineType
                kind: NodeType
                props:
                  at:
                    type: instant
                    timeline: []
                ---
            """.trimIndent(),
            "/types/parsed-empty-timeline.md",
        )
        assertTrue(parsed.diagnostics.any { it.message == "props.at.timeline MUST be a non-empty list" })
    }

    @Test
    fun `non-array items are validated but discarded from resolved schemas`() {
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "LeftIgnored",
                    props = mapOf(
                        "value" to PropSchema(
                            PropType.text,
                            items = PropSchema(
                                PropType.instant,
                                timeline = TimelineSelector.Id("MissingNestedTimeline"),
                            ),
                        ),
                    ),
                    sourcePath = "/types/left-ignored.md",
                ),
                NodeTypeDocument(
                    "RightIgnored",
                    props = mapOf("value" to PropSchema(PropType.text, items = PropSchema(PropType.number))),
                    sourcePath = "/types/right-ignored.md",
                ),
                NodeTypeDocument(
                    "BothIgnored",
                    extends = listOf("LeftIgnored", "RightIgnored"),
                    sourcePath = "/types/both-ignored.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any {
            it.source?.documentId == "LeftIgnored" && it.message == "Unknown Timeline: MissingNestedTimeline"
        })
        assertTrue(result.diagnostics.none {
            it.source?.documentId == "BothIgnored" && it.message.startsWith("Incompatible inherited prop schemas")
        })
        assertEquals(null, result.nodeTypes.single { it.id == "BothIgnored" }.props.getValue("value").items)
    }

    @Test
    fun `invalid child schemas do not pollute descendant constraints while valid refinements do`() {
        val parentItems = nested(PropSchema(PropType.string))
        val invalidItems = nested(PropSchema(PropType.number))
        val parentTimeline = PropSchema(
            PropType.array,
            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildA")),
        )
        val invalidTimeline = PropSchema(
            PropType.array,
            items = PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildB")),
        )
        val openItems = PropSchema(PropType.array)
        val stringItems = PropSchema(PropType.array, items = PropSchema(PropType.string))
        val numberItems = PropSchema(PropType.array, items = PropSchema(PropType.number))
        val rootTimeline = PropSchema(PropType.instant, timeline = TimelineSelector.Id("Root"))
        val childATimeline = PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildA"))
        val childBTimeline = PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildB"))
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "PollutionNodeParent",
                    props = mapOf("items" to parentItems, "timeline" to parentTimeline),
                    sourcePath = "/types/pollution-node-parent.md",
                ),
                NodeTypeDocument(
                    "PollutionNodeChild",
                    extends = listOf("PollutionNodeParent"),
                    props = mapOf("items" to invalidItems, "timeline" to invalidTimeline),
                    sourcePath = "/types/pollution-node-child.md",
                ),
                NodeTypeDocument(
                    "PollutionNodeGrandchild",
                    extends = listOf("PollutionNodeChild"),
                    props = mapOf("items" to parentItems, "timeline" to parentTimeline),
                    sourcePath = "/types/pollution-node-grandchild.md",
                ),
                RelTypeDocument(
                    "PollutionRelParent",
                    props = mapOf("items" to parentItems, "timeline" to parentTimeline),
                    sourcePath = "/rels/pollution-rel-parent.md",
                ),
                RelTypeDocument(
                    "PollutionRelChild",
                    extends = listOf("PollutionRelParent"),
                    props = mapOf("items" to invalidItems, "timeline" to invalidTimeline),
                    sourcePath = "/rels/pollution-rel-child.md",
                ),
                RelTypeDocument(
                    "PollutionRelGrandchild",
                    extends = listOf("PollutionRelChild"),
                    props = mapOf("items" to parentItems, "timeline" to parentTimeline),
                    sourcePath = "/rels/pollution-rel-grandchild.md",
                ),
                NodeTypeDocument(
                    "ValidNodeParent",
                    props = mapOf("items" to openItems, "timeline" to rootTimeline),
                    sourcePath = "/types/valid-node-parent.md",
                ),
                NodeTypeDocument(
                    "ValidNodeChild",
                    extends = listOf("ValidNodeParent"),
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/types/valid-node-child.md",
                ),
                NodeTypeDocument(
                    "ValidNodeGrandchild",
                    extends = listOf("ValidNodeChild"),
                    props = mapOf("items" to numberItems, "timeline" to childBTimeline),
                    sourcePath = "/types/valid-node-grandchild.md",
                ),
                RelTypeDocument(
                    "ValidRelParent",
                    props = mapOf("items" to openItems, "timeline" to rootTimeline),
                    sourcePath = "/rels/valid-rel-parent.md",
                ),
                RelTypeDocument(
                    "ValidRelChild",
                    extends = listOf("ValidRelParent"),
                    props = mapOf("items" to stringItems, "timeline" to childATimeline),
                    sourcePath = "/rels/valid-rel-child.md",
                ),
                RelTypeDocument(
                    "ValidRelGrandchild",
                    extends = listOf("ValidRelChild"),
                    props = mapOf("items" to numberItems, "timeline" to childBTimeline),
                    sourcePath = "/rels/valid-rel-grandchild.md",
                ),
            ),
        )

        listOf("PollutionNodeChild", "PollutionRelChild").forEach { id ->
            assertEquals(
                setOf("items", "timeline"),
                invalidRefinementProps(result, id),
            )
        }
        listOf("PollutionNodeGrandchild", "PollutionRelGrandchild").forEach { id ->
            assertEquals(emptySet(), invalidRefinementProps(result, id))
        }
        listOf("ValidNodeChild", "ValidRelChild").forEach { id ->
            assertEquals(emptySet(), invalidRefinementProps(result, id))
        }
        listOf("ValidNodeGrandchild", "ValidRelGrandchild").forEach { id ->
            assertEquals(setOf("items", "timeline"), invalidRefinementProps(result, id))
        }
        assertEquals(
            PropType.string,
            result.nodeTypes.single { it.id == "PollutionNodeGrandchild" }.props.getValue("items").items?.items?.type,
        )
        assertEquals(
            PropType.string,
            result.relTypes.single { it.id == "PollutionRelGrandchild" }.props.getValue("items").items?.items?.type,
        )
    }

    @Test
    fun `inherited conflicts are not re-reported without a new parent cross pair`() {
        val result = compiler().compile(
            timelines() + listOf(
                NodeTypeDocument(
                    "ConflictA",
                    props = mapOf("value" to PropSchema(PropType.string, required = true)),
                    sourcePath = "/types/conflict-a.md",
                ),
                NodeTypeDocument(
                    "ConflictB",
                    props = mapOf("value" to PropSchema(PropType.number)),
                    sourcePath = "/types/conflict-b.md",
                ),
                NodeTypeDocument(
                    "ConflictAOptional",
                    props = mapOf("value" to PropSchema(PropType.string, required = false)),
                    sourcePath = "/types/conflict-a-optional.md",
                ),
                NodeTypeDocument(
                    "ConflictC",
                    extends = listOf("ConflictA", "ConflictB"),
                    sourcePath = "/types/conflict-c.md",
                ),
                NodeTypeDocument(
                    "ConflictDeduplicated",
                    extends = listOf("ConflictA", "ConflictAOptional", "ConflictB"),
                    sourcePath = "/types/conflict-deduplicated.md",
                ),
                NodeTypeDocument(
                    "ConflictD",
                    extends = listOf("ConflictC"),
                    sourcePath = "/types/conflict-d.md",
                ),
                NodeTypeDocument(
                    "ConflictE",
                    extends = listOf("ConflictD"),
                    sourcePath = "/types/conflict-e.md",
                ),
                NodeTypeDocument(
                    "ConflictX",
                    props = mapOf("value" to PropSchema(PropType.text)),
                    sourcePath = "/types/conflict-x.md",
                ),
                NodeTypeDocument(
                    "ConflictWithNewParent",
                    extends = listOf("ConflictC", "ConflictX"),
                    sourcePath = "/types/conflict-with-new-parent.md",
                ),
            ),
        )

        assertEquals(1, inheritedConflictCount(result, "ConflictC", "value"))
        assertEquals(1, inheritedConflictCount(result, "ConflictDeduplicated", "value"))
        assertEquals(
            false,
            result.nodeTypes.single { it.id == "ConflictDeduplicated" }.props.getValue("value").required,
        )
        assertEquals(0, inheritedConflictCount(result, "ConflictD", "value"))
        assertEquals(0, inheritedConflictCount(result, "ConflictE", "value"))
        assertEquals(2, inheritedConflictCount(result, "ConflictWithNewParent", "value"))
    }

    @Test
    fun `canonical parent pairs make partially overlapping histories order independent`() {
        fun nodeBase(id: String, timeline: String) = NodeTypeDocument(
            id,
            props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id(timeline))),
            sourcePath = "/types/${id.lowercase()}.md",
        )
        fun relBase(id: String, timeline: String) = RelTypeDocument(
            id,
            props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id(timeline))),
            sourcePath = "/rels/${id.lowercase()}.md",
        )
        val result = compiler().compile(
            timelines() + listOf(
                nodeBase("OverlapNodeA", "Other"),
                nodeBase("OverlapNodeB", "ChildA"),
                nodeBase("OverlapNodeC", "ChildB"),
                nodeBase("OverlapNodeD", "Root"),
                NodeTypeDocument(
                    "OverlapNodeP1",
                    extends = listOf("OverlapNodeA", "OverlapNodeB", "OverlapNodeC"),
                    sourcePath = "/types/overlap-node-p1.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeP1Clone",
                    extends = listOf("OverlapNodeC", "OverlapNodeA", "OverlapNodeB"),
                    sourcePath = "/types/overlap-node-p1-clone.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeP2",
                    extends = listOf("OverlapNodeB", "OverlapNodeC", "OverlapNodeD"),
                    sourcePath = "/types/overlap-node-p2.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeSubset",
                    extends = listOf("OverlapNodeA", "OverlapNodeB"),
                    sourcePath = "/types/overlap-node-subset.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeForward",
                    extends = listOf("OverlapNodeP1", "OverlapNodeP2"),
                    sourcePath = "/types/overlap-node-forward.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeReverse",
                    extends = listOf("OverlapNodeP2", "OverlapNodeP1"),
                    sourcePath = "/types/overlap-node-reverse.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeIdentical",
                    extends = listOf("OverlapNodeP1", "OverlapNodeP1Clone"),
                    sourcePath = "/types/overlap-node-identical.md",
                ),
                NodeTypeDocument(
                    "OverlapNodeSubsetChild",
                    extends = listOf("OverlapNodeP1", "OverlapNodeSubset"),
                    sourcePath = "/types/overlap-node-subset-child.md",
                ),
                NodeTypeDocument(
                    "DisjointNodeForward",
                    extends = listOf("OverlapNodeA", "OverlapNodeB", "OverlapNodeC"),
                    sourcePath = "/types/disjoint-node-forward.md",
                ),
                NodeTypeDocument(
                    "DisjointNodeReverse",
                    extends = listOf("OverlapNodeC", "OverlapNodeB", "OverlapNodeA"),
                    sourcePath = "/types/disjoint-node-reverse.md",
                ),
                relBase("OverlapRelA", "Other"),
                relBase("OverlapRelB", "ChildA"),
                relBase("OverlapRelC", "ChildB"),
                relBase("OverlapRelD", "Root"),
                RelTypeDocument(
                    "OverlapRelP1",
                    extends = listOf("OverlapRelA", "OverlapRelB", "OverlapRelC"),
                    sourcePath = "/rels/overlap-rel-p1.md",
                ),
                RelTypeDocument(
                    "OverlapRelP2",
                    extends = listOf("OverlapRelB", "OverlapRelC", "OverlapRelD"),
                    sourcePath = "/rels/overlap-rel-p2.md",
                ),
                RelTypeDocument(
                    "OverlapRelForward",
                    extends = listOf("OverlapRelP1", "OverlapRelP2"),
                    sourcePath = "/rels/overlap-rel-forward.md",
                ),
                RelTypeDocument(
                    "OverlapRelReverse",
                    extends = listOf("OverlapRelP2", "OverlapRelP1"),
                    sourcePath = "/rels/overlap-rel-reverse.md",
                ),
            ),
        )

        listOf("OverlapNodeForward", "OverlapNodeReverse", "OverlapRelForward", "OverlapRelReverse").forEach { id ->
            assertEquals(1, inheritedConflictCount(result, id, "at"))
        }
        assertEquals(0, inheritedConflictCount(result, "OverlapNodeIdentical", "at"))
        assertEquals(0, inheritedConflictCount(result, "OverlapNodeSubsetChild", "at"))
        assertEquals(3, inheritedConflictCount(result, "DisjointNodeForward", "at"))
        assertEquals(3, inheritedConflictCount(result, "DisjointNodeReverse", "at"))
    }

    private fun nested(items: PropSchema): PropSchema =
        PropSchema(PropType.array, items = PropSchema(PropType.array, items = items))

    private fun invalidRefinementProps(result: dev.usbharu.graphmd.core.model.GraphCompilationResult, id: String): Set<String> =
        result.diagnostics.filter {
            it.source?.documentId == id && it.message.startsWith("Invalid refinement for prop ")
        }.map { it.message.substringAfterLast(' ') }.toSet()

    private fun inheritedConflictCount(
        result: dev.usbharu.graphmd.core.model.GraphCompilationResult,
        id: String,
        prop: String,
    ): Int = result.diagnostics.count {
        it.source?.documentId == id && it.message == "Incompatible inherited prop schemas for $prop"
    }

    private fun timelines() = listOf(
        TimelineDocument(
            "Root",
            timecode = TimecodeSchema(TimecodeType.number),
            sourcePath = "/timelines/root.md",
        ),
        TimelineDocument("Child", extends = listOf("Root"), sourcePath = "/timelines/child.md"),
        TimelineDocument("ChildA", extends = listOf("Root"), sourcePath = "/timelines/child-a.md"),
        TimelineDocument("ChildB", extends = listOf("Root"), sourcePath = "/timelines/child-b.md"),
        TimelineDocument(
            "MappedPeer",
            timecode = TimecodeSchema(TimecodeType.number),
            mappings = listOf(OffsetTimelineMapping(to = "Root", offset = 1.0)),
            sourcePath = "/timelines/mapped-peer.md",
        ),
        TimelineDocument("Other", sourcePath = "/timelines/other.md"),
    )

    private fun compiler() = GraphCompiler()
}
