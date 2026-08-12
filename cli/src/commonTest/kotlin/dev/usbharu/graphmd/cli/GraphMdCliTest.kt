package dev.usbharu.graphmd.cli

import dev.usbharu.graphmd.core.GraphCompiler
import dev.usbharu.graphmd.core.model.CalendarField
import dev.usbharu.graphmd.core.model.CalendarGranularity
import dev.usbharu.graphmd.core.model.CalendarKind
import dev.usbharu.graphmd.core.model.CompileOptions
import dev.usbharu.graphmd.core.model.SourceDocument
import dev.usbharu.graphmd.core.model.TemporalCoordinateSpec
import dev.usbharu.graphmd.core.model.ValidationMode
import dev.usbharu.graphmd.core.model.YearNumbering
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphMdCliTest {
    @Test
    fun `calendar pattern JSON preserves year numbering`() {
        fun json(numbering: YearNumbering): String = TemporalCoordinateSpec.CalendarPattern(
            CalendarKind.Gregorian,
            listOf(CalendarField.Year),
            numbering,
            CalendarGranularity.Year,
        ).toJson().encode()

        val common = json(YearNumbering.CommonEra)
        val astronomical = json(YearNumbering.Astronomical)
        val offset = json(YearNumbering.Offset(offset = 543, yearZero = false))

        assertTrue(common.contains("\"numbering\":\"common-era\""), common)
        assertTrue(astronomical.contains("\"numbering\":\"astronomical\""), astronomical)
        assertTrue(
            offset.contains("\"numbering\":{\"kind\":\"offset\",\"offset\":543,\"yearZero\":false}"),
            offset,
        )
        assertFalse(common == astronomical)
        assertFalse(common == offset)
    }

    @Test
    fun `demo generates a valid minimum dataset and reports its seed`() {
        val fs = FakeFileSystem(emptyMap())

        val result = GraphMdCli(fs).run(listOf("demo", "/demo", "--count", "3", "--seed", "42", "--json"))

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"requestedCount\":3"))
        assertTrue(result.stdout.contains("\"generatedCount\":24"))
        assertTrue(result.stdout.contains("\"seed\":42"))
        assertEquals(24, fs.contentsUnder("/demo").size)
        val generated = fs.contentsUnder("/demo").values
        assertEquals(18, generated.count { "kind: Timeline" in it })
        assertTrue(generated.count { "kind: NodeType" in it } >= 2)
        assertTrue(generated.count { "kind: RelType" in it } >= 2)
        assertTrue(generated.any { "kind: Media" in it })
        assertTrue(generated.any { "@link(" in it })
        assertTrue(generated.any { "この" in it })
        assertTrue(generated.any { "This " in it })
    }

    @Test
    fun `demo timelines cover the complete temporal authoring surface`() {
        val plan = DemoGenerator.plan(requestedCount = 24, requestedSeed = 42)
        val timelines = plan.documents().filter { it.kind == CliKind.Timeline }.map { it.text }.toList()
        val generated = timelines.joinToString("\n")

        assertEquals(18, timelines.size)
        listOf("sameAxisAs:", "scale:", "offset:", "aliases:", "props:", "domain:").forEach {
            assertTrue(it in generated, "Missing Timeline field: $it")
        }
        listOf("gregorian", "julian", "kind: era", "kind: frame", "kind: timecode").forEach {
            assertTrue(it in generated, "Missing coordinate feature: $it")
        }
        listOf("fork", "simulation", "recording", "edit", "resample", "copy", "derived").forEach {
            val present = if (it == "derived") {
                Regex("derivedFrom: Timeline_[0-9]+").containsMatchIn(generated)
            } else {
                "kind: $it" in generated
            }
            assertTrue(present, "Missing lineage kind: $it")
        }
        listOf("isomorphism", "alignment", "correspondence", "projection", "embedding", "coercion").forEach {
            assertTrue("kind: $it" in generated, "Missing Mapping kind: $it")
        }
        listOf(
            "mapsTo: Timeline_",
            "    id: DemoMap_",
            "segments:",
            "pairs:",
            "approximate",
            "uncertain",
            "traits:",
            "requiredContext:",
            "provenance:",
        ).forEach {
            assertTrue(it in generated, "Missing Mapping feature: $it")
        }
    }

    @Test
    fun `demo is reproducible with a seed and varies with another seed`() {
        val first = FakeFileSystem(emptyMap())
        val second = FakeFileSystem(emptyMap())
        val different = FakeFileSystem(emptyMap())

        assertEquals(0, GraphMdCli(first).run(listOf("demo", "/demo", "--count", "20", "--seed", "7")).exitCode)
        assertEquals(0, GraphMdCli(second).run(listOf("demo", "/demo", "--count", "20", "--seed", "7")).exitCode)
        assertEquals(0, GraphMdCli(different).run(listOf("demo", "/demo", "--count", "20", "--seed", "8")).exitCode)

        assertEquals(first.contentsUnder("/demo"), second.contentsUnder("/demo"))
        assertFalse(first.contentsUnder("/demo") == different.contentsUnder("/demo"))
    }

    @Test
    fun `streamed demo documents pass strict validation with multi-parent inheritance`() {
        val plan = DemoGenerator.plan(requestedCount = 250, requestedSeed = 99)
        val compilation = GraphCompiler(CompileOptions(mode = ValidationMode.Strict)).compileSources(
            plan.documents().map { SourceDocument(it.text, it.fileName) }.toList(),
        )

        assertEquals(250, plan.generatedCount)
        assertTrue(compilation.diagnostics.isEmpty(), compilation.diagnostics.joinToString("\n") { it.message })
    }

    @Test
    fun `million document plan stays lazy and derives final file name`() {
        val plan = DemoGenerator.plan(requestedCount = 1_000_000, requestedSeed = 42)

        assertEquals(1_000_000, plan.generatedCount)
        assertEquals(3, plan.documents().take(3).count())
        assertTrue(plan.fileNameAt(plan.generatedCount - 1).startsWith("media-"))
    }

    @Test
    fun `demo rejects invalid arguments and non-empty output`() {
        val invalidCount = GraphMdCli(FakeFileSystem(emptyMap())).run(listOf("demo", "/demo", "--count", "0"))
        val missingCount = GraphMdCli(FakeFileSystem(emptyMap())).run(listOf("demo", "/demo"))
        val nonEmptyFs = FakeFileSystem(mapOf("/demo/keep.txt" to "keep"))
        val nonEmpty = GraphMdCli(nonEmptyFs).run(listOf("demo", "/demo", "--count", "8"))

        assertEquals(1, invalidCount.exitCode)
        assertEquals(1, missingCount.exitCode)
        assertEquals(1, nonEmpty.exitCode)
        assertEquals(mapOf("/demo/keep.txt" to "keep"), nonEmptyFs.contentsUnder("/demo"))
    }

    @Test
    fun `demo removes files and a newly created directory after write failure`() {
        val fs = FakeFileSystem(emptyMap(), failWriteAt = 2)

        val result = GraphMdCli(fs).run(listOf("demo", "/demo", "--count", "8", "--seed", "42"))

        assertEquals(1, result.exitCode)
        assertTrue(fs.contentsUnder("/demo").isEmpty())
        assertEquals(null, fs.kind("/demo"))
    }

    @Test
    fun `list supports repeated kind and type filters`() {
        val cli = fixtureCli()

        val result = cli.run(
            listOf(
                "list",
                "/workspace",
                "--kind",
                "node",
                "--kind=media",
                "--type",
                "Person",
                "--json",
            ),
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"id\":\"alice\""))
        assertTrue(result.stdout.contains("\"id\":\"portrait\""))
        assertFalse(result.stdout.contains("\"kind\":\"node-type\""))
    }

    @Test
    fun `include derived matches child node types`() {
        val cli = fixtureCli()

        val exact = cli.run(listOf("list", "/workspace", "--kind", "node", "--type", "Entity", "--json"))
        val derived = cli.run(
            listOf("list", "/workspace", "--kind", "node", "--type", "Entity", "--include-derived", "--json"),
        )

        assertEquals("[]\n", exact.stdout)
        assertTrue(derived.stdout.contains("\"id\":\"alice\""))
    }

    @Test
    fun `show reports ambiguous IDs and requests kind`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/alice-type.md" to nodeType("alice"),
                "/workspace/alice.md" to node("alice", "Person"),
            ),
        )
        val cli = GraphMdCli(fs)

        val ambiguous = cli.run(listOf("show", "alice", "/workspace", "--json"))
        val selected = cli.run(listOf("show", "alice", "/workspace", "--kind", "node", "--json"))

        assertEquals(1, ambiguous.exitCode)
        assertTrue(ambiguous.stderr.contains("ambiguous"))
        assertTrue(selected.stdout.contains("\"kind\":\"node\""))
    }

    @Test
    fun `directory discovery ignores plain markdown but explicit files are linted`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/README.md" to "# ordinary markdown",
            ),
        )
        val cli = GraphMdCli(fs)

        val discovered = cli.run(listOf("lint", "/workspace", "--json"))
        val explicit = cli.run(listOf("lint", "/workspace/README.md", "--json"))

        assertEquals(0, discovered.exitCode)
        assertEquals("[]\n", discovered.stdout)
        assertEquals(1, explicit.exitCode)
        assertTrue(explicit.stdout.contains("Document MUST start with YAML front matter"))
    }

    @Test
    fun `directory discovery excludes generated directories and symbolic links`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/build/generated.md" to node("generated", "Person"),
                "/workspace/linked/linked.md" to node("linked", "Person"),
            ),
            aliases = mapOf("/workspace/linked" to "/outside"),
        )

        val result = GraphMdCli(fs).run(listOf("list", "/workspace", "--json"))

        assertTrue(result.stdout.contains("\"id\":\"Person\""))
        assertFalse(result.stdout.contains("generated"))
        assertFalse(result.stdout.contains("linked"))
    }

    @Test
    fun `query emits partial data on stdout and diagnostics on stderr`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/broken.md" to node("broken", "MissingType"),
            ),
        )

        val result = GraphMdCli(fs).run(listOf("list", "/workspace", "--json"))

        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("\"id\":\"broken\""))
        assertTrue(result.stderr.startsWith("["))
        assertTrue(result.stderr.contains("Unknown NodeType"))
    }

    @Test
    fun `props emits every property entry`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      name:
                        type: string
                    ---
                """.trimIndent(),
                "/workspace/time.md" to """
                    ---
                    id: TimelineA
                    kind: Timeline
                    ---
                """.trimIndent(),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    ---
                    @props{name = "Alice",name(validTime=TimelineA) = "Alicia"}
                """.trimIndent(),
            ),
        )

        val result = GraphMdCli(fs).run(listOf("props", "alice", "/workspace", "--json"))

        assertEquals(0, result.exitCode)
        assertEquals(2, "\"name\":\"name\"".toRegex().findAll(result.stdout).count())
    }

    @Test
    fun `human readable properties render values and valid time ranges`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      anytime:
                        type: string
                      bounded:
                        type: string
                      count:
                        type: number
                      escaped:
                        type: string
                      happenedAt:
                        type: instant
                      items:
                        type: array
                        items: string
                      label:
                        type: text
                      multiple:
                        type: string
                      openFrom:
                        type: string
                      openTo:
                        type: string
                      period:
                        type: duration
                      plain:
                        type: string
                    ---
                """.trimIndent(),
                "/workspace/CommonEra.md" to """
                    ---
                    id: CommonEra
                    kind: Timeline
                    ---
                """.trimIndent(),
                "/workspace/Branch.md" to """
                    ---
                    id: Branch
                    kind: Timeline
                    ---
                """.trimIndent(),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    props:
                      count: 2
                      enabled: true
                      happenedAt:
                        timeline: CommonEra
                        value: 3
                      items:
                        - alpha
                        - value: beta
                          validTime:
                            - timeline: Branch
                              from: 1
                              to: 2
                      label:
                        default: Display name
                        ja: 表示名
                      metadata:
                        active: true
                        code: sample
                      nothing:
                      period:
                        timeline: CommonEra
                        from: 1
                        to: 2
                    ---
                    @props{
                      anytime(validTime=CommonEra) = "anytime",
                      bounded(validTime=CommonEra(from=10,to=20)) = "bounded",
                      escaped = "a\\b\tc\nd",
                      multiple(validTime=[CommonEra,Branch(from=1,to=2)]) = "multiple",
                      openFrom(validTime=CommonEra(from=10)) = "open-from",
                      openTo(validTime=Branch(to=20)) = "open-to",
                      plain = "plain"
                    }
                """.trimIndent(),
            ),
        )
        val cli = GraphMdCli(fs)

        val props = cli.run(listOf("props", "alice", "/workspace"))
        val shown = cli.run(listOf("show", "alice", "/workspace"))
        val json = cli.run(listOf("props", "alice", "/workspace", "--json"))
        fun tableBlock(value: String): String = value.trimIndent().replace('→', '\t') + "\n"

        assertEquals(0, props.exitCode, props.stderr)
        assertTrue(props.stdout.startsWith("OWNER_ID\tOWNER_VISIBILITY\tNAME\tVALUE\tVALID_TIME\tFALLBACK\n"))
        assertTrue(props.stdout.contains("anytime\tanytime\tCommonEra\tfalse\n"))
        assertTrue(props.stdout.contains("bounded\tbounded\tCommonEra: 10 – 20\tfalse\n"))
        assertTrue(props.stdout.contains("count\t2.0\t-\ttrue\n"))
        assertTrue(props.stdout.contains("enabled\ttrue\t-\ttrue\n"))
        assertTrue(props.stdout.contains("escaped\ta\\\\b\\tc\\nd\t-\ttrue\n"))
        assertTrue(props.stdout.contains("alice\tfull\tlabel\ttext {\t-\ttrue\n"))
        assertTrue(props.stdout.contains("\t\t\t    value: Display name\n"))
        assertTrue(props.stdout.contains("\t\t\t    value: 表示名\n"))
        assertEquals(0, shown.exitCode, shown.stderr)
        assertTrue(
            shown.stdout.contains(
                tableBlock(
                    """
                    happenedAt→instant {→-→true
                    →  timeline: CommonEra
                    →  value: null
                    →  coordinate: 3
                    →}
                    """,
                ),
            ),
            shown.stdout,
        )
        assertTrue(
            shown.stdout.contains(
                tableBlock(
                    """
                    items→array [→-→true
                    →  [0]:
                    →    value: alpha
                    →    validTime: -
                    →    fallback: true
                    →  [1]:
                    →    value: beta
                    →    validTime: Branch: 1 – 2
                    →    fallback: false
                    →]
                    """,
                ),
            ),
        )
        assertTrue(
            shown.stdout.contains(
                tableBlock(
                    """
                    label→text {→-→true
                    →  default:
                    →    value: Display name
                    →    validTime: -
                    →    fallback: true
                    →  ja:
                    →    value: 表示名
                    →    validTime: -
                    →    fallback: true
                    →}
                    """,
                ),
            ),
        )
        assertTrue(
            shown.stdout.contains(
                tableBlock(
                    """
                    metadata→object {→-→true
                    →  active:
                    →    value: true
                    →    validTime: -
                    →    fallback: true
                    →  code:
                    →    value: sample
                    →    validTime: -
                    →    fallback: true
                    →}
                    """,
                ),
            ),
        )
        assertTrue(props.stdout.contains("multiple\tmultiple\tCommonEra, Branch: 1 – 2\tfalse\n"))
        assertTrue(props.stdout.contains("nothing\tnull\t-\ttrue\n"))
        assertTrue(props.stdout.contains("openFrom\topen-from\tCommonEra: 10 –\tfalse\n"))
        assertTrue(props.stdout.contains("openTo\topen-to\tBranch: – 20\tfalse\n"))
        assertTrue(
            shown.stdout.contains(
                tableBlock(
                    """
                    period→duration {→-→true
                    →  timeline: CommonEra
                    →  from:
                    →    timePoint {
                    →      timeline: CommonEra
                    →      value: null
                    →      coordinate: 1
                    →    }
                    →  to:
                    →    timePoint {
                    →      timeline: CommonEra
                    →      value: null
                    →      coordinate: 2
                    →    }
                    →}
                    """,
                ),
            ),
        )
        assertTrue(props.stdout.contains("plain\tplain\t-\ttrue\n"))
        assertFalse(props.stdout.contains("[{\"timeline\""))

        assertTrue(shown.stdout.contains("bounded\tbounded\tCommonEra: 10 – 20\tfalse\n"))
        assertFalse(shown.stdout.contains("[{\"timeline\""))

        assertEquals(0, json.exitCode, json.stderr)
        assertTrue(json.stdout.contains("\"validTime\":[{\"timeline\":\"CommonEra\""))
        assertTrue(json.stdout.contains("\"fallback\":true"))
        assertTrue(json.stdout.contains("\"value\":\"alpha\",\"validTime\":[],\"fallback\":true"))
        assertTrue(json.stdout.contains("\"value\":\"beta\",\"validTime\":["))
        assertTrue(json.stdout.contains("\"value\":\"Display name\",\"validTime\":[],\"fallback\":true"))
    }

    @Test
    fun `fallback property values only appear outside explicit assertion time`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/TimelineA.md" to timeline("TimelineA"),
                "/workspace/TimelineB.md" to timeline("TimelineB"),
                "/workspace/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      age:
                        type: number
                      score:
                        type: number
                    ---
                """.trimIndent(),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: TimelineA
                      - timeline: TimelineB
                    ---
                    @props{
                      age(validTime=TimelineA)=16,
                      age=17,
                      score(validTime=TimelineA(from=10,to=20))=1,
                      score=2
                    }
                """.trimIndent(),
            ),
        )
        val cli = GraphMdCli(fs)

        val all = cli.run(listOf("show", "alice", "/workspace"))
        val allJson = cli.run(listOf("props", "alice", "/workspace", "--json"))
        val timelineA = cli.run(listOf("show", "alice", "/workspace", "--valid-time", "TimelineA"))
        val timelineAJson = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA", "--json"),
        )
        val timelineAProps = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA"),
        )
        val timelineAShowJson = cli.run(
            listOf("show", "alice", "/workspace", "--valid-time", "TimelineA", "--json"),
        )
        val timelineB = cli.run(listOf("show", "alice", "/workspace", "--valid-time", "TimelineB"))
        val timelineBJson = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineB", "--json"),
        )

        assertEquals(0, all.exitCode, all.stderr)
        assertTrue(all.stdout.contains("NAME\tVALUE\tVALID_TIME\tFALLBACK\n"))
        assertTrue(all.stdout.contains("age\t16.0\tTimelineA\tfalse\n"))
        assertTrue(all.stdout.contains("age\t17.0\tTimelineA, TimelineB\ttrue\n"))
        assertTrue(allJson.stdout.contains("\"value\":16.0,\"validTime\":"))
        assertTrue(allJson.stdout.contains("\"fallback\":false"))
        assertTrue(allJson.stdout.contains("\"value\":17.0,\"validTime\":"))
        assertTrue(allJson.stdout.contains("\"fallback\":true"))

        assertEquals(0, timelineA.exitCode, timelineA.stderr)
        assertTrue(timelineA.stdout.contains("age\t16.0\tTimelineA\tfalse\n"))
        assertFalse(timelineA.stdout.contains("age\t17.0"))
        assertTrue(timelineAJson.stdout.contains("\"value\":16.0"))
        assertFalse(timelineAJson.stdout.contains("\"value\":17.0"))
        assertTrue(timelineAProps.stdout.contains("age\t16.0\tTimelineA\tfalse\n"))
        assertFalse(timelineAProps.stdout.contains("age\t17.0"))
        assertTrue(timelineAShowJson.stdout.contains("\"value\":16.0"))
        assertFalse(timelineAShowJson.stdout.contains("\"value\":17.0"))

        assertEquals(0, timelineB.exitCode, timelineB.stderr)
        assertFalse(timelineB.stdout.contains("age\t16.0"))
        assertTrue(timelineB.stdout.contains("age\t17.0\tTimelineA, TimelineB\ttrue\n"))
        assertFalse(timelineBJson.stdout.contains("\"value\":16.0"))
        assertTrue(timelineBJson.stdout.contains("\"value\":17.0"))

        val atStart = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA(from=10,to=10)", "--json"),
        )
        val atEnd = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA(from=20,to=20)", "--json"),
        )
        val outside = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA(from=21,to=21)", "--json"),
        )
        val spanning = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA(from=5,to=25)", "--json"),
        )

        assertTrue(atStart.stdout.contains("\"value\":1.0"))
        assertFalse(atStart.stdout.contains("\"value\":2.0"))
        assertTrue(atEnd.stdout.contains("\"value\":1.0"))
        assertFalse(atEnd.stdout.contains("\"value\":2.0"))
        assertFalse(outside.stdout.contains("\"value\":1.0"))
        assertTrue(outside.stdout.contains("\"value\":2.0"))
        assertTrue(spanning.stdout.contains("\"value\":1.0"))
        assertTrue(spanning.stdout.contains("\"value\":2.0"))
    }

    @Test
    fun `links filters direction and derived relation type`() {
        val cli = GraphMdCli(linkFixture())

        val exact = cli.run(
            listOf("links", "bob", "/workspace", "--direction", "incoming", "--type", "related", "--json"),
        )
        val derived = cli.run(
            listOf(
                "links",
                "bob",
                "/workspace",
                "--direction",
                "incoming",
                "--type",
                "related",
                "--include-derived",
                "--json",
            ),
        )

        assertEquals("[]\n", exact.stdout)
        assertTrue(derived.stdout.contains("\"type\":\"friend\""))
        assertTrue(derived.stdout.contains("\"from\":\"alice\""))
    }

    @Test
    fun `human readable links render direct inherited and absent valid times`() {
        val temporalCli = GraphMdCli(validTimeFixture())

        val shown = temporalCli.run(listOf("show", "alice", "/workspace"))
        val links = temporalCli.run(listOf("links", "alice", "/workspace"))
        val timeless = GraphMdCli(linkFixture()).run(listOf("links", "alice", "/workspace"))
        val header = "TYPE\tFROM\tFROM_VISIBILITY\tTO\tTO_VISIBILITY\tLABEL\tVALID_TIME\tSOURCE\n"
        val direct = "related\talice\tfull\terin\tfull\tErin\tCommonEra: 12 – 18\t/workspace/alice.md\n"
        val inherited = "related\talice\tfull\tbob\tfull\tBob\tCommonEra: 10 – 20\t/workspace/alice.md\n"
        val absent = "friend\talice\tfull\tbob\tfull\tBob\t-\t/workspace/alice.md\n"

        assertEquals(0, shown.exitCode, shown.stderr)
        assertTrue(shown.stdout.contains(header))
        assertTrue(shown.stdout.contains(direct))
        assertTrue(shown.stdout.contains(inherited))

        assertEquals(0, links.exitCode, links.stderr)
        assertTrue(links.stdout.contains(header))
        assertTrue(links.stdout.contains(direct))
        assertTrue(links.stdout.contains(inherited))

        assertEquals(0, timeless.exitCode, timeless.stderr)
        assertTrue(timeless.stdout.contains(absent))
    }

    @Test
    fun `stats counts filtered graph items`() {
        val result = GraphMdCli(linkFixture()).run(
            listOf("stats", "/workspace", "--kind", "node", "--kind", "link", "--json"),
        )

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"node\":2"))
        assertTrue(result.stdout.contains("\"link\":1"))
        assertTrue(result.stdout.contains("\"nodeType\":0"))
    }

    @Test
    fun `strict lint promotes unknown property warnings to errors`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    props:
                      unexpected: value
                    ---
                """.trimIndent(),
            ),
        )

        val regular = GraphMdCli(fs).run(listOf("lint", "/workspace", "--json"))
        val strict = GraphMdCli(fs).run(listOf("lint", "/workspace", "--strict", "--json"))
        val query = GraphMdCli(fs).run(listOf("list", "/workspace", "--json"))

        assertEquals(0, regular.exitCode)
        assertEquals(1, strict.exitCode)
        assertTrue(strict.stdout.contains("\"severity\":\"error\""))
        assertEquals("", query.stderr)
    }

    @Test
    fun `valid time filters documents properties and links by overlapping range`() {
        val cli = GraphMdCli(validTimeFixture())

        val listed = cli.run(
            listOf(
                "list",
                "/workspace",
                "--valid-time",
                "CommonEra(from=14,to=16)",
                "--json",
            ),
        )
        val props = cli.run(
            listOf(
                "props",
                "alice",
                "/workspace",
                "--valid-time=CommonEra(from=14,to=16)",
                "--json",
            ),
        )
        val links = cli.run(
            listOf("links", "alice", "/workspace", "--valid-time", "CommonEra(from=14,to=16)", "--json"),
        )

        assertEquals(0, listed.exitCode, listed.stderr)
        assertTrue(listed.stdout.contains("\"id\":\"alice\""))
        assertTrue(listed.stdout.contains("\"id\":\"erin\""))
        assertFalse(listed.stdout.contains("\"id\":\"carol\""))
        assertTrue(listed.stdout.contains("\"id\":\"dave\""))
        assertTrue(listed.stdout.contains("\"id\":\"bob\",\"visibility\":\"full\""))
        assertTrue(listed.stdout.contains("\"id\":\"frank\",\"visibility\":\"assertion-only\""))
        assertTrue(props.stdout.contains("\"value\":\"old\""))
        assertTrue(props.stdout.contains("\"value\":\"new\""))
        assertTrue(links.stdout.contains("\"to\":\"erin\""))
        assertTrue(links.stdout.contains("\"to\":\"bob\""))
        assertFalse(links.stdout.contains("\"toVisibility\":\"assertion-only\""))
    }

    @Test
    fun `valid time keeps timeless documents nested properties and links visible`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Timeline.md" to timeline("Timeline"),
                "/workspace/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      tags:
                        type: array
                        items:
                          type: string
                    ---
                """.trimIndent(),
                "/workspace/related.md" to """
                    ---
                    id: related
                    kind: RelType
                    ---
                """.trimIndent(),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    props:
                      tags: [timeless]
                    ---
                    @link[Bob](bob related)
                """.trimIndent(),
                "/workspace/bob.md" to node("bob", "Person"),
            ),
        )
        val cli = GraphMdCli(fs)
        val validTime = listOf("--valid-time", "Timeline(from=10,to=20)", "--json")

        val listed = cli.run(listOf("list", "/workspace") + validTime)
        val props = cli.run(listOf("props", "alice", "/workspace") + validTime)
        val links = cli.run(listOf("links", "alice", "/workspace") + validTime)

        assertEquals(0, listed.exitCode, listed.stderr)
        assertTrue(listed.stdout.contains("\"id\":\"alice\""))
        assertFalse(listed.stdout.contains("\"id\":\"alice\",\"visibility\":\"assertion-only\""))
        assertEquals(0, props.exitCode, props.stderr)
        assertTrue(props.stdout.contains("\"value\":\"timeless\""))
        assertEquals(0, links.exitCode, links.stderr)
        assertTrue(links.stdout.contains("\"to\":\"bob\""))
    }

    @Test
    fun `valid time uses inclusive bounds and timeline inheritance but not mappings`() {
        val cli = GraphMdCli(validTimeFixture())

        val boundary = cli.run(
            listOf("props", "alice", "/workspace", "--valid-time", "CommonEra(from=14,to=14)", "--json"),
        )
        val mappedOnly = cli.run(
            listOf("show", "carol", "/workspace", "--valid-time", "CommonEra(from=10,to=20)", "--json"),
        )
        val inherited = cli.run(
            listOf("show", "alice", "/workspace", "--valid-time", "Branch(from=10,to=20)", "--json"),
        )

        assertTrue(boundary.stdout.contains("\"value\":\"old\""))
        assertFalse(boundary.stdout.contains("\"value\":\"new\""))
        assertEquals(1, mappedOnly.exitCode)
        assertTrue(mappedOnly.stderr.contains("No entity found"))
        assertEquals(0, inherited.exitCode, inherited.stderr)
        assertTrue(inherited.stdout.contains("\"id\":\"alice\""))
    }

    @Test
    fun `matching property is assertion-only while a timeless linked target remains full`() {
        val cli = GraphMdCli(validTimeFixture())

        val shown = cli.run(
            listOf("show", "frank", "/workspace", "--valid-time", "CommonEra(from=14,to=16)", "--json"),
        )
        val props = cli.run(
            listOf("props", "frank", "/workspace", "--valid-time", "CommonEra(from=14,to=16)", "--json"),
        )
        val linkedTarget = cli.run(
            listOf("show", "bob", "/workspace", "--valid-time", "CommonEra(from=14,to=16)", "--json"),
        )

        assertEquals(0, shown.exitCode)
        assertTrue(shown.stdout.contains("\"visibility\":\"assertion-only\""))
        assertTrue(shown.stdout.contains("\"value\":\"searchable\""))
        assertFalse(shown.stdout.contains("\"type\":\"Person\""))
        assertFalse(shown.stdout.contains("\"source\""))
        assertTrue(props.stdout.contains("\"value\":\"searchable\""))
        assertTrue(props.stdout.contains("\"ownerId\":\"frank\""))
        assertTrue(props.stdout.contains("\"ownerVisibility\":\"assertion-only\""))
        assertEquals("", props.stderr)
        assertTrue(linkedTarget.stdout.contains("\"visibility\":\"full\""))
        assertTrue(linkedTarget.stdout.contains("\"incomingLinks\""))
        assertTrue(linkedTarget.stdout.contains("\"type\":\"Person\""))
    }

    @Test
    fun `nested assertion remains visible when it overrides its property and document timeline`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/TimelineA.md" to """
                    ---
                    id: TimelineA
                    kind: Timeline
                    ---
                """.trimIndent(),
                "/workspace/TimelineB.md" to """
                    ---
                    id: TimelineB
                    kind: Timeline
                    ---
                """.trimIndent(),
                "/workspace/SampleType.md" to """
                    ---
                    id: Sample
                    kind: NodeType
                    props:
                      values:
                        type: array
                        items: number
                    ---
                """.trimIndent(),
                "/workspace/sample.md" to """
                    ---
                    id: sample
                    kind: Node
                    type: Sample
                    validTime:
                      - timeline: TimelineA
                    props:
                      values:
                        - 1
                        - value: 2
                          validTime:
                            - timeline: TimelineB
                    ---
                """.trimIndent(),
            ),
        )
        val cli = GraphMdCli(fs)

        val timelineA = cli.run(
            listOf("props", "sample", "/workspace", "--valid-time", "TimelineA", "--json"),
        )
        val timelineB = cli.run(
            listOf("props", "sample", "/workspace", "--valid-time", "TimelineB", "--json"),
        )
        val valueOne = Regex("\"value\":1(?:\\.0)?,")
        val valueTwo = Regex("\"value\":2(?:\\.0)?,")

        assertEquals(0, timelineA.exitCode, timelineA.stderr)
        assertTrue(valueOne.containsMatchIn(timelineA.stdout))
        assertFalse(valueTwo.containsMatchIn(timelineA.stdout))
        assertEquals(0, timelineB.exitCode, timelineB.stderr)
        assertTrue(timelineB.stdout.contains("\"ownerVisibility\":\"assertion-only\""))
        assertTrue(valueTwo.containsMatchIn(timelineB.stdout))
        assertFalse(valueOne.containsMatchIn(timelineB.stdout))
    }

    @Test
    fun `valid time rejects invalid ranges and unknown timelines`() {
        val cli = GraphMdCli(validTimeFixture())

        val invalid = cli.run(
            listOf("list", "/workspace", "--valid-time", "CommonEra(from=20,to=10)", "--json"),
        )
        val scientific = cli.run(
            listOf("list", "/workspace", "--valid-time", "CommonEra(from=1e1,to=2E1)", "--json"),
        )
        val reversedScientific = cli.run(
            listOf("list", "/workspace", "--valid-time", "CommonEra(from=2e1,to=1E1)", "--json"),
        )
        val unknown = cli.run(
            listOf("list", "/workspace", "--valid-time", "Missing(from=1)", "--json"),
        )

        assertEquals(2, invalid.exitCode)
        assertTrue(invalid.stderr.contains("from must not exceed to"))
        assertEquals(0, scientific.exitCode, scientific.stderr)
        assertTrue(scientific.stdout.contains("\"id\":\"alice\""))
        assertEquals(2, reversedScientific.exitCode)
        assertTrue(reversedScientific.stderr.contains("from must not exceed to"))
        assertEquals(1, unknown.exitCode)
        assertTrue(unknown.stderr.contains("Unknown Timeline"))
    }

    @Test
    fun `valid time parses calendar boundaries and rejects reversed dates`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/CommonEra.md" to """
                    ---
                    id: CommonEra
                    kind: Timeline
                    coordinate: gregorian
                    ---
                """.trimIndent(),
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: CommonEra
                        from: 2026-01-01
                        to: 2026-12-31
                    ---
                """.trimIndent(),
            ),
        )
        val cli = GraphMdCli(fs)

        val matching = cli.run(
            listOf("list", "/workspace", "--valid-time", "CommonEra(from=2026-06-01,to=2026-06-30)", "--json"),
        )
        val reversed = cli.run(
            listOf("list", "/workspace", "--valid-time", "CommonEra(from=2027-01-01,to=2026-01-01)", "--json"),
        )

        assertEquals(0, matching.exitCode, matching.stderr)
        assertTrue(matching.stdout.contains("\"id\":\"alice\""))
        assertEquals(2, reversed.exitCode)
        assertTrue(reversed.stderr.contains("from must not exceed to"))
    }

    @Test
    fun `source valid time with reversed bounds does not crash temporal property filtering`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/TimelineA.md" to timeline("TimelineA"),
                "/workspace/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    props:
                      age:
                        type: number
                    ---
                """.trimIndent(),
                "/workspace/alice.md" to """
                    ---
                    id: alice
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: TimelineA
                    ---
                    @props{age(validTime=TimelineA(from=20,to=10))=16,age=17}
                """.trimIndent(),
            ),
        )

        val result = GraphMdCli(fs).run(
            listOf("props", "alice", "/workspace", "--valid-time", "TimelineA", "--json"),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertFalse(result.stdout.contains("\"value\":16.0"))
        assertTrue(result.stdout.contains("\"value\":17.0"))
        assertFalse(result.stderr.contains("An interval must not be empty"))
    }

    @Test
    fun `lint with valid time includes timeless documents`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Timeline.md" to """
                    ---
                    id: CommonEra
                    kind: Timeline
                    ---
                """.trimIndent(),
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/visible.md" to """
                    ---
                    id: visible
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: CommonEra
                    props:
                      unexpected: value
                    ---
                """.trimIndent(),
                "/workspace/hidden.md" to node("hidden", "MissingType"),
            ),
        )

        val result = GraphMdCli(fs).run(
            listOf("lint", "/workspace", "--valid-time", "CommonEra(from=1,to=2)", "--json"),
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("Unknown property unexpected"))
        assertTrue(result.stdout.contains("Unknown NodeType"))
    }

    @Test
    fun `search executes inline GMQL with typed parameters and JSON rows`() {
        val cli = GraphMdCli(searchFixture())
        val query = """
            MATCH (n:Person)
            WHERE n.age >= ${'$'}minimum
            RETURN ID(n) AS id, n.age AS age
            ORDER BY id
        """.trimIndent()

        val result = cli.run(
            listOf("search", query, "/workspace", "--param", "minimum=15", "--json"),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"id\":\"alice\""))
        assertTrue(result.stdout.contains("\"age\":["))
        assertFalse(result.stdout.contains("\"id\":\"bob\""))
    }

    @Test
    fun `search reads a query file and renders tab separated output`() {
        val result = GraphMdCli(searchFixture()).run(
            listOf("search", "--query-file", "/queries/find.gmql", "/workspace"),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.startsWith("id\n"))
        assertTrue(result.stdout.contains("alice\n"))
        assertTrue(result.stdout.contains("bob\n"))
    }

    @Test
    fun `search returns GMQL diagnostics on stderr`() {
        val result = GraphMdCli(searchFixture()).run(
            listOf("search", "MATCH (n:NoSuch) RETURN n", "/workspace", "--json"),
        )

        assertEquals(1, result.exitCode)
        assertEquals("[]\n", result.stdout)
        assertTrue(result.stderr.contains("\"code\":\"GMQL2001\""), result.stderr)
    }

    @Test
    fun `search keeps valid nodes when another node has an unknown validTime timeline`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Person.md" to nodeType("Person"),
                "/workspace/broken.md" to """
                    ---
                    id: broken
                    kind: Node
                    type: Person
                    validTime:
                      - timeline: MissingTimeline
                    ---
                """.trimIndent(),
                "/workspace/good.md" to node("good", "Person"),
            ),
        )

        val result = GraphMdCli(fs).run(
            listOf("search", "MATCH (n:Person) RETURN ID(n) AS id ORDER BY id", "/workspace", "--json"),
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stdout.contains("\"good\""))
        assertFalse(result.stdout.contains("\"broken\""))
        assertTrue(result.stderr.contains("Unknown Timeline: MissingTimeline"))
        assertFalse(result.stderr.contains("Key is missing in the map"))
    }

    private fun fixtureCli(): GraphMdCli = GraphMdCli(
        FakeFileSystem(
            files = mapOf(
                "/workspace/Entity.md" to nodeType("Entity"),
                "/workspace/Person.md" to """
                    ---
                    id: Person
                    kind: NodeType
                    extends: [Entity]
                    ---
                """.trimIndent(),
                "/workspace/alice.md" to node("alice", "Person"),
                "/workspace/portrait.md" to """
                    ---
                    id: portrait
                    kind: Media
                    type: Person
                    url: https://example.com/portrait.png
                    ---
                """.trimIndent(),
            ),
        ),
    )

    private fun linkFixture(): FakeFileSystem = FakeFileSystem(
        files = mapOf(
            "/workspace/Person.md" to nodeType("Person"),
            "/workspace/related.md" to """
                ---
                id: related
                kind: RelType
                ---
            """.trimIndent(),
            "/workspace/friend.md" to """
                ---
                id: friend
                kind: RelType
                extends: [related]
                from: [Person]
                to: [Person]
                ---
            """.trimIndent(),
            "/workspace/alice.md" to """
                ---
                id: alice
                kind: Node
                type: Person
                ---
                @link{}[Bob](bob friend)
            """.trimIndent(),
            "/workspace/bob.md" to node("bob", "Person"),
        ),
    )

    private fun searchFixture(): FakeFileSystem = FakeFileSystem(
        files = mapOf(
            "/workspace/Person.md" to """
                ---
                id: Person
                kind: NodeType
                props:
                  age:
                    type: number
                ---
            """.trimIndent(),
            "/workspace/alice.md" to """
                ---
                id: alice
                kind: Node
                type: Person
                props:
                  age: 20
                ---
            """.trimIndent(),
            "/workspace/bob.md" to """
                ---
                id: bob
                kind: Node
                type: Person
                props:
                  age: 10
                ---
            """.trimIndent(),
            "/queries/find.gmql" to "MATCH (n:Person) RETURN ID(n) AS id ORDER BY id",
        ),
    )

    private fun validTimeFixture(): FakeFileSystem = FakeFileSystem(
        files = mapOf(
            "/workspace/Person.md" to """
                ---
                id: Person
                kind: NodeType
                props:
                  name:
                    type: string
                ---
            """.trimIndent(),
            "/workspace/CommonEra.md" to """
                ---
                id: CommonEra
                kind: Timeline
                mapsTo:
                  - timeline: ProjectEra
                    kind: alignment
                    precision:
                      kind: approximate
                      error: 1
                    offset: 100
                ---
            """.trimIndent(),
            "/workspace/ProjectEra.md" to """
                ---
                id: ProjectEra
                kind: Timeline
                ---
            """.trimIndent(),
            "/workspace/Branch.md" to """
                ---
                id: Branch
                kind: Timeline
                sameAxisAs: CommonEra
                ---
            """.trimIndent(),
            "/workspace/related.md" to """
                ---
                id: related
                kind: RelType
                ---
            """.trimIndent(),
            "/workspace/alice.md" to """
                ---
                id: alice
                kind: Node
                type: Person
                validTime:
                  - timeline: CommonEra
                    from: 10
                    to: 20
                ---
                @props{
                  name(validTime=CommonEra(from=10,to=14)) = "old",
                  name(validTime=CommonEra(from=15,to=20)) = "new"
                }
                @link(validTime=CommonEra(from=12,to=18))[Erin](erin related)
                @link[Bob](bob related)
            """.trimIndent(),
            "/workspace/bob.md" to node("bob", "Person"),
            "/workspace/carol.md" to """
                ---
                id: carol
                kind: Node
                type: Person
                validTime:
                  - timeline: ProjectEra
                    from: 110
                    to: 120
                ---
            """.trimIndent(),
            "/workspace/dave.md" to """
                ---
                id: dave
                kind: Node
                type: Person
                validTime:
                  - timeline: Branch
                    from: 10
                    to: 20
                ---
            """.trimIndent(),
            "/workspace/erin.md" to """
                ---
                id: erin
                kind: Node
                type: Person
                validTime:
                  - timeline: CommonEra
                    from: 10
                    to: 20
                ---
            """.trimIndent(),
            "/workspace/frank.md" to """
                ---
                id: frank
                kind: Node
                type: Person
                validTime:
                  - timeline: ProjectEra
                ---
                @props{name(validTime=CommonEra(from=10,to=20)) = "searchable"}
            """.trimIndent(),
        ),
    )

    private fun nodeType(id: String): String = """
        ---
        id: $id
        kind: NodeType
        ---
    """.trimIndent()

    private fun timeline(id: String): String = """
        ---
        id: $id
        kind: Timeline
        ---
    """.trimIndent()

    private fun node(id: String, type: String): String = """
        ---
        id: $id
        kind: Node
        type: $type
        ---
    """.trimIndent()
}

private class FakeFileSystem(
    files: Map<String, String>,
    private val aliases: Map<String, String> = emptyMap(),
    private val failWriteAt: Int? = null,
) : CliFileSystem {
    private val mutableFiles = files.toMutableMap()
    private var writeCount = 0
    private val directories: MutableSet<String> = buildSet {
        add("/")
        files.keys.forEach { file ->
            var current = file.substringBeforeLast('/', missingDelimiterValue = "/")
            while (current.isNotEmpty()) {
                add(current)
                if (current == "/") break
                current = current.substringBeforeLast('/', missingDelimiterValue = "/")
            }
        }
    }.toMutableSet()

    override fun kind(path: String): FileKind? = when (canonical(path)) {
        in mutableFiles -> FileKind.File
        in directories -> FileKind.Directory
        else -> null
    }

    override fun canonical(path: String): String {
        val absolute = when (path) {
            "." -> "/workspace"
            else -> path.removeSuffix("/").ifEmpty { "/" }
        }
        return aliases[absolute] ?: absolute
    }

    override fun children(path: String): List<String> {
        val prefix = canonical(path).let { if (it == "/") "/" else "$it/" }
        return (mutableFiles.keys + directories)
            .filter { it.startsWith(prefix) && it != path }
            .filter { it.removePrefix(prefix).let { rest -> '/' !in rest } }
            .distinct()
    }

    override fun readText(path: String): String = mutableFiles.getValue(canonical(path))

    override fun child(path: String, name: String): String =
        canonical(path).let { if (it == "/") "/$name" else "$it/$name" }

    override fun createDirectories(path: String) {
        val canonical = canonical(path)
        var current = canonical
        while (current.isNotEmpty()) {
            directories += current
            if (current == "/") break
            current = current.substringBeforeLast('/', missingDelimiterValue = "/")
        }
    }

    override fun writeText(path: String, text: String) {
        writeCount++
        if (writeCount == failWriteAt) error("simulated write failure")
        mutableFiles[canonical(path)] = text
    }

    override fun delete(path: String, mustExist: Boolean) {
        val canonical = canonical(path)
        val removed = mutableFiles.remove(canonical) != null || directories.remove(canonical)
        if (mustExist && !removed) error("Path does not exist: $path")
    }

    fun contentsUnder(path: String): Map<String, String> {
        val prefix = canonical(path).let { if (it == "/") "/" else "$it/" }
        return mutableFiles.entries
            .filter { it.key.startsWith(prefix) }
            .sortedBy { it.key }
            .associateTo(linkedMapOf()) { it.toPair() }
    }
}
