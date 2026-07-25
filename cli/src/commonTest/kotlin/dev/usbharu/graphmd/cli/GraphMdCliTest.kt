package dev.usbharu.graphmd.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphMdCliTest {
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
        assertFalse(listed.stdout.contains("\"id\":\"dave\""))
        assertTrue(listed.stdout.contains("\"id\":\"bob\",\"visibility\":\"assertion-only\""))
        assertTrue(listed.stdout.contains("\"id\":\"frank\",\"visibility\":\"assertion-only\""))
        assertTrue(props.stdout.contains("\"value\":\"old\""))
        assertTrue(props.stdout.contains("\"value\":\"new\""))
        assertTrue(links.stdout.contains("\"to\":\"erin\""))
        assertTrue(links.stdout.contains("\"to\":\"bob\""))
        assertTrue(links.stdout.contains("\"toVisibility\":\"assertion-only\""))
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
    fun `matching property exposes only assertions and id when document is outside valid time`() {
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
        assertTrue(linkedTarget.stdout.contains("\"visibility\":\"assertion-only\""))
        assertTrue(linkedTarget.stdout.contains("\"incomingLinks\""))
        assertFalse(linkedTarget.stdout.contains("\"type\":\"Person\""))
    }

    @Test
    fun `nested assertion remains visible when it overrides its property and document timeline`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/TimelineA.md" to """
                    ---
                    id: TimelineA
                    kind: Timeline
                    timecode:
                      type: number
                    ---
                """.trimIndent(),
                "/workspace/TimelineB.md" to """
                    ---
                    id: TimelineB
                    kind: Timeline
                    timecode:
                      type: number
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
        val unknown = cli.run(
            listOf("list", "/workspace", "--valid-time", "Missing(from=1)", "--json"),
        )

        assertEquals(2, invalid.exitCode)
        assertTrue(invalid.stderr.contains("from must not exceed to"))
        assertEquals(1, unknown.exitCode)
        assertTrue(unknown.stderr.contains("Unknown Timeline"))
    }

    @Test
    fun `lint with valid time only reports visible documents`() {
        val fs = FakeFileSystem(
            files = mapOf(
                "/workspace/Timeline.md" to """
                    ---
                    id: CommonEra
                    kind: Timeline
                    timecode:
                      type: number
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

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("Unknown property unexpected"))
        assertFalse(result.stdout.contains("Unknown NodeType"))
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
                timecode:
                  type: number
                mappings:
                  - kind: offset
                    to: ProjectEra
                    offset: 100
                ---
            """.trimIndent(),
            "/workspace/ProjectEra.md" to """
                ---
                id: ProjectEra
                kind: Timeline
                timecode:
                  type: number
                ---
            """.trimIndent(),
            "/workspace/Branch.md" to """
                ---
                id: Branch
                kind: Timeline
                extends: [CommonEra]
                timecode:
                  type: number
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
                    from:
                      timecode: 10
                    to:
                      timecode: 20
                ---
                @props{
                  name(validTime=CommonEra(from=10,to=14)) = "old",
                  name(validTime=CommonEra(from=15,to=20)) = "new"
                }
                @link(validTime=CommonEra(from=12,to=18))[Erin](erin related)
                @link(validTime=CommonEra(from=12,to=18))[Bob](bob related)
            """.trimIndent(),
            "/workspace/bob.md" to node("bob", "Person"),
            "/workspace/carol.md" to """
                ---
                id: carol
                kind: Node
                type: Person
                validTime:
                  - timeline: ProjectEra
                    from:
                      timecode: 110
                    to:
                      timecode: 120
                ---
            """.trimIndent(),
            "/workspace/dave.md" to """
                ---
                id: dave
                kind: Node
                type: Person
                validTime:
                  - timeline: Branch
                    from:
                      timecode: 10
                    to:
                      timecode: 20
                ---
            """.trimIndent(),
            "/workspace/erin.md" to """
                ---
                id: erin
                kind: Node
                type: Person
                validTime:
                  - timeline: CommonEra
                    from:
                      timecode: 10
                    to:
                      timecode: 20
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

    private fun node(id: String, type: String): String = """
        ---
        id: $id
        kind: Node
        type: $type
        ---
    """.trimIndent()
}

private class FakeFileSystem(
    private val files: Map<String, String>,
    private val aliases: Map<String, String> = emptyMap(),
) : CliFileSystem {
    private val directories: Set<String> = buildSet {
        add("/")
        files.keys.forEach { file ->
            var current = file.substringBeforeLast('/', missingDelimiterValue = "/")
            while (current.isNotEmpty()) {
                add(current)
                if (current == "/") break
                current = current.substringBeforeLast('/', missingDelimiterValue = "/")
            }
        }
    }

    override fun kind(path: String): FileKind? = when (canonical(path)) {
        in files -> FileKind.File
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
        return (files.keys + directories)
            .filter { it.startsWith(prefix) && it != path }
            .filter { it.removePrefix(prefix).let { rest -> '/' !in rest } }
            .distinct()
    }

    override fun readText(path: String): String = files.getValue(canonical(path))
}
