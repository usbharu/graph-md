package dev.usbharu.graphmd.core
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCompilerTest {
    private fun compiler(mode: ValidationMode = ValidationMode.Default) =
        GraphCompiler(CompileOptions(mode = mode))

    @Test
    fun `compiles graph and normalizes defaults body props and relations`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                friendOfType(),
                NodeDocument(
                    id = "bob",
                    type = "Person",
                    props = mapOf("name" to RawString("Bob")),
                    sourcePath = "/tmp/bob.md",
                ),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf("name" to RawString("Initial")),
                    body = """
                        @props{
                          name = { default = "Alice", ja = "アリス" }
                          birthDate = { timeline = CommonEra, value = "2001-04-12" }
                        }
                        Alice is friends with @[Bob](bob friendOf){since = { timeline = CommonEra, value = "AD 2024-04-01" }}
                    """.trimIndent(),
                    sourcePath = "/tmp/alice.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val alice = result.nodes.single { it.id == "alice" }
        assertEquals("Alice", (alice.props.getValue("name") as TextValue).values.getValue("default"))
        assertEquals(null, (alice.props.getValue("birthDate") as InstantValue).precision)
        assertEquals("2001-04-12", (alice.props.getValue("birthDate") as InstantValue).value)
        assertEquals(1, result.relations.size)
        assertEquals("Bob", result.relations.single().sourceLabel)
    }

    @Test
    fun `normalizes instant scalar shortcut when schema fixes timeline`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                friendOfType(),
                NodeDocument(
                    id = "bob",
                    type = "Person",
                    props = mapOf("name" to RawString("Bob")),
                    sourcePath = "/tmp/bob.md",
                ),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf("name" to RawString("Alice")),
                    body = """@[Bob](bob "friendOf"){since = "2005-01-02"}""",
                    sourcePath = "/tmp/alice.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val since = result.relations.single().props.getValue("since") as InstantValue
        assertEquals("CommonEra", since.timeline)
        assertEquals("2005-01-02", since.value)
        assertEquals(null, since.precision)
    }

    @Test
    fun `stores numeric and tuple timecodes on temporal values`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument(
                    id = "ThirdAge",
                    timecode = TimecodeSchema(TimecodeType.tuple),
                    sourcePath = "/tmp/third-age.md",
                ),
                timeline(),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "happenedAt" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("ThirdAge")),
                        "activeDuring" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "happenedAt" to RawObject(
                            mapOf(
                                "value" to RawString("TA 3018-09-23"),
                                "timecode" to RawArray(listOf(RawInteger(3018), RawInteger(9), RawInteger(23))),
                            ),
                        ),
                        "activeDuring" to RawObject(
                            mapOf(
                                "from" to RawObject(
                                    mapOf(
                                        "value" to RawString("AD 2020-01-01"),
                                        "timecode" to RawNumber(2020.0),
                                        "precision" to RawString("day"),
                                    ),
                                ),
                                "to" to RawObject(
                                    mapOf(
                                        "value" to RawString("AD 2020-12-31"),
                                        "timecode" to RawNumber(2020.999),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val instant = result.nodes.single().props.getValue("happenedAt") as InstantValue
        assertEquals(listOf(3018.0, 9.0, 23.0), (instant.timecode as TupleTimecode).values)
        val interval = result.nodes.single().props.getValue("activeDuring") as IntervalValue
        assertEquals(2020.0, (interval.fromTimecode as NumberTimecode).value)
        assertEquals("day", interval.fromPrecision)
        assertEquals(2020.999, (interval.toTimecode as NumberTimecode).value)
    }

    @Test
    fun `rejects tuple timecode direction on timeline`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument(
                    id = "ThirdAge",
                    timecode = TimecodeSchema(TimecodeType.tuple, TimecodeDirection.ascending),
                    sourcePath = "/tmp/third-age.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "timecode.direction" in it.message })
    }

    @Test
    fun `enforces relation constraints`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                organizationType(),
                friendOfType(),
                NodeDocument("org", "Organization", mapOf("name" to RawString("Org")), sourcePath = "/tmp/org.md"),
                NodeDocument(
                    "alice",
                    "Person",
                    mapOf("name" to RawString("Alice")),
                    body = "@[Org](org friendOf)",
                    sourcePath = "/tmp/alice.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.ConstraintError && "target type" in it.message })
    }

    @Test
    fun `relation accepts node whose type transitively extends the declared from-to nodetype`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Entity",
                    props = mapOf("name" to PropSchema(PropType.text, required = true)),
                    sourcePath = "/tmp/entity.md",
                ),
                NodeTypeDocument(
                    id = "Character",
                    extends = listOf("Entity"),
                    props = emptyMap(),
                    sourcePath = "/tmp/character.md",
                ),
                NodeTypeDocument(
                    id = "Person",
                    extends = listOf("Character"),
                    props = mapOf("birthDate" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra"))),
                    sourcePath = "/tmp/person.md",
                ),
                RelTypeDocument(
                    id = "knows",
                    from = listOf("Entity"),
                    to = listOf("Entity"),
                    sourcePath = "/tmp/knows.md",
                ),
                NodeDocument(
                    "alice",
                    "Person",
                    mapOf("name" to RawString("Alice")),
                    body = "@[Bob](bob knows)",
                    sourcePath = "/tmp/alice.md",
                ),
                NodeDocument(
                    "bob",
                    "Person",
                    mapOf("name" to RawString("Bob")),
                    sourcePath = "/tmp/bob.md",
                ),
            )
        )

        assertFalse(
            result.diagnostics.any { it.category == DiagnosticCategory.ConstraintError },
            "Expected no constraint errors for subtype nodes, got: ${result.diagnostics}",
        )
    }

    @Test
    fun `applies inherited schemas and narrower rel constraints`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Entity",
                    props = mapOf("name" to PropSchema(PropType.text, required = true)),
                    sourcePath = "/tmp/entity.md",
                ),
                NodeTypeDocument(
                    id = "Person",
                    extends = listOf("Entity"),
                    props = mapOf("birthDate" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra"))),
                    sourcePath = "/tmp/person.md",
                ),
                RelTypeDocument(
                    id = "relatedTo",
                    from = listOf("Entity", "Person"),
                    to = listOf("Entity", "Person"),
                    sourcePath = "/tmp/related.md",
                ),
                RelTypeDocument(
                    id = "friendOf",
                    extends = listOf("relatedTo"),
                    from = listOf("Person"),
                    to = listOf("Person"),
                    sourcePath = "/tmp/friend.md",
                ),
            )
        )

        val person = result.nodeTypes.single { it.id == "Person" }
        assertTrue("name" in person.props)
        val friendOf = result.relTypes.single { it.id == "friendOf" }
        assertEquals(listOf("Person"), friendOf.from)
    }

    @Test
    fun `reports invalid schema refinements and missing references`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Entity",
                    props = mapOf("name" to PropSchema(PropType.text, required = true)),
                    sourcePath = "/tmp/entity.md",
                ),
                NodeTypeDocument(
                    id = "Person",
                    extends = listOf("Missing", "Entity"),
                    props = mapOf("name" to PropSchema(PropType.text, required = false)),
                    sourcePath = "/tmp/person.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.ReferenceError && "Missing" in it.message })
        assertTrue(result.diagnostics.any { it.category == DiagnosticCategory.SchemaError && "Invalid refinement" in it.message })
    }

    @Test
    fun `validates temporal precision and year zero`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf(
                        "name" to RawString("Alice"),
                        "birthDate" to RawObject(
                            mapOf(
                                "timeline" to RawString("CommonEra"),
                                "value" to RawString("AD 0-01-01"),
                                "precision" to RawString("month"),
                            )
                        ),
                    ),
                    sourcePath = "/tmp/alice.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { "precision" in it.message })
    }

    @Test
    fun `strict mode upgrades unknowns to errors`() {
        val result = compiler(ValidationMode.Strict).compile(
            listOf(
                timeline(),
                personType(),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf("name" to RawString("Alice"), "nickname" to RawString("Al")),
                    sourcePath = "/tmp/alice.md",
                    topLevelFields = setOf("id", "kind", "type", "props", "extraField"),
                ),
            )
        )

        assertTrue(result.diagnostics.any { it.severity == Severity.Error && "Unknown property nickname" in it.message })
        assertTrue(result.diagnostics.any { it.severity == Severity.Error && "Unknown top-level field: extraField" in it.message })
    }

    @Test
    fun `schema less values stay structural`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf(
                        "name" to RawString("Alice"),
                        "extra" to RawObject(mapOf("timeline" to RawString("CommonEra"), "value" to RawString("AD 2001-01-01"))),
                    ),
                    sourcePath = "/tmp/alice.md",
                ),
            )
        )

        assertTrue(result.nodes.single().props.getValue("extra") is ObjectValue)
    }

    @Test
    fun `interval without bounds warns and duration validates units`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "activeDuring" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("CommonEra")),
                        "duration" to PropSchema(PropType.duration, timeline = TimelineSelector.Id("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "activeDuring" to RawObject(mapOf("timeline" to RawString("CommonEra"))),
                        "duration" to RawObject(mapOf("unit" to RawString("century"), "value" to RawInteger(1))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "at least one bound" in it.message })
        assertTrue(result.diagnostics.none { "unit century" in it.message })
    }

    @Test
    fun `normalizes scalar array and object property types`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Metric",
                    props = mapOf(
                        "code" to PropSchema(PropType.string, required = true),
                        "rank" to PropSchema(PropType.integer),
                        "score" to PropSchema(PropType.number),
                        "active" to PropSchema(PropType.boolean),
                        "tags" to PropSchema(PropType.array, items = PropSchema(PropType.string)),
                        "meta" to PropSchema(
                            PropType.`object`,
                            properties = mapOf(
                                "owner" to PropSchema(PropType.string, required = true),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/metric-type.md",
                ),
                NodeDocument(
                    id = "m1",
                    type = "Metric",
                    props = mapOf(
                        "code" to RawString("m1"),
                        "rank" to RawInteger(1),
                        "score" to RawNumber(1.5),
                        "active" to RawBoolean(true),
                        "tags" to RawArray(listOf(RawString("a"), RawString("b"))),
                        "meta" to RawObject(mapOf("owner" to RawString("alice"))),
                    ),
                    sourcePath = "/tmp/m1.md",
                ),
            )
        )

        val node = result.nodes.single()
        assertTrue(node.props.getValue("code") is StringValue)
        assertTrue(node.props.getValue("rank") is IntegerValue)
        assertTrue(node.props.getValue("score") is NumberValue)
        assertTrue(node.props.getValue("active") is BooleanValue)
        assertTrue(node.props.getValue("tags") is ArrayValue)
        assertTrue(node.props.getValue("meta") is ObjectValue)
    }

    @Test
    fun `reports type errors for invalid shaped values`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Metric",
                    props = mapOf(
                        "code" to PropSchema(PropType.string),
                        "rank" to PropSchema(PropType.integer),
                        "active" to PropSchema(PropType.boolean),
                        "tags" to PropSchema(PropType.array, items = PropSchema(PropType.string)),
                        "meta" to PropSchema(PropType.`object`),
                    ),
                    sourcePath = "/tmp/metric-type.md",
                ),
                NodeDocument(
                    id = "m1",
                    type = "Metric",
                    props = mapOf(
                        "code" to RawBoolean(true),
                        "rank" to RawString("one"),
                        "active" to RawString("yes"),
                        "tags" to RawString("bad"),
                        "meta" to RawString("bad"),
                    ),
                    sourcePath = "/tmp/m1.md",
                ),
            )
        )

        assertTrue(result.diagnostics.count { it.category == DiagnosticCategory.TypeError } >= 5)
    }

    @Test
    fun `reports duplicate ids and unresolved relation references`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                NodeDocument("alice", "Person", mapOf("name" to RawString("Alice")), sourcePath = "/tmp/alice-a.md"),
                NodeDocument(
                    "alice",
                    "Person",
                    mapOf("name" to RawString("Alice2")),
                    body = "@[Ghost](ghost missingRel)",
                    sourcePath = "/tmp/alice-b.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "must be unique" in it.message })
        assertTrue(result.diagnostics.any { "Unknown RelType" in it.message })
        assertTrue(result.diagnostics.any { "Unknown Node target" in it.message })
    }

    @Test
    fun `reports invalid reltype narrowing and timeline schema errors`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument("Entity", sourcePath = "/tmp/entity.md"),
                NodeTypeDocument("Person", extends = listOf("Entity"), sourcePath = "/tmp/person.md"),
                RelTypeDocument(
                    id = "relatedTo",
                    from = listOf("Person"),
                    to = listOf("Person"),
                    sourcePath = "/tmp/related.md",
                ),
                RelTypeDocument(
                    id = "friendOf",
                    extends = listOf("relatedTo"),
                    from = listOf("Entity"),
                    to = listOf("Person"),
                    props = mapOf(
                        "when" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra"), timelines = listOf(TimelineSelector.Id("Other"))),
                    ),
                    sourcePath = "/tmp/friend.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "narrower than inherited" in it.message })
        assertTrue(result.diagnostics.any { "timeline and timelines" in it.message })
        assertTrue(result.diagnostics.any { "Unknown Timeline: Other" in it.message })
    }

    @Test
    fun `supports opaque timeline instant values`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument(
                    id = "ThirdAge",
                    sourcePath = "/tmp/third-age.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf("happenedAt" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("ThirdAge"))),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "happenedAt" to RawObject(
                            mapOf(
                                "timeline" to RawString("ThirdAge"),
                                "value" to RawString("TA 3018-09-23"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error })
        assertEquals("TA 3018-09-23", (result.nodes.single().props.getValue("happenedAt") as InstantValue).value)
    }

    @Test
    fun `reports missing nested required object property and invalid defaults`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Metric",
                    props = mapOf(
                        "meta" to PropSchema(
                            PropType.`object`,
                            properties = mapOf("owner" to PropSchema(PropType.string, required = true)),
                        ),
                        "name" to PropSchema(PropType.text, required = true, default = RawObject(mapOf("ja" to RawString("欠落")))),
                    ),
                    sourcePath = "/tmp/metric-type.md",
                ),
                NodeDocument(
                    id = "m1",
                    type = "Metric",
                    props = mapOf("meta" to RawObject(emptyMap())),
                    sourcePath = "/tmp/m1.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "meta.owner" in it.message })
        assertTrue(result.diagnostics.any { "Default value for name" in it.message })
    }

    @Test
    fun `reports cyclic inheritance`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument("A", extends = listOf("B"), sourcePath = "/tmp/a.md"),
                NodeTypeDocument("B", extends = listOf("A"), sourcePath = "/tmp/b.md"),
                RelTypeDocument("R1", extends = listOf("R2"), sourcePath = "/tmp/r1.md"),
                RelTypeDocument("R2", extends = listOf("R1"), sourcePath = "/tmp/r2.md"),
                TimelineDocument("T1", extends = listOf("T2"), sourcePath = "/tmp/t1.md"),
                TimelineDocument("T2", extends = listOf("T1"), sourcePath = "/tmp/t2.md"),
            )
        )

        assertTrue(result.diagnostics.any { "Cyclic NodeType inheritance" in it.message })
        assertTrue(result.diagnostics.any { "Cyclic RelType inheritance" in it.message })
        assertTrue(result.diagnostics.any { "Cyclic Timeline inheritance" in it.message })
    }

    @Test
    fun `accepts timeline lists and detects relation source violations`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                organizationType(),
                personType(),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf("happenedAt" to PropSchema(PropType.instant, timelines = listOf(TimelineSelector.Id("CommonEra")))),
                    sourcePath = "/tmp/event-type.md",
                ),
                RelTypeDocument(
                    id = "ownedBy",
                    from = listOf("Organization"),
                    to = listOf("Person"),
                    sourcePath = "/tmp/owned-by.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "happenedAt" to RawObject(
                            mapOf(
                                "timeline" to RawString("CommonEra"),
                                "value" to RawString("AD 2024-01-01"),
                            ),
                        ),
                    ),
                    body = "@[Alice](alice ownedBy)",
                    sourcePath = "/tmp/event.md",
                ),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf("name" to RawString("Alice")),
                    sourcePath = "/tmp/alice.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "source type" in it.message })
        assertEquals("CommonEra", (result.nodes.single { it.id == "event" }.props.getValue("happenedAt") as InstantValue).timeline)
    }

    @Test
    fun `allows unconstrained relation types and reserved top level fields are rejected`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                personType(),
                RelTypeDocument(
                    id = "mentions",
                    sourcePath = "/tmp/mentions.md",
                ),
                NodeDocument(
                    id = "bob",
                    type = "Person",
                    props = mapOf("name" to RawString("Bob")),
                    sourcePath = "/tmp/bob.md",
                ),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    props = mapOf("name" to RawString("Alice")),
                    body = "@[Bob](bob mentions)",
                    sourcePath = "/tmp/alice.md",
                    topLevelFields = setOf("id", "kind", "type", "props", "name"),
                ),
            )
        )

        assertEquals(1, result.relations.size)
        assertTrue(result.diagnostics.any { "MUST NOT define top-level field: name" in it.message })
    }

    @Test
    fun `covers instant error branches`() {
        val eventType = NodeTypeDocument(
            id = "Event",
            props = mapOf(
                "missingObject" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                "missingTimeline" to PropSchema(PropType.instant),
                "unknownTimeline" to PropSchema(PropType.instant),
                "disallowedTimeline" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                "missingValue" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                "opaqueLiteral" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                "timePrecision" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
            ),
            sourcePath = "/tmp/event-type.md",
        )
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(id = "AltTimeline", sourcePath = "/tmp/alt-timeline.md"),
                eventType,
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "missingObject" to RawString("bad"),
                        "missingTimeline" to RawObject(mapOf("value" to RawString("AD 2024-01-01"))),
                        "unknownTimeline" to RawObject(mapOf("timeline" to RawString("Missing"), "value" to RawString("AD 2024-01-01"))),
                        "disallowedTimeline" to RawObject(mapOf("timeline" to RawString("AltTimeline"), "value" to RawString("AD 2024-01-01"))),
                        "missingValue" to RawObject(mapOf("timeline" to RawString("CommonEra"))),
                        "opaqueLiteral" to RawObject(mapOf("timeline" to RawString("CommonEra"), "value" to RawString("not-a-date"))),
                        "timePrecision" to RawObject(mapOf("timeline" to RawString("CommonEra"), "value" to RawString("AD 2024-01-01T10:20:30"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.isNotEmpty())
        assertEquals(null, (result.nodes.single().props.getValue("timePrecision") as InstantValue).precision)
    }

    @Test
    fun `covers interval branches for validity and failures`() {
        val eventType = NodeTypeDocument(
            id = "Event",
            props = mapOf(
                "nonObject" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("CommonEra")),
                "missingTimeline" to PropSchema(PropType.interval),
                "unknownTimeline" to PropSchema(PropType.interval),
                "disallowedTimeline" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("CommonEra")),
                "fromOnly" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("CommonEra")),
                "withFlags" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("CommonEra")),
            ),
            sourcePath = "/tmp/event-type.md",
        )
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(id = "ThirdAge", sourcePath = "/tmp/third-age.md"),
                eventType,
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "nonObject" to RawString("bad"),
                        "missingTimeline" to RawObject(mapOf("from" to RawString("AD 2024-01-01"))),
                        "unknownTimeline" to RawObject(mapOf("timeline" to RawString("Missing"), "from" to RawString("AD 2024-01-01"))),
                        "disallowedTimeline" to RawObject(mapOf("timeline" to RawString("ThirdAge"), "from" to RawString("AD 2024-01-01"))),
                        "fromOnly" to RawObject(mapOf("timeline" to RawString("CommonEra"), "from" to RawString("AD 2024-01-01"))),
                        "withFlags" to RawObject(
                            mapOf(
                                "timeline" to RawString("CommonEra"),
                                "from" to RawString("AD 2024-01-01"),
                                "to" to RawString("AD 2024-12-31"),
                                "fromInclusive" to RawBoolean(false),
                                "toInclusive" to RawBoolean(true),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.isNotEmpty())
        val fromOnly = result.nodes.single().props.getValue("fromOnly") as IntervalValue
        assertEquals("AD 2024-01-01", fromOnly.from)
        val withFlags = result.nodes.single().props.getValue("withFlags") as IntervalValue
        assertEquals(false, withFlags.fromInclusive)
        assertEquals(true, withFlags.toInclusive)
    }

    @Test
    fun `covers duration branches for validity and failures`() {
        val eventType = NodeTypeDocument(
            id = "Event",
            props = mapOf(
                "nonObject" to PropSchema(PropType.duration),
                "missingUnit" to PropSchema(PropType.duration),
                "missingValue" to PropSchema(PropType.duration),
                "unknownTimeline" to PropSchema(PropType.duration),
                "timelineAny" to PropSchema(PropType.duration, timeline = TimelineSelector.Any),
                "valid" to PropSchema(PropType.duration, timeline = TimelineSelector.Id("CommonEra")),
            ),
            sourcePath = "/tmp/event-type.md",
        )
        val result = compiler().compile(
            listOf(
                timeline(),
                eventType,
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "nonObject" to RawString("bad"),
                        "missingUnit" to RawObject(mapOf("value" to RawInteger(1))),
                        "missingValue" to RawObject(mapOf("unit" to RawString("day"), "value" to RawString("bad"))),
                        "unknownTimeline" to RawObject(mapOf("unit" to RawString("day"), "value" to RawInteger(1), "timeline" to RawString("Missing"))),
                        "timelineAny" to RawObject(mapOf("unit" to RawString("day"), "value" to RawInteger(2))),
                        "valid" to RawObject(mapOf("unit" to RawString("day"), "value" to RawNumber(1.5))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.isNotEmpty())
        assertEquals(null, (result.nodes.single().props.getValue("timelineAny") as DurationValue).timeline)
        assertEquals("CommonEra", (result.nodes.single().props.getValue("valid") as DurationValue).timeline)
    }

    @Test
    fun `covers text map item schema and schemaless branches`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Entity",
                    props = mapOf(
                        "badText" to PropSchema(PropType.text),
                        "arrayNoItems" to PropSchema(PropType.array),
                        "objectNoSchema" to PropSchema(PropType.`object`),
                    ),
                    sourcePath = "/tmp/entity.md",
                ),
                NodeDocument(
                    id = "e1",
                    type = "Entity",
                    props = mapOf(
                        "badText" to RawObject(mapOf("default" to RawInteger(1))),
                        "arrayNoItems" to RawArray(listOf(RawNull, RawBoolean(true), RawObject(mapOf("x" to RawInteger(1))))),
                        "objectNoSchema" to RawObject(mapOf("k" to RawArray(listOf(RawString("v"))))),
                    ),
                    sourcePath = "/tmp/e1.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "badText text map values must be string" in it.message })
        assertTrue((result.nodes.single().props.getValue("arrayNoItems") as ArrayValue).values.first() is NullValue)
        assertTrue((result.nodes.single().props.getValue("objectNoSchema") as ObjectValue).values.getValue("k") is ArrayValue)
    }

    @Test
    fun `covers timeline inheritance and temporal literal branches`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument(
                    id = "Parent",
                    mappings = listOf(OffsetTimelineMapping("CommonEra", offset = 1)),
                    sourcePath = "/tmp/parent.md",
                ),
                TimelineDocument(
                    id = "Child",
                    extends = listOf("Parent"),
                    sourcePath = "/tmp/child.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "customInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Child")),
                        "customInterval" to PropSchema(PropType.interval, timeline = TimelineSelector.Id("Child")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "customInstant" to RawObject(mapOf("value" to RawString("AD 2024-01-01T10:20:30Z"))),
                        "customInterval" to RawObject(mapOf("from" to RawString("AD 2024-01"), "to" to RawString("AD 2024-12"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error })
        val child = result.timelines.single { it.id == "Child" }
        assertEquals(null, child.timecode)
        assertEquals(1, child.mappings.size)
        assertTrue(child.mappings.single() is OffsetTimelineMapping)
        assertEquals(null, (result.nodes.single().props.getValue("customInstant") as InstantValue).precision)
    }

    @Test
    fun `covers inheritance intersections and compatible refinements`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Base",
                    props = mapOf("name" to PropSchema(PropType.text)),
                    sourcePath = "/tmp/base.md",
                ),
                NodeTypeDocument(
                    id = "Child",
                    extends = listOf("Base"),
                    props = mapOf("name" to PropSchema(PropType.text, required = true)),
                    sourcePath = "/tmp/child.md",
                ),
                RelTypeDocument(
                    id = "ParentA",
                    from = listOf("Child", "Base"),
                    to = listOf("Child"),
                    sourcePath = "/tmp/parent-a.md",
                ),
                RelTypeDocument(
                    id = "ParentB",
                    from = listOf("Child"),
                    to = listOf("Child", "Base"),
                    sourcePath = "/tmp/parent-b.md",
                ),
                RelTypeDocument(
                    id = "ChildRel",
                    extends = listOf("ParentA", "ParentB"),
                    sourcePath = "/tmp/child-rel.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error })
        assertEquals(listOf("Child"), result.relTypes.single { it.id == "ChildRel" }.from)
        assertEquals(listOf("Child"), result.relTypes.single { it.id == "ChildRel" }.to)
    }

    @Test
    fun `covers infer precision variants and unknown warnings toggle`() {
        val result = GraphCompiler(
            CompileOptions(
                mode = ValidationMode.Default,
                emitUnknownPropertyWarnings = false,
            )
        ).compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "yearOnly" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                        "monthOnly" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                        "dayOnly" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "yearOnly" to RawObject(mapOf("value" to RawString("AD 2024"))),
                        "monthOnly" to RawObject(mapOf("value" to RawString("AD 2024-09"))),
                        "dayOnly" to RawObject(mapOf("value" to RawString("AD 2024-09-30"))),
                        "extra" to RawString("ignored"),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        val props = result.nodes.single().props
        assertEquals(null, (props.getValue("yearOnly") as InstantValue).precision)
        assertEquals(null, (props.getValue("monthOnly") as InstantValue).precision)
        assertEquals(null, (props.getValue("dayOnly") as InstantValue).precision)
        assertTrue(result.diagnostics.none { "Unknown property extra" in it.message })
    }

    @Test
    fun `covers additional schema and relation inheritance branches`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Left",
                    props = mapOf("name" to PropSchema(PropType.text)),
                    sourcePath = "/tmp/left.md",
                ),
                NodeTypeDocument(
                    id = "Right",
                    props = mapOf("name" to PropSchema(PropType.text)),
                    sourcePath = "/tmp/right.md",
                ),
                NodeTypeDocument(
                    id = "Both",
                    extends = listOf("Left", "Right"),
                    sourcePath = "/tmp/both.md",
                ),
                RelTypeDocument(
                    id = "OpenParent",
                    sourcePath = "/tmp/open-parent.md",
                ),
                RelTypeDocument(
                    id = "ClosedParent",
                    from = listOf("Both"),
                    to = listOf("Both"),
                    props = mapOf("label" to PropSchema(PropType.text)),
                    sourcePath = "/tmp/closed-parent.md",
                ),
                RelTypeDocument(
                    id = "ChildRel",
                    extends = listOf("OpenParent", "ClosedParent"),
                    props = mapOf("label" to PropSchema(PropType.text, required = true)),
                    sourcePath = "/tmp/child-rel.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error })
        assertTrue("name" in result.nodeTypes.single { it.id == "Both" }.props)
        assertTrue(result.relTypes.single { it.id == "ChildRel" }.props.getValue("label").required)
    }

    @Test
    fun `reports incompatible inherited parent schemas`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Left",
                    props = mapOf("name" to PropSchema(PropType.text)),
                    sourcePath = "/tmp/left.md",
                ),
                NodeTypeDocument(
                    id = "Right",
                    props = mapOf("name" to PropSchema(PropType.integer)),
                    sourcePath = "/tmp/right.md",
                ),
                NodeTypeDocument(
                    id = "Child",
                    extends = listOf("Left", "Right"),
                    sourcePath = "/tmp/child.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "Incompatible inherited prop schemas" in it.message })
    }

    @Test
    fun `covers unrestricted timelines custom calendars and to only interval`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(id = "Custom", sourcePath = "/tmp/custom.md"),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "freeInstant" to PropSchema(PropType.instant),
                        "toOnly" to PropSchema(PropType.interval),
                        "customInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Custom")),
                        "durationKnown" to PropSchema(PropType.duration),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "freeInstant" to RawObject(mapOf("timeline" to RawString("CommonEra"), "value" to RawString("AD 2024"))),
                        "toOnly" to RawObject(mapOf("timeline" to RawString("CommonEra"), "to" to RawString("AD 2024-12"))),
                        "customInstant" to RawObject(mapOf("value" to RawString("Season-9"))),
                        "durationKnown" to RawObject(mapOf("timeline" to RawString("Custom"), "unit" to RawString("chapter"), "value" to RawInteger(3))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error })
        assertEquals("Season-9", (result.nodes.single().props.getValue("customInstant") as InstantValue).value)
        assertEquals("AD 2024-12", (result.nodes.single().props.getValue("toOnly") as IntervalValue).to)
        assertEquals("Custom", (result.nodes.single().props.getValue("durationKnown") as DurationValue).timeline)
    }

    @Test
    fun `covers interval any and interval timeline lists`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(id = "ThirdAge", sourcePath = "/tmp/third-age.md"),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "intervalAny" to PropSchema(PropType.interval, timeline = TimelineSelector.Any),
                        "intervalList" to PropSchema(PropType.interval, timelines = listOf(TimelineSelector.Id("CommonEra"), TimelineSelector.Id("ThirdAge"))),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "intervalAny" to RawObject(mapOf("timeline" to RawString("CommonEra"), "from" to RawString("AD 2024-01-01"))),
                        "intervalList" to RawObject(mapOf("timeline" to RawString("ThirdAge"), "to" to RawString("TA 1"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            )
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error })
        assertEquals("CommonEra", (result.nodes.single().props.getValue("intervalAny") as IntervalValue).timeline)
        assertEquals("ThirdAge", (result.nodes.single().props.getValue("intervalList") as IntervalValue).timeline)
    }

    @Test
    fun `covers refinement type changes text default validation and timeline any`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Base",
                    props = mapOf("name" to PropSchema(PropType.text)),
                    sourcePath = "/tmp/base.md",
                ),
                NodeTypeDocument(
                    id = "Child",
                    extends = listOf("Base"),
                    props = mapOf(
                        "name" to PropSchema(PropType.integer),
                        "textMissingDefault" to PropSchema(PropType.text),
                        "preciseInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                        "anyInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Any),
                    ),
                    sourcePath = "/tmp/child.md",
                ),
                NodeDocument(
                    id = "child",
                    type = "Child",
                    props = mapOf(
                        "name" to RawInteger(1),
                        "textMissingDefault" to RawObject(mapOf("ja" to RawString("欠落"))),
                        "preciseInstant" to RawObject(
                            mapOf(
                                "value" to RawString("AD 2024-09-30"),
                                "precision" to RawString("day"),
                            ),
                        ),
                        "anyInstant" to RawObject(
                            mapOf(
                                "timeline" to RawString("CommonEra"),
                                "value" to RawString("AD 2024-01"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/child-node.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "Invalid refinement for prop name" in it.message })
        assertTrue(result.diagnostics.any { "textMissingDefault text map must define default" in it.message })
        assertEquals("day", (result.nodes.single().props.getValue("preciseInstant") as InstantValue).precision)
        assertEquals(null, (result.nodes.single().props.getValue("anyInstant") as InstantValue).precision)
    }

    @Test
    fun `covers empty compilation and open parent ordering`() {
        val empty = compiler().compile(emptyList())
        assertEquals(0, empty.nodes.size)
        assertEquals(0, empty.relations.size)

        val inherited = compiler().compile(
            listOf(
                timeline(),
                RelTypeDocument(
                    id = "ClosedParent",
                    from = listOf("Person"),
                    sourcePath = "/tmp/closed-parent.md",
                ),
                RelTypeDocument(
                    id = "OpenParent",
                    sourcePath = "/tmp/open-parent.md",
                ),
                RelTypeDocument(
                    id = "ChildRel",
                    extends = listOf("ClosedParent", "OpenParent"),
                    sourcePath = "/tmp/child-rel.md",
                ),
            )
        )

        assertEquals(listOf("Person"), inherited.relTypes.single { it.id == "ChildRel" }.from)
    }

    @Test
    fun `accepts subtimeline for ancestor requirement but not the reverse`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "CommonEraNarrow",
                    extends = listOf("CommonEra"),
                    sourcePath = "/tmp/common-era-narrow.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "parentAllowed" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                        "childOnly" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEraNarrow")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "parentAllowed" to RawObject(
                            mapOf(
                                "timeline" to RawString("CommonEraNarrow"),
                                "value" to RawString("AD 2024-01-01"),
                            ),
                        ),
                        "childOnly" to RawObject(
                            mapOf(
                                "timeline" to RawString("CommonEra"),
                                "value" to RawString("AD 2024-01-01"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertEquals("CommonEraNarrow", (result.nodes.single().props.getValue("parentAllowed") as InstantValue).timeline)
        assertTrue(result.diagnostics.any { "childOnly timeline CommonEra is not allowed" in it.message })
    }

    @Test
    fun `rejects incompatible timeline extends that should use mapping`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "BrokenChild",
                    extends = listOf("CommonEra"),
                    timecode = TimecodeSchema(TimecodeType.tuple),
                    sourcePath = "/tmp/broken-child.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "cannot change timecode schema" in it.message })
    }

    @Test
    fun `mapped timeline selector accepts a timeline that maps to target`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "ThirdAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping("CommonEra", offset = 1)),
                    sourcePath = "/tmp/third-age.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "mapped" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "mapped" to RawObject(mapOf("timeline" to RawString("ThirdAge"), "value" to RawString("TA 3018"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals("ThirdAge", (result.nodes.single().props.getValue("mapped") as InstantValue).timeline)
    }

    @Test
    fun `mapped timeline selector also accepts exact target and its subtimelines`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "CommonEraNarrow",
                    extends = listOf("CommonEra"),
                    sourcePath = "/tmp/common-era-narrow.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "exact" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("CommonEra")),
                        "sub" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "exact" to RawObject(mapOf("timeline" to RawString("CommonEra"), "value" to RawString("AD 2024-01-01"))),
                        "sub" to RawObject(mapOf("timeline" to RawString("CommonEraNarrow"), "value" to RawString("AD 2024-01-01"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals("CommonEra", (result.nodes.single().props.getValue("exact") as InstantValue).timeline)
        assertEquals("CommonEraNarrow", (result.nodes.single().props.getValue("sub") as InstantValue).timeline)
    }

    @Test
    fun `mapped timeline selector rejects an unrelated timeline`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "FourthAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    sourcePath = "/tmp/fourth-age.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "mapped" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "mapped" to RawObject(mapOf("timeline" to RawString("FourthAge"), "value" to RawString("FA 1"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "mapped timeline FourthAge is not allowed" in it.message })
    }

    @Test
    fun `timelines list combines identifier and mapped selectors`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "ThirdAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    sourcePath = "/tmp/third-age.md",
                ),
                TimelineDocument(
                    id = "Julian",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping("CommonEra", offset = 0)),
                    sourcePath = "/tmp/julian.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "viaId" to PropSchema(
                            PropType.instant,
                            timelines = listOf(TimelineSelector.Id("ThirdAge"), TimelineSelector.Mapped("CommonEra")),
                        ),
                        "viaMapped" to PropSchema(
                            PropType.instant,
                            timelines = listOf(TimelineSelector.Id("ThirdAge"), TimelineSelector.Mapped("CommonEra")),
                        ),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "viaId" to RawObject(mapOf("timeline" to RawString("ThirdAge"), "value" to RawString("TA 3018"))),
                        "viaMapped" to RawObject(mapOf("timeline" to RawString("Julian"), "value" to RawString("J 1"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals("ThirdAge", (result.nodes.single().props.getValue("viaId") as InstantValue).timeline)
        assertEquals("Julian", (result.nodes.single().props.getValue("viaMapped") as InstantValue).timeline)
    }

    @Test
    fun `mapped timeline selector with unknown target reports reference error`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "mapped" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("NoSuch")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "Unknown Timeline: NoSuch" in it.message && it.category == DiagnosticCategory.ReferenceError })
    }

    @Test
    fun `timeline mappings do not allow a value without a mapped selector`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "ThirdAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping("CommonEra", offset = 1)),
                    sourcePath = "/tmp/third-age.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "plain" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "plain" to RawObject(mapOf("timeline" to RawString("ThirdAge"), "value" to RawString("TA 3018"))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "plain timeline ThirdAge is not allowed" in it.message })
    }

    @Test
    fun `mapped timeline selector does not fix a concrete timeline for the bare string shortcut`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "mapped" to PropSchema(PropType.instant, timeline = TimelineSelector.Mapped("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf("mapped" to RawString("AD 2024-01-01")),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "mapped instant missing timeline" in it.message })
    }

    @Test
    fun `mapped timeline selector is enforced for duration values`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "ThirdAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping("CommonEra", offset = 1)),
                    sourcePath = "/tmp/third-age.md",
                ),
                TimelineDocument(
                    id = "FourthAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    sourcePath = "/tmp/fourth-age.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "lifespan" to PropSchema(PropType.duration, timeline = TimelineSelector.Mapped("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "lifespan" to RawObject(
                            mapOf(
                                "unit" to RawString("year"),
                                "value" to RawInteger(3),
                                "timeline" to RawString("ThirdAge"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals("ThirdAge", (result.nodes.single().props.getValue("lifespan") as DurationValue).timeline)

        val rejected = compiler().compile(
            listOf(
                timeline(),
                TimelineDocument(
                    id = "FourthAge",
                    timecode = TimecodeSchema(TimecodeType.number),
                    sourcePath = "/tmp/fourth-age.md",
                ),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "lifespan" to PropSchema(PropType.duration, timeline = TimelineSelector.Mapped("CommonEra")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "lifespan" to RawObject(
                            mapOf(
                                "unit" to RawString("year"),
                                "value" to RawInteger(3),
                                "timeline" to RawString("FourthAge"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(rejected.diagnostics.any { "lifespan timeline FourthAge is not allowed" in it.message })
    }

    private fun timeline() = TimelineDocument(
        id = "CommonEra",
        timecode = TimecodeSchema(TimecodeType.number, TimecodeDirection.ascending),
        sourcePath = "/tmp/timeline.md",
    )

    private fun personType() = NodeTypeDocument(
        id = "Person",
        props = mapOf(
            "name" to PropSchema(PropType.text, required = true),
            "birthDate" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra")),
        ),
        sourcePath = "/tmp/person.md",
    )

    private fun organizationType() = NodeTypeDocument(
        id = "Organization",
        props = mapOf("name" to PropSchema(PropType.text, required = true)),
        sourcePath = "/tmp/org-type.md",
    )

    private fun friendOfType() = RelTypeDocument(
        id = "friendOf",
        from = listOf("Person"),
        to = listOf("Person"),
        props = mapOf("since" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("CommonEra"))),
        sourcePath = "/tmp/friend.md",
    )
}
