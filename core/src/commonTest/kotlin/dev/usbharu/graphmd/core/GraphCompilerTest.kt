package dev.usbharu.graphmd.core
import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCompilerTest {
    private fun compiler(mode: ValidationMode = ValidationMode.Default) =
        GraphCompiler(CompileOptions(mode = mode))

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
                    body = "@link{}[Org](org friendOf)",
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
                    body = "@link{}[Bob](bob knows)",
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
    fun `reports every unknown RelType endpoint without requiring a link`() {
        val result = compiler().compile(
            listOf(
                RelTypeDocument(
                    id = "relatedTo",
                    from = listOf("Known", "Missing", "Missing"),
                    to = listOf("OtherMissing", "Known"),
                    sourcePath = "/tmp/related.md",
                ),
                NodeTypeDocument("Known", sourcePath = "/tmp/known.md"),
            ),
        )

        val referenceErrors = result.diagnostics.filter { it.category == DiagnosticCategory.ReferenceError }
        assertEquals(
            listOf(
                "Unknown NodeType: Missing",
                "Unknown NodeType: Missing",
                "Unknown NodeType: OtherMissing",
            ),
            referenceErrors.map { it.message },
        )
        assertTrue(referenceErrors.all { it.source == SourceInfo("/tmp/related.md", "relatedTo") })
        val relatedTo = result.relTypes.single()
        assertEquals(listOf("Known", "Missing", "Missing"), relatedTo.from)
        assertEquals(listOf("OtherMissing", "Known"), relatedTo.to)
        assertTrue(result.relations.isEmpty())
    }

    @Test
    fun `reports inherited unknown RelType endpoints only at their declaration`() {
        val result = compiler().compile(
            listOf(
                RelTypeDocument(
                    id = "child",
                    extends = listOf("parent"),
                    sourcePath = "/tmp/child.md",
                ),
                RelTypeDocument(
                    id = "parent",
                    from = listOf("Missing"),
                    sourcePath = "/tmp/parent.md",
                ),
            ),
        )

        val referenceErrors = result.diagnostics.filter {
            it.category == DiagnosticCategory.ReferenceError && it.message == "Unknown NodeType: Missing"
        }
        assertEquals(1, referenceErrors.size)
        assertEquals(SourceInfo("/tmp/parent.md", "parent"), referenceErrors.single().source)
        assertEquals(listOf("Missing"), result.relTypes.single { it.id == "child" }.from)
    }

    @Test
    fun `validates endpoints in every duplicate RelType definition`() {
        val result = compiler().compile(
            listOf(
                RelTypeDocument(
                    id = "duplicate",
                    from = listOf("MissingFrom"),
                    sourcePath = "/tmp/duplicate-a.md",
                ),
                RelTypeDocument(
                    id = "duplicate",
                    to = listOf("MissingTo"),
                    sourcePath = "/tmp/duplicate-b.md",
                ),
            ),
        )

        val referenceErrors = result.diagnostics.filter { it.category == DiagnosticCategory.ReferenceError }
        assertEquals(listOf("Unknown NodeType: MissingFrom", "Unknown NodeType: MissingTo"), referenceErrors.map { it.message })
        assertEquals(
            listOf("/tmp/duplicate-a.md", "/tmp/duplicate-b.md"),
            referenceErrors.map { it.source?.path },
        )
    }

    @Test
    fun `resolves retained noncanonical endpoint ids while recovering invalid ids`() {
        val result = compiler().compileSources(
            listOf(
                SourceDocument(
                    text = """
                        ---
                        id: relation
                        kind: RelType
                        from:
                          - "Known Type"
                          - ""
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/relation.md",
                ),
                SourceDocument(
                    text = """
                        ---
                        id: "Known Type"
                        kind: NodeType
                        ---
                    """.trimIndent(),
                    sourcePath = "/tmp/known-type.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { it.message == "from items MUST be non-empty" })
        assertTrue(result.diagnostics.any {
            it.severity == Severity.Warning &&
                it.message == "id MUST match [A-Za-z_][A-Za-z0-9_.:-]*" &&
                it.source?.documentId == "Known Type"
        })
        val referenceErrors = result.diagnostics.filter { it.category == DiagnosticCategory.ReferenceError }
        assertEquals(listOf("Unknown NodeType: "), referenceErrors.map { it.message })
        assertEquals(listOf("Known Type", ""), result.relTypes.single().from)
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
    fun `merges inherited required with AND and reports missing references`() {
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
        assertFalse(result.nodeTypes.single { it.id == "Person" }.props.getValue("name").required)
    }

    @Test
    fun `rejects noncanonical instant fields`() {
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
                                "timecode" to RawInteger(0),
                            )
                        ),
                    ),
                    sourcePath = "/tmp/alice.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "unknown fields: precision" in it.message })
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
    fun `normalizes canonical scalar text and array property types`() {
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Metric",
                    props = mapOf(
                        "code" to PropSchema(PropType.string, required = true),
                        "score" to PropSchema(PropType.number),
                        "label" to PropSchema(PropType.text),
                        "tags" to PropSchema(PropType.array, items = PropSchema(PropType.string)),
                    ),
                    sourcePath = "/tmp/metric-type.md",
                ),
                NodeDocument(
                    id = "m1",
                    type = "Metric",
                    props = mapOf(
                        "code" to RawString("m1"),
                        "score" to RawNumber(1.5),
                        "label" to RawObject(mapOf("ja" to RawString("指標"))),
                        "tags" to RawArray(listOf(RawString("a"), RawString("b"))),
                    ),
                    sourcePath = "/tmp/m1.md",
                ),
            )
        )

        val node = result.nodes.single()
        assertTrue(node.props.getValue("code") is StringValue)
        assertTrue(node.props.getValue("score") is NumberValue)
        assertTrue(node.props.getValue("label") is TextValue)
        assertTrue(node.props.getValue("tags") is ArrayValue)
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
                        "score" to PropSchema(PropType.number),
                        "tags" to PropSchema(PropType.array, items = PropSchema(PropType.string)),
                    ),
                    sourcePath = "/tmp/metric-type.md",
                ),
                NodeDocument(
                    id = "m1",
                    type = "Metric",
                    props = mapOf(
                        "code" to RawBoolean(true),
                        "score" to RawString("one"),
                        "tags" to RawString("bad"),
                    ),
                    sourcePath = "/tmp/m1.md",
                ),
            )
        )

        assertTrue(result.diagnostics.count { it.category == DiagnosticCategory.TypeError } >= 3)
    }

    @Test
    fun `drops invalid typed array elements without affecting valid schemaless nested or timed elements`() {
        val validTime = RawArray(listOf(RawObject(mapOf("timeline" to RawString("CommonEra")))))
        val result = compiler().compile(
            listOf(
                timeline(),
                NodeTypeDocument(
                    id = "Metric",
                    props = mapOf(
                        "tags" to PropSchema(PropType.array, items = PropSchema(PropType.string)),
                        "matrix" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.array, items = PropSchema(PropType.number)),
                        ),
                        "anything" to PropSchema(PropType.array),
                    ),
                    sourcePath = "/tmp/metric-type.md",
                ),
                NodeDocument(
                    id = "m1",
                    type = "Metric",
                    props = mapOf(
                        "tags" to RawArray(
                            listOf(
                                RawString("plain"),
                                RawInteger(1),
                                RawObject(
                                    mapOf(
                                        "value" to RawString("timed"),
                                        "validTime" to validTime,
                                    ),
                                ),
                                RawObject(
                                    mapOf(
                                        "value" to RawBoolean(false),
                                        "validTime" to validTime,
                                    ),
                                ),
                            ),
                        ),
                        "matrix" to RawArray(
                            listOf(
                                RawArray(listOf(RawInteger(1), RawString("bad"), RawNumber(2.5))),
                                RawString("not-an-array"),
                                RawArray(listOf(RawInteger(3))),
                            ),
                        ),
                        "anything" to RawArray(
                            listOf(
                                RawBoolean(true),
                                RawArray(listOf(RawString("nested"), RawInteger(4))),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/m1.md",
                ),
            ),
        )

        val node = result.nodes.single()
        val tags = node.props.getValue("tags") as ArrayValue
        assertEquals(listOf(StringValue("plain"), StringValue("timed")), tags.values)
        assertTrue(tags.elements[0].validTime.isEmpty())
        assertEquals("CommonEra", tags.elements[1].validTime.single().timeline)

        val matrix = node.props.getValue("matrix") as ArrayValue
        assertEquals(2, matrix.values.size)
        assertEquals(listOf(NumberValue(1.0), NumberValue(2.5)), (matrix.values[0] as ArrayValue).values)
        assertEquals(listOf(NumberValue(3.0)), (matrix.values[1] as ArrayValue).values)

        val anything = node.props.getValue("anything") as ArrayValue
        assertTrue(anything.values[0] is BooleanValue)
        assertEquals(
            listOf(StringValue("nested"), IntegerValue(4)),
            (anything.values[1] as ArrayValue).values,
        )

        assertEquals(2, result.diagnostics.count { it.message == "tags[] must be string" })
        assertEquals(1, result.diagnostics.count { it.message == "matrix[][] must be number" })
        assertEquals(1, result.diagnostics.count { it.message == "matrix[] must be array" })
        assertEquals(4, result.diagnostics.count { it.category == DiagnosticCategory.TypeError })
    }

    @Test
    fun `does not report required prop missing when its inline block has a syntax error`() {
        val result = compiler().compile(
            listOf(
                NodeTypeDocument(
                    id = "Person",
                    props = mapOf("age" to PropSchema(PropType.number, required = true)),
                    sourcePath = "/tmp/person-type.md",
                ),
                NodeDocument(
                    id = "alice",
                    type = "Person",
                    body = "@props{age = 14,age = 14}",
                    sourcePath = "/tmp/alice.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { it.message.startsWith("Duplicate key: age") })
        assertFalse(result.diagnostics.any { it.message == "Required property missing after normalization: age" })
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
                    body = "@link{}[Ghost](ghost missingRel)",
                    sourcePath = "/tmp/alice-b.md",
                ),
            )
        )

        assertTrue(result.diagnostics.any { "must be unique" in it.message })
        assertTrue(result.diagnostics.any { "Unknown RelType" in it.message })
        assertTrue(result.diagnostics.any { "Unknown Node target" in it.message })
    }

    @Test
    fun `distinguishes unresolved wrong-kind ambiguous and resolved references`() {
        fun referenceMessages(documents: List<GraphDocument>): List<String> =
            compiler().compile(documents).diagnostics
                .filter { it.category == DiagnosticCategory.ReferenceError }
                .map { it.message }

        assertEquals(
            listOf("Unknown NodeType: Missing"),
            referenceMessages(listOf(NodeDocument("alice", "Missing", sourcePath = "/tmp/unresolved.md"))),
        )
        assertEquals(
            listOf("Expected NodeType but found RelType: Shared"),
            referenceMessages(
                listOf(
                    RelTypeDocument("Shared", sourcePath = "/tmp/shared-rel.md"),
                    NodeDocument("alice", "Shared", sourcePath = "/tmp/wrong-kind.md"),
                ),
            ),
        )
        assertTrue(
            "Ambiguous NodeType reference: Shared" in referenceMessages(
                listOf(
                    NodeTypeDocument("Shared", sourcePath = "/tmp/shared-a.md"),
                    NodeTypeDocument("Shared", sourcePath = "/tmp/shared-b.md"),
                    NodeDocument("alice", "Shared", sourcePath = "/tmp/ambiguous.md"),
                ),
            ),
        )
        assertEquals(
            listOf("Expected Node but found NodeType, RelType: Shared"),
            referenceMessages(
                listOf(
                    NodeTypeDocument("Shared", sourcePath = "/tmp/shared-type.md"),
                    RelTypeDocument("Shared", sourcePath = "/tmp/shared-rel.md"),
                    RelTypeDocument("friendOf", sourcePath = "/tmp/friend-of.md"),
                    NodeTypeDocument("Person", sourcePath = "/tmp/person.md"),
                    NodeDocument(
                        "alice",
                        "Person",
                        body = "@link{}[Shared](Shared friendOf)",
                        sourcePath = "/tmp/mixed.md",
                    ),
                ),
            ),
        )
        assertTrue(
            referenceMessages(
                listOf(
                    NodeTypeDocument("Shared", sourcePath = "/tmp/shared-type.md"),
                    RelTypeDocument("Shared", sourcePath = "/tmp/shared-rel.md"),
                    NodeDocument("alice", "Shared", sourcePath = "/tmp/resolved-with-wrong-kind.md"),
                ),
            ).none { "Shared" in it },
        )
        assertEquals(
            emptyList(),
            referenceMessages(
                listOf(
                    NodeTypeDocument("Person", sourcePath = "/tmp/person.md"),
                    NodeDocument("alice", "Person", sourcePath = "/tmp/resolved.md"),
                ),
            ),
        )
    }

    @Test
    fun `Timeline references use candidate precedence across validation paths`() {
        fun validTimeMessages(candidates: List<GraphDocument>): List<String> =
            compiler().compile(
                candidates + NodeTypeDocument("Person", sourcePath = "/tmp/person.md") +
                    NodeDocument(
                        "alice",
                        "Person",
                        validTime = listOf(ValidTime("T")),
                        sourcePath = "/tmp/alice.md",
                    ),
            ).diagnostics.filter { it.category == DiagnosticCategory.ReferenceError }.map { it.message }

        assertEquals(listOf("Unknown Timeline: T"), validTimeMessages(emptyList()))
        assertEquals(
            listOf("Expected Timeline but found NodeType: T"),
            validTimeMessages(listOf(NodeTypeDocument("T", sourcePath = "/tmp/wrong.md"))),
        )
        assertTrue(
            "Ambiguous Timeline reference: T" in validTimeMessages(
                listOf(
                    TimelineDocument("T", sourcePath = "/tmp/t-a.md"),
                    TimelineDocument("T", sourcePath = "/tmp/t-b.md"),
                ),
            ),
        )
        assertTrue(
            validTimeMessages(
                listOf(
                    TimelineDocument("T", sourcePath = "/tmp/t.md"),
                    NodeTypeDocument("T", sourcePath = "/tmp/wrong-too.md"),
                ),
            ).none { "T" in it },
        )

        val allPaths = compiler().compile(
            listOf(
                NodeTypeDocument("SelectorWrong", sourcePath = "/tmp/selector-wrong.md"),
                NodeTypeDocument("MappingWrong", sourcePath = "/tmp/mapping-wrong.md"),
                NodeTypeDocument("InstantWrong", sourcePath = "/tmp/instant-wrong.md"),
                NodeTypeDocument("EndpointWrong", sourcePath = "/tmp/endpoint-wrong.md"),
                TimelineDocument(
                    "Base",
                    mappings = listOf(OffsetTimelineMapping(to = "MappingWrong", offset = 1.0)),
                    sourcePath = "/tmp/base.md",
                ),
                NodeTypeDocument(
                    "Event",
                    props = mapOf(
                        "instant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("SelectorWrong")),
                        "endpoint" to PropSchema(PropType.duration),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    "event",
                    "Event",
                    props = mapOf(
                        "instant" to RawObject(
                            mapOf("timeline" to RawString("InstantWrong"), "timecode" to RawInteger(1)),
                        ),
                        "endpoint" to RawObject(
                            mapOf(
                                "timeline" to RawString("Base"),
                                "from" to RawObject(
                                    mapOf("timeline" to RawString("EndpointWrong"), "timecode" to RawInteger(1)),
                                ),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        ).diagnostics.map { it.message }

        listOf("SelectorWrong", "MappingWrong", "InstantWrong", "EndpointWrong").forEach { id ->
            assertTrue("Expected Timeline but found NodeType: $id" in allPaths, allPaths.joinToString())
        }

        val commented = compiler().compileSources(
            listOf(
                SourceDocument(
                    """
                    ---
                    id: T
                    kind: Timeline
                    ---
                    """.trimIndent(),
                    "/tmp/t.md",
                ),
                SourceDocument(
                    """
                    ---
                    id: Person
                    kind: NodeType
                    ---
                    """.trimIndent(),
                    "/tmp/person.md",
                ),
                SourceDocument(
                    """
                    ---
                    id: commented
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: T # era
                    ---
                    """.trimIndent(),
                    "/tmp/commented.md",
                ),
            ),
        )
        assertTrue(commented.diagnostics.none { it.category == DiagnosticCategory.ReferenceError }, commented.diagnostics.joinToString())
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

        assertEquals(listOf("Person"), result.relTypes.single { it.id == "friendOf" }.from)
        assertTrue(result.diagnostics.any { "timeline and timelines" in it.message })
        assertTrue(result.diagnostics.any { "Unknown Timeline: Other" in it.message })
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
                    body = "@link{}[Bob](bob mentions)",
                    sourcePath = "/tmp/alice.md",
                    topLevelFields = setOf("id", "kind", "type", "props", "name"),
                ),
            )
        )

        assertEquals(1, result.relations.size)
        assertTrue(result.diagnostics.any { "MUST NOT define top-level field: name" in it.message })
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
                    ),
                    sourcePath = "/tmp/entity.md",
                ),
                NodeDocument(
                    id = "e1",
                    type = "Entity",
                    props = mapOf(
                        "badText" to RawObject(mapOf("default" to RawInteger(1))),
                        "arrayNoItems" to RawArray(listOf(RawNull, RawBoolean(true), RawObject(mapOf("x" to RawInteger(1))))),
                    ),
                    sourcePath = "/tmp/e1.md",
                ),
            )
        )

        assertTrue((result.nodes.single().props.getValue("badText") as TextValue).entries.getValue("default") is IntegerValue)
        assertTrue((result.nodes.single().props.getValue("arrayNoItems") as ArrayValue).values.first() is NullValue)
    }

    @Test
    fun `resolves offset mappings transitively in both directions and warns on inconsistency`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument(id = "A", sourcePath = "/tmp/a.md"),
                TimelineDocument(
                    id = "B",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping(from = "A", offset = 10.5)),
                    sourcePath = "/tmp/b.md",
                ),
                TimelineDocument(
                    id = "C",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(
                        OffsetTimelineMapping(from = "B", offset = 2.0),
                        OffsetTimelineMapping(from = "A", offset = 99.0),
                    ),
                    sourcePath = "/tmp/c.md",
                ),
            ),
        )

        val a = result.timelines.single { it.id == "A" }
        val b = result.timelines.single { it.id == "B" }
        assertEquals(10.5, a.mappedOffsets["B"])
        assertEquals(-10.5, b.mappedOffsets["A"])
        assertTrue(result.diagnostics.any { "Inconsistent offset mapping" in it.message })
    }

    @Test
    fun `unknown timeline mapping endpoints are diagnosed without stopping other mappings`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument(id = "A", sourcePath = "/tmp/a.md"),
                TimelineDocument(
                    id = "B",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(
                        OffsetTimelineMapping(from = "MissingFrom", offset = 1.0),
                        OffsetTimelineMapping(to = "MissingTo", offset = 2.0),
                        OffsetTimelineMapping(from = "A", offset = 3.0),
                    ),
                    sourcePath = "/tmp/b.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { it.message == "Unknown mapped Timeline: MissingFrom" })
        assertTrue(result.diagnostics.any { it.message == "Unknown mapped Timeline: MissingTo" })
        assertEquals(3.0, result.timelines.single { it.id == "A" }.mappedOffsets["B"])
        assertEquals(-3.0, result.timelines.single { it.id == "B" }.mappedOffsets["A"])
    }

    @Test
    fun `normalizes property variants and array element validTime with lexical fallback`() {
        val result = compiler().compileSources(
            listOf(
                SourceDocument(
                    """
                        ---
                        id: CommonEra
                        kind: Timeline
                        timecode:
                          type: number
                        ---
                    """.trimIndent(),
                    "/tmp/time.md",
                ),
                SourceDocument(
                    """
                        ---
                        id: Sample
                        kind: NodeType
                        props:
                          score:
                            type: number
                          values:
                            type: array
                            items: number
                        ---
                    """.trimIndent(),
                    "/tmp/type.md",
                ),
                SourceDocument(
                    """
                        ---
                        id: sample
                        kind: Node
                        type: Sample
                        validTime:
                          - timeline: CommonEra
                            from:
                              timecode: 0
                        props:
                          score:
                            - value: 1
                            - value: 2
                              validTime:
                                - timeline: CommonEra
                                  from:
                                    timecode: 10
                          values:
                            - 3
                            - value: 4
                              validTime:
                                - timeline: CommonEra
                                  from:
                                    timecode: 20
                        ---
                    """.trimIndent(),
                    "/tmp/node.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val node = result.nodes.single()
        val scores = node.propEntries.getValue("score")
        assertEquals(listOf(1.0, 2.0), scores.map { (it.value as NumberValue).value })
        assertEquals(0.0, scores[0].validTime.single().from?.timecode)
        assertEquals(10.0, scores[1].validTime.single().from?.timecode)
        val values = node.propEntries.getValue("values").single().value as ArrayValue
        assertEquals(0.0, values.elements[0].validTime.single().from?.timecode)
        assertEquals(20.0, values.elements[1].validTime.single().from?.timecode)
    }

    @Test
    fun `body props overwrite only the YAML assertion with identical validTime`() {
        val commonTime = RawArray(listOf(RawObject(mapOf("timeline" to RawString("CommonEra")))))
        val result = compiler().compile(
            listOf(
                TimelineDocument("CommonEra", sourcePath = "/tmp/common.md"),
                TimelineDocument("Branch", sourcePath = "/tmp/branch.md"),
                NodeTypeDocument("Sample", props = mapOf("age" to PropSchema(PropType.number)), sourcePath = "/tmp/type.md"),
                NodeDocument(
                    id = "sample",
                    type = "Sample",
                    props = mapOf(
                        "age" to RawArray(listOf(RawObject(mapOf("value" to RawInteger(1), "validTime" to commonTime)))),
                    ),
                    body = "@props{age(validTime=CommonEra)=2,age(validTime=Branch)=3}",
                    sourcePath = "/tmp/node.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val ages = result.nodes.single().propEntries.getValue("age")
        assertEquals(listOf(2.0, 3.0), ages.map { (it.value as NumberValue).value })
        assertEquals(listOf("CommonEra", "Branch"), ages.map { it.validTime.single().timeline })
    }

    @Test
    fun `compiler resolves noncanonical timeline ids parsed from body validTime`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument("Era@Branch", sourcePath = "/tmp/timeline.md"),
                NodeTypeDocument("Sample", props = mapOf("age" to PropSchema(PropType.number)), sourcePath = "/tmp/type.md"),
                NodeDocument(
                    id = "sample",
                    type = "Sample",
                    body = "@props{age(validTime=Era@Branch)=20}",
                    sourcePath = "/tmp/node.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        assertEquals("Era@Branch", result.nodes.single().propEntries.getValue("age").single().validTime.single().timeline)
    }

    @Test
    fun `body props keep fallback and timed assertions for the same property`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument("TimelineA", sourcePath = "/tmp/timeline-a.md"),
                NodeTypeDocument("Sample", props = mapOf("age" to PropSchema(PropType.number)), sourcePath = "/tmp/type.md"),
                NodeDocument(
                    id = "sample",
                    type = "Sample",
                    body = "@props{age=17,age(validTime = TimelineA) = 18}",
                    sourcePath = "/tmp/node.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val ages = result.nodes.single().propEntries.getValue("age")
        assertEquals(listOf(17.0, 18.0), ages.map { (it.value as NumberValue).value })
        assertTrue(ages[0].validTime.isEmpty())
        assertEquals("TimelineA", ages[1].validTime.single().timeline)
    }

    @Test
    fun `normalizes canonical instant and duration time points`() {
        val result = compiler().compileSources(
            listOf(
                SourceDocument(
                    """
                        ---
                        id: A
                        kind: Timeline
                        timecode:
                          type: number
                        ---
                    """.trimIndent(), "/tmp/a.md",
                ),
                SourceDocument(
                    """
                        ---
                        id: B
                        kind: Timeline
                        timecode:
                          type: number
                        mappings:
                          - from: A
                            kind: offset
                            offset: 10
                        ---
                    """.trimIndent(), "/tmp/b.md",
                ),
                SourceDocument(
                    """
                        ---
                        id: Event
                        kind: NodeType
                        props:
                          createdAt:
                            type: instant
                          active:
                            type: duration
                        ---
                    """.trimIndent(), "/tmp/type.md",
                ),
                SourceDocument(
                    """
                        ---
                        id: event
                        kind: Node
                        type: Event
                        props:
                          createdAt:
                            value: Today
                            timecode: 1.5
                          active:
                            timeline: A
                            from:
                              timeline: A
                              value: Start
                              timecode: 1
                            to:
                              timeline: B
                              timecode: 12
                        ---
                    """.trimIndent(), "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { it.severity == Severity.Error }, result.diagnostics.joinToString("\n") { it.message })
        val node = result.nodes.single()
        val instant = node.props.getValue("createdAt") as InstantValue
        assertEquals(1.5, (instant.timecode as NumberTimecode).value)
        assertEquals("Today", instant.value)
        assertEquals(null, instant.timeline)
        val duration = node.props.getValue("active") as DurationValue
        assertEquals("A", duration.timeline)
        assertEquals(1.0, duration.from?.timecode)
        assertEquals("B", duration.to?.timeline)
        assertTrue(result.diagnostics.none { "not mapped" in it.message })
    }

    @Test
    fun `rejects instant without numeric timecode and unmapped duration endpoints`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument("A", sourcePath = "/tmp/a.md"),
                TimelineDocument("B", sourcePath = "/tmp/b.md"),
                NodeTypeDocument(
                    "Event",
                    props = mapOf("at" to PropSchema(PropType.instant), "during" to PropSchema(PropType.duration)),
                    sourcePath = "/tmp/type.md",
                ),
                NodeDocument(
                    "event",
                    "Event",
                    props = mapOf(
                        "at" to RawString("today"),
                        "during" to RawObject(
                            mapOf(
                                "timeline" to RawString("A"),
                                "from" to RawObject(mapOf("timeline" to RawString("B"), "timecode" to RawInteger(1))),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "at.timecode must be number" in it.message })
        assertTrue(result.diagnostics.any { "not mapped" in it.message })
    }

    @Test
    fun `rejects unmapped duration endpoint timelines when duration timeline is omitted`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument("A", sourcePath = "/tmp/a.md"),
                TimelineDocument("B", sourcePath = "/tmp/b.md"),
                NodeTypeDocument(
                    "Event",
                    props = mapOf("during" to PropSchema(PropType.duration)),
                    sourcePath = "/tmp/type.md",
                ),
                NodeDocument(
                    "event",
                    "Event",
                    props = mapOf(
                        "during" to RawObject(
                            mapOf(
                                "from" to RawObject(mapOf("timeline" to RawString("A"), "timecode" to RawInteger(1))),
                                "to" to RawObject(mapOf("timeline" to RawString("B"), "timecode" to RawInteger(2))),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(
            result.diagnostics.any {
                it.category == DiagnosticCategory.ConstraintError &&
                    it.message == "during duration endpoint timelines A and B are not mapped"
            },
            result.diagnostics.joinToString("\n") { it.message },
        )
        val duration = result.nodes.single().props.getValue("during") as DurationValue
        assertEquals("A", duration.from?.timeline)
        assertEquals("B", duration.to?.timeline)
    }

    @Test
    fun `accepts same and transitively mapped duration endpoint timelines in either order`() {
        val durationProps = listOf("same", "direct", "transitive", "reverse", "subtype").associateWith {
            PropSchema(PropType.duration)
        }
        fun duration(from: String, to: String) = RawObject(
            mapOf(
                "from" to RawObject(mapOf("timeline" to RawString(from), "timecode" to RawInteger(1))),
                "to" to RawObject(mapOf("timeline" to RawString(to), "timecode" to RawInteger(2))),
            ),
        )
        val result = compiler().compile(
            listOf(
                TimelineDocument("A", sourcePath = "/tmp/a.md"),
                TimelineDocument(
                    "B",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping(from = "A", offset = 10.0)),
                    sourcePath = "/tmp/b.md",
                ),
                TimelineDocument(
                    "C",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping(from = "B", offset = 20.0)),
                    sourcePath = "/tmp/c.md",
                ),
                TimelineDocument("D", extends = listOf("C"), sourcePath = "/tmp/d.md"),
                NodeTypeDocument("Event", props = durationProps, sourcePath = "/tmp/type.md"),
                NodeDocument(
                    "event",
                    "Event",
                    props = mapOf(
                        "same" to duration("A", "A"),
                        "direct" to duration("A", "B"),
                        "transitive" to duration("A", "C"),
                        "reverse" to duration("C", "A"),
                        "subtype" to duration("D", "A"),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        assertTrue(
            result.diagnostics.none { "duration endpoint timeline" in it.message },
            result.diagnostics.joinToString("\n") { it.message },
        )
    }

    @Test
    fun `accepts inherited property timeline selector narrowed to a timeline subtype`() {
        val result = compiler().compile(
            listOf(
                TimelineDocument("ParentTime", sourcePath = "/tmp/parent-time.md"),
                TimelineDocument("ChildTime", extends = listOf("ParentTime"), sourcePath = "/tmp/child-time.md"),
                NodeTypeDocument(
                    "Parent",
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("ParentTime"))),
                    sourcePath = "/tmp/parent.md",
                ),
                NodeTypeDocument(
                    "Child",
                    extends = listOf("Parent"),
                    props = mapOf("at" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("ChildTime"))),
                    sourcePath = "/tmp/child.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.none { "Invalid refinement for prop at" in it.message })
        assertEquals(TimelineSelector.Id("ParentTime"), result.nodeTypes.single { it.id == "Child" }.props.getValue("at").timeline)
    }

    @Test
    fun `resolves relation target URL for Media nodes`() {
        val result = compiler().compile(
            listOf(
                NodeTypeDocument("Page", sourcePath = "/tmp/page-type.md"),
                RelTypeDocument("embeds", sourcePath = "/tmp/embeds.md"),
                NodeDocument(
                    "image",
                    "Page",
                    url = "https://example.com/image.png",
                    documentKind = DocumentKind.Media,
                    sourcePath = "/tmp/image.md",
                ),
                NodeDocument(
                    "page",
                    "Page",
                    body = "@link{}[image](image embeds)",
                    sourcePath = "/tmp/page.md",
                ),
            ),
        )

        assertEquals("https://example.com/image.png", result.relations.single().targetUrl)
    }

    @Test
    fun `rejects multiple fallback entries for a Property`() {
        val result = compiler().compile(
            listOf(
                NodeTypeDocument("Sample", props = mapOf("score" to PropSchema(PropType.number)), sourcePath = "/tmp/type.md"),
                NodeDocument(
                    "sample",
                    "Sample",
                    props = mapOf(
                        "score" to RawArray(
                            listOf(
                                RawObject(mapOf("value" to RawInteger(1))),
                                RawObject(mapOf("value" to RawInteger(2))),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/node.md",
                ),
            ),
        )

        assertTrue(result.diagnostics.any { "at most one entry without validTime" in it.message })
    }

    @Test
    fun `recursively applies validTime fallback to text and object members`() {
        val nodeTime = listOf(ValidTime("CommonEra"))
        val branchTime = RawArray(listOf(RawObject(mapOf("timeline" to RawString("Branch")))))
        val result = compiler().compile(
            listOf(
                TimelineDocument("CommonEra", sourcePath = "/tmp/common.md"),
                TimelineDocument("Branch", sourcePath = "/tmp/branch.md"),
                NodeTypeDocument("Sample", props = mapOf("labels" to PropSchema(PropType.text)), sourcePath = "/tmp/type.md"),
                NodeDocument(
                    "sample",
                    "Sample",
                    validTime = nodeTime,
                    props = mapOf(
                        "labels" to RawObject(
                            mapOf(
                                "plain" to RawString("current"),
                                "historic" to RawObject(
                                    mapOf("value" to RawString("old"), "validTime" to branchTime),
                                ),
                            ),
                        ),
                        "extra" to RawObject(
                            mapOf(
                                "nested" to RawObject(
                                    mapOf("value" to RawInteger(1), "validTime" to branchTime),
                                ),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/node.md",
                ),
            ),
        )

        val node = result.nodes.single()
        val text = node.props.getValue("labels") as TextValue
        assertEquals("CommonEra", text.memberEntries.getValue("plain").validTime.single().timeline)
        assertEquals("Branch", text.memberEntries.getValue("historic").validTime.single().timeline)
        val extra = node.props.getValue("extra") as ObjectValue
        assertEquals("Branch", extra.members.getValue("nested").validTime.single().timeline)
    }

    @Test
    fun `text members apply property timeline selector to inherited and overridden validTime`() {
        fun rawValidTime(timeline: String) = RawArray(
            listOf(RawObject(mapOf("timeline" to RawString(timeline)))),
        )
        fun member(value: String, timeline: String) = RawObject(
            mapOf(
                "value" to RawString(value),
                "validTime" to rawValidTime(timeline),
            ),
        )

        val result = compiler().compile(
            listOf(
                TimelineDocument("Allowed", sourcePath = "/tmp/allowed.md"),
                TimelineDocument("Other", sourcePath = "/tmp/other.md"),
                NodeTypeDocument(
                    "Sample",
                    props = mapOf(
                        "label" to PropSchema(PropType.text, timeline = TimelineSelector.Id("Allowed")),
                    ),
                    sourcePath = "/tmp/type.md",
                ),
                NodeDocument(
                    "valid",
                    "Sample",
                    validTime = listOf(ValidTime("Allowed")),
                    props = mapOf(
                        "label" to RawObject(
                            mapOf(
                                "inherited" to RawString("current"),
                                "overridden" to member("also current", "Allowed"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/valid.md",
                ),
                NodeDocument(
                    "invalid",
                    "Sample",
                    validTime = listOf(ValidTime("Other")),
                    props = mapOf(
                        "label" to RawObject(
                            mapOf(
                                "inheritedFirst" to RawString("old"),
                                "inheritedSecond" to RawString("also old"),
                                "allowedOverride" to member("current", "Allowed"),
                                "otherOverride" to member("old override", "Other"),
                                "unknownOverride" to member("unknown", "Missing"),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/invalid.md",
                ),
            ),
        )

        val invalidConstraints = result.diagnostics
            .filter { it.category == DiagnosticCategory.ConstraintError && it.source?.documentId == "invalid" }
            .map { it.message }
        assertEquals(
            setOf(
                "label.inheritedFirst validTime timeline Other is not allowed",
                "label.inheritedSecond validTime timeline Other is not allowed",
                "label.otherOverride validTime timeline Other is not allowed",
            ),
            invalidConstraints.toSet(),
        )
        assertTrue(result.diagnostics.none {
            it.category == DiagnosticCategory.ConstraintError && it.source?.documentId == "valid"
        })
        assertTrue(result.diagnostics.any {
            it.category == DiagnosticCategory.ReferenceError &&
                it.source?.documentId == "invalid" &&
                it.message == "Unknown Timeline: Missing"
        })
        assertTrue(invalidConstraints.none { "unknownOverride" in it })
    }

    @Test
    fun `array text items validate only their effective member timelines`() {
        fun rawValidTime(timeline: String) = RawArray(
            listOf(RawObject(mapOf("timeline" to RawString(timeline)))),
        )
        fun timed(value: RawValue, timeline: String) = RawObject(
            mapOf(
                "value" to value,
                "validTime" to rawValidTime(timeline),
            ),
        )

        val result = compiler().compile(
            listOf(
                TimelineDocument("Allowed", sourcePath = "/tmp/allowed.md"),
                TimelineDocument("Other", sourcePath = "/tmp/other.md"),
                NodeTypeDocument(
                    "Sample",
                    props = mapOf(
                        "labels" to PropSchema(
                            PropType.array,
                            items = PropSchema(PropType.text, timeline = TimelineSelector.Id("Allowed")),
                        ),
                    ),
                    sourcePath = "/tmp/type.md",
                ),
                NodeDocument(
                    "valid",
                    "Sample",
                    validTime = listOf(ValidTime("Allowed")),
                    props = mapOf(
                        "labels" to RawArray(
                            listOf(
                                RawString("current"),
                                RawObject(mapOf("ja" to RawString("現在"), "en" to RawString("current"))),
                                RawObject(emptyMap()),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/valid.md",
                ),
                NodeDocument(
                    "invalid",
                    "Sample",
                    validTime = listOf(ValidTime("Other")),
                    props = mapOf(
                        "labels" to RawArray(
                            listOf(
                                RawString("old"),
                                RawObject(
                                    mapOf(
                                        "inheritedJa" to RawString("旧名"),
                                        "inheritedEn" to RawString("Old"),
                                        "allowedOverride" to timed(RawString("current"), "Allowed"),
                                        "otherOverride" to timed(RawString("old override"), "Other"),
                                    ),
                                ),
                                RawObject(emptyMap()),
                                timed(
                                    RawObject(mapOf("elementOverride" to RawString("current"))),
                                    "Allowed",
                                ),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/invalid.md",
                ),
            ),
        )

        val invalidConstraints = result.diagnostics
            .filter { it.category == DiagnosticCategory.ConstraintError && it.source?.documentId == "invalid" }
            .map { it.message }
        assertEquals(
            setOf(
                "labels[] validTime timeline Other is not allowed",
                "labels[].inheritedJa validTime timeline Other is not allowed",
                "labels[].inheritedEn validTime timeline Other is not allowed",
                "labels[].otherOverride validTime timeline Other is not allowed",
            ),
            invalidConstraints.toSet(),
        )
        assertTrue(invalidConstraints.none { it == "labels[] validTime timeline Allowed is not allowed" })
        assertTrue(result.diagnostics.none {
            it.category == DiagnosticCategory.ConstraintError && it.source?.documentId == "valid"
        })
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
        assertFalse(result.relTypes.single { it.id == "ChildRel" }.props.getValue("label").required)
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
                    props = mapOf("name" to PropSchema(PropType.number)),
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
    fun `property validTime selectors apply to scalar array and nested array assertions`() {
        fun validTime(timeline: String) = listOf(ValidTime(timeline))
        fun rawValidTime(timeline: String) = RawArray(
            listOf(RawObject(mapOf("timeline" to RawString(timeline)))),
        )

        val result = compiler().compile(
            listOf(
                TimelineDocument("Allowed", sourcePath = "/tmp/allowed.md"),
                TimelineDocument("AllowedChild", extends = listOf("Allowed"), sourcePath = "/tmp/allowed-child.md"),
                TimelineDocument("Other", sourcePath = "/tmp/other.md"),
                NodeTypeDocument(
                    id = "Sample",
                    props = mapOf(
                        "number" to PropSchema(PropType.number, timeline = TimelineSelector.Id("Allowed")),
                        "string" to PropSchema(PropType.string, timeline = TimelineSelector.Id("Allowed")),
                        "text" to PropSchema(PropType.text, timeline = TimelineSelector.Id("Allowed")),
                        "array" to PropSchema(
                            PropType.array,
                            timeline = TimelineSelector.Id("Allowed"),
                            items = PropSchema(PropType.number, timeline = TimelineSelector.Id("Allowed")),
                        ),
                        "free" to PropSchema(PropType.number),
                        "unknownTimeline" to PropSchema(PropType.number, timeline = TimelineSelector.Id("Allowed")),
                    ),
                    sourcePath = "/tmp/sample-type.md",
                ),
                NodeDocument(
                    id = "valid",
                    type = "Sample",
                    validTime = validTime("AllowedChild"),
                    props = mapOf(
                        "number" to RawInteger(1),
                        "string" to RawString("value"),
                        "text" to RawString("value"),
                        "array" to RawArray(listOf(RawInteger(1))),
                        "free" to RawInteger(1),
                    ),
                    sourcePath = "/tmp/valid.md",
                ),
                NodeDocument(
                    id = "invalid",
                    type = "Sample",
                    validTime = validTime("Other"),
                    props = mapOf(
                        "number" to RawInteger(1),
                        "string" to RawString("value"),
                        "text" to RawString("value"),
                        "array" to RawArray(
                            listOf(
                                RawObject(
                                    mapOf(
                                        "value" to RawInteger(1),
                                        "validTime" to rawValidTime("Other"),
                                    ),
                                ),
                            ),
                        ),
                        "free" to RawInteger(1),
                        "schemaless" to RawInteger(1),
                        "unknownTimeline" to RawArray(
                            listOf(
                                RawObject(
                                    mapOf(
                                        "value" to RawInteger(1),
                                        "validTime" to rawValidTime("Missing"),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    sourcePath = "/tmp/invalid.md",
                ),
            ),
        )

        val invalidMessages = result.diagnostics
            .filter { it.category == DiagnosticCategory.ConstraintError && it.source?.documentId == "invalid" }
            .map { it.message }
        assertTrue(invalidMessages.any { "number validTime timeline Other is not allowed" in it })
        assertTrue(invalidMessages.any { "string validTime timeline Other is not allowed" in it })
        assertTrue(invalidMessages.any { "text validTime timeline Other is not allowed" in it })
        assertTrue(invalidMessages.any { "array validTime timeline Other is not allowed" in it })
        assertTrue(invalidMessages.any { "array[] validTime timeline Other is not allowed" in it })
        assertTrue(invalidMessages.none { "free" in it || "schemaless" in it })
        assertTrue(invalidMessages.none { "Missing" in it })
        assertTrue(result.diagnostics.any {
            it.category == DiagnosticCategory.ReferenceError &&
                it.source?.documentId == "invalid" &&
                it.message == "Unknown Timeline: Missing"
        })
        assertTrue(result.diagnostics.none {
            it.category == DiagnosticCategory.ConstraintError && it.source?.documentId == "valid"
        })
    }

    @Test
    fun `property validTime selector supports OR subtype and mapped timelines`() {
        fun rawAssertion(value: Long, vararg timelines: String) = RawObject(
            mapOf(
                "value" to RawInteger(value),
                "validTime" to RawArray(
                    timelines.map { timeline ->
                        RawObject(mapOf("timeline" to RawString(timeline)))
                    },
                ),
            ),
        )

        val result = compiler().compile(
            listOf(
                TimelineDocument("Base", sourcePath = "/tmp/base-time.md"),
                TimelineDocument("Child", extends = listOf("Base"), sourcePath = "/tmp/child-time.md"),
                TimelineDocument(
                    "Mapped",
                    timecode = TimecodeSchema(TimecodeType.number),
                    mappings = listOf(OffsetTimelineMapping(from = "Child", offset = 1.0)),
                    sourcePath = "/tmp/mapped.md",
                ),
                TimelineDocument("Other", sourcePath = "/tmp/other.md"),
                NodeTypeDocument(
                    id = "Sample",
                    props = mapOf(
                        "plain" to PropSchema(PropType.number, timeline = TimelineSelector.Id("Base")),
                        "mapped" to PropSchema(PropType.number, timeline = TimelineSelector.Mapped("Base")),
                    ),
                    sourcePath = "/tmp/sample-type.md",
                ),
                NodeDocument(
                    id = "sample",
                    type = "Sample",
                    props = mapOf(
                        "plain" to RawArray(listOf(rawAssertion(1, "Child"), rawAssertion(2, "Child", "Other"))),
                        "mapped" to RawArray(listOf(rawAssertion(1, "Mapped"), rawAssertion(2, "Other"))),
                    ),
                    sourcePath = "/tmp/sample.md",
                ),
            ),
        )

        val constraintMessages = result.diagnostics
            .filter { it.category == DiagnosticCategory.ConstraintError }
            .map { it.message }
        assertEquals(1, constraintMessages.count { it == "plain validTime timeline Other is not allowed" })
        assertEquals(1, constraintMessages.count { it == "mapped validTime timeline Other is not allowed" })
        assertTrue(constraintMessages.none { "Child is not allowed" in it || "Mapped is not allowed" in it })
    }

    @Test
    fun `instant and duration implicit and explicit timelines obey property selector`() {
        fun rawValidTime(timeline: String) = RawArray(
            listOf(RawObject(mapOf("timeline" to RawString(timeline)))),
        )
        fun assertion(value: RawValue, timeline: String) = RawArray(
            listOf(
                RawObject(
                    mapOf(
                        "value" to value,
                        "validTime" to rawValidTime(timeline),
                    ),
                ),
            ),
        )

        val result = compiler().compile(
            listOf(
                TimelineDocument("Allowed", sourcePath = "/tmp/allowed.md"),
                TimelineDocument("Other", sourcePath = "/tmp/other.md"),
                NodeTypeDocument(
                    id = "Event",
                    props = mapOf(
                        "implicitInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Allowed")),
                        "implicitDuration" to PropSchema(PropType.duration, timeline = TimelineSelector.Id("Allowed")),
                        "explicitInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Allowed")),
                        "explicitDuration" to PropSchema(PropType.duration, timeline = TimelineSelector.Id("Allowed")),
                        "absentInstant" to PropSchema(PropType.instant, timeline = TimelineSelector.Id("Allowed")),
                        "absentDuration" to PropSchema(PropType.duration, timeline = TimelineSelector.Id("Allowed")),
                    ),
                    sourcePath = "/tmp/event-type.md",
                ),
                NodeDocument(
                    id = "event",
                    type = "Event",
                    props = mapOf(
                        "implicitInstant" to assertion(RawInteger(1), "Other"),
                        "implicitDuration" to assertion(
                            RawObject(mapOf("from" to RawInteger(1), "to" to RawInteger(2))),
                            "Other",
                        ),
                        "explicitInstant" to RawObject(
                            mapOf("timeline" to RawString("Other"), "timecode" to RawInteger(1)),
                        ),
                        "explicitDuration" to RawObject(
                            mapOf("timeline" to RawString("Other"), "from" to RawInteger(1)),
                        ),
                        "absentInstant" to RawInteger(1),
                        "absentDuration" to RawObject(mapOf("from" to RawInteger(1))),
                    ),
                    sourcePath = "/tmp/event.md",
                ),
            ),
        )

        val constraintMessages = result.diagnostics
            .filter { it.category == DiagnosticCategory.ConstraintError }
            .map { it.message }
        assertTrue(constraintMessages.any { it == "implicitInstant validTime timeline Other is not allowed" })
        assertTrue(constraintMessages.any { it == "implicitDuration validTime timeline Other is not allowed" })
        assertTrue(constraintMessages.any { it == "explicitInstant timeline Other is not allowed" })
        assertTrue(constraintMessages.any { it == "explicitDuration timeline Other is not allowed" })
        assertTrue(constraintMessages.none { "absent" in it })
    }

    private fun timeline() = TimelineDocument(
        id = "CommonEra",
        timecode = TimecodeSchema(TimecodeType.number),
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
