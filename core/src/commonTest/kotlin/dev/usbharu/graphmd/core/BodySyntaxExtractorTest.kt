package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodySyntaxExtractorTest {
    private val extractor = BodySyntaxExtractor()

    @Test
    fun `extracts props and relations from non code regions`() {
        val body = """
            # Alice

            @props{name = "Alice"}
            Alice is friends with @link{weight = 0.82}[Bob](bob friendOf)

            ```md
            @props{name = "Ignored"}
            @link{}[Ignored](ignored friendOf)
            ```

            `@link{}[Inline](inline friendOf)`
            \@link{}[Escaped](escaped friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.propsBlocks.size)
        assertEquals(1, extracted.relations.size)
        assertEquals("Alice", (extracted.propsBlocks.single().props.getValue("name") as RawString).value)
        assertEquals("bob", extracted.relations.single().target)
    }

    @Test
    fun `supports escaped label characters`() {
        val body = """@link{}[Bob \] Jr.](bob-jr friendOf)"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals("Bob ] Jr.", extracted.relations.single().label)
    }

    @Test
    fun `supports quoted rel type`() {
        val body = """@link{weight = 0.82}[Bob](bob "friendOf")"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals("friendOf", extracted.relations.single().relType)
    }

    @Test
    fun `supports quoted noncanonical rel type without whitespace`() {
        val extracted = extractor.extract(
            """@link[Bob](bob "friend/of")""",
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals("friend/of", extracted.relations.single().relType)
    }

    @Test
    fun `rejects whitespace in quoted and unquoted rel types`() {
        listOf(
            """@link[Bob](bob friend Of)""",
            "@link[Bob](bob friend\tOf)",
            "@link[Bob](bob friend\u00a0Of)",
            """@link[Bob](bob "friend Of")""",
            """@link[Bob](bob "friend\ Of")""",
            "@link[Bob](bob \"friend\tOf\")",
            "@link[Bob](bob \"friend\u00a0Of\")",
        ).forEach { body ->
            val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

            assertTrue(extracted.relations.isEmpty(), body)
            val diagnostic = extracted.diagnostics.single {
                it.message == "Relation target and type must be separated by horizontal spaces"
            }
            assertEquals(SourceRange(0, body.lastIndexOf(')')), diagnostic.source?.range)
        }
    }

    @Test
    fun `keeps target and malformed relation recovery when rel type whitespace is rejected`() {
        val body = """
            @link[Allowed target label](bob "friend Of")
            @link[Carol](carol friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(listOf("carol"), extracted.relations.map { it.target })
        assertEquals("Carol", extracted.relations.single().label)
        assertTrue(extracted.diagnostics.any {
            it.message == "Relation target and type must be separated by horizontal spaces"
        })
    }

    @Test
    fun `reports malformed relation`() {
        val body = """@link{}[Bob](bob)"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { it.category == DiagnosticCategory.SyntaxError })
    }

    @Test
    fun `reports malformed props and invalid relation props`() {
        val body = """
            @props{name = "Alice"
            @link{weight = }[Bob](bob friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { "Unclosed @props block" in it.message })
        assertTrue(extracted.diagnostics.any { it.category == DiagnosticCategory.SyntaxError })
    }

    @Test
    fun `reports duplicate props at the second key`() {
        val body = """
            Intro
            @props{
              name = "Alice",
              name = "Bob"
            }
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        val diagnostic = extracted.diagnostics.single { it.message.startsWith("Duplicate key: name") }
        val duplicateStart = body.lastIndexOf("name")
        assertEquals(SourceRange(duplicateStart, duplicateStart + "name".length), diagnostic.source?.range)
    }

    @Test
    fun `ignores indented code blocks`() {
        val body = listOf(
            "    @props{name = \"Ignored\"}",
            "    @link{}[Ignored](ignored friendOf)",
            "\t@link{}[TabIgnored](tabIgnored friendOf)",
            "",
            "@link{}[Bob](bob friendOf)",
        ).joinToString("\n")

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
        assertEquals("bob", extracted.relations.single().target)
    }

    @Test
    fun `ignores CommonMark fenced code variants and preserves surrounding syntax`() {
        val body = """
            @link[Before](before friendOf)
              ~~~ graph-md
            @props{name = "tilde"}
            @link[Tilde](tilde friendOf)
              ~~~~~
            > ```
            > @link[Quote](quote friendOf)
            > ```
            - ````
              @link[List](list friendOf)
              ```
              @props{name = "still fenced"}
              `````
            @link[After](after friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/fences.md", "fences")

        assertEquals(listOf("before", "after"), extracted.relations.map { it.target })
        assertTrue(extracted.propsBlocks.isEmpty())
        assertEquals(
            body.indexOf("@link[After]"),
            extracted.relations.last().range.start,
            "masking must retain source offsets",
        )
    }

    @Test
    fun `honors fence indentation variable runs and unclosed fences`() {
        val body = """
              @link[One](one friendOf)
               @link[Two](two friendOf)
                @link[Three](three friendOf)

                 @link[Indented](indented friendOf)
             ````
             @link[LongFence](long-fence friendOf)
             ```
             @link[ShortClose](short-close friendOf)
             `````
             @link[Visible](visible friendOf)
             ~~~
             @link[Unclosed](unclosed friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/boundaries.md", "boundaries")

        assertEquals(listOf("one", "two", "three", "visible"), extracted.relations.map { it.target })
    }

    @Test
    fun `does not treat paragraph continuation indentation as a code block`() {
        val body = """
            paragraph
                @link[Continuation](continuation friendOf)

                @link[Code](code friendOf)
            @link[Visible](visible friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/indented.md", "indented")

        assertEquals(listOf("continuation", "visible"), extracted.relations.map { it.target })
    }

    @Test
    fun `handles tabs and CommonMark backtick span delimiters`() {
        val body = buildString {
            append("\t@link[Tab](tab friendOf)\n")
            append("``@link[Double](double friendOf) ` inner``` ``\n")
            append("`@link[AdjacentOne](adjacent-one friendOf)``")
            append("@link[AdjacentTwo](adjacent-two friendOf)`\n")
            append("\\` @link[Escaped](escaped friendOf)\n")
            append("` unmatched @link[Unmatched](unmatched friendOf)\n")
            append("@link[Visible](visible friendOf)")
        }

        val extracted = extractor.extract(body, "/tmp/spans.md", "spans")

        assertEquals(
            listOf("escaped", "unmatched", "visible"),
            extracted.relations.map { it.target },
        )
    }

    @Test
    fun `reports unclosed relation label and target`() {
        val body = """
            @link{}[Bob(bob friendOf)
            @link{}[Carol](carol friendOf
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { "Unclosed relation label" in it.message })
        assertTrue(extracted.diagnostics.any { "Unclosed relation target" in it.message })
    }

    @Test
    fun `reports missing relation paren and unclosed relation props`() {
        val body = """
            @link{}[Bob]x
            @link{weight = 1[Carol](carol friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { "Relation must be followed by (...)" in it.message })
        assertTrue(extracted.diagnostics.any { "Unclosed @link property block" in it.message })
    }

    @Test
    fun `handles unterminated fenced and inline code spans`() {
        val body = """
            ```md
            @link{}[Ignored](ignored friendOf)
            @props{name = "Ignored"}
            @link{}[Bob](bob friendOf)
            `@link{}[StillIgnored](ignored friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(0, extracted.relations.size)
        assertEquals(0, extracted.propsBlocks.size)
    }

    @Test
    fun `treats double backslash marker as unescaped`() {
        val body = """\\@link{}[Bob](bob friendOf)"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
    }

    @Test
    fun `supports relation without props and ignores props without brace`() {
        val body = """
            @link[Bob](bob friendOf)
            @link(validTime=CommonEra)[Carol](carol friendOf)
            @props name = "ignored"
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(2, extracted.relations.size)
        assertTrue(extracted.relations.all { it.props.isEmpty() })
        assertEquals("CommonEra", extracted.relations[1].validTime.single().timeline)
        assertEquals(0, extracted.propsBlocks.size)
    }

    @Test
    fun `treats nested braces and escaped strings as balanced`() {
        val body = """
            @props{meta = { text = "a\"b", nested = { value = "x" } }}
            @link{meta = { value = "{x}" }}[Bob](bob friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.propsBlocks.size)
        assertEquals(1, extracted.relations.size)
    }

    @Test
    fun `ignores unrelated at markers and dangling props keyword`() {
        val body = """
            email@example.com
            @props
            @unknown
            @link{}[Bob](bob friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
        assertTrue(extracted.diagnostics.isEmpty())
    }

    @Test
    fun `ignores directive keywords followed by identifier characters`() {
        val body = """
            @linking @links @link_foo @link1 @link.foo @link:foo @link-foo @linké @link日本語
            https://example.com/@link:section user@link.example
            @propsExtra{name = "Ignored"} @props_foo{name = "Ignored"} @props1{name = "Ignored"}
            @props.foo{name = "Ignored"} @props:foo{name = "Ignored"} @props-foo{name = "Ignored"}
            @link[Bob](bob friendOf)
            @props{name = "Alice"}
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals(1, extracted.propsBlocks.size)
        assertEquals("Alice", (extracted.propsBlocks.single().props.getValue("name") as RawString).value)
        assertEquals(1, extracted.relations.size)
        assertEquals("bob", extracted.relations.single().target)
    }

    @Test
    fun `recognizes standalone link keyword and boundaries before punctuation and whitespace`() {
        val body = """
            @link,
            @link [Bob](bob friendOf)
            @link
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(3, extracted.diagnostics.count { it.message == "@link must be followed immediately by a link" })
        assertEquals(0, extracted.relations.size)
    }

    @Test
    fun `returns empty extraction for plain text`() {
        val extracted = extractor.extract("plain text only", "/tmp/plain.md", "plain")

        assertEquals(0, extracted.relations.size)
        assertEquals(0, extracted.propsBlocks.size)
        assertEquals(0, extracted.diagnostics.size)
    }

    @Test
    fun `extracts canonical link syntax and link validTime`() {
        val extracted = extractor.extract(
            """Aliceは@link(validTime=[CommonEra,Branch(from=1,to=2)]){weight=0.2}[Bob](bob "friendOf")です""",
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        val relation = extracted.relations.single()
        assertEquals("bob", relation.target)
        assertEquals("friendOf", relation.relType)
        assertEquals(0.2, (relation.props.getValue("weight") as RawNumber).value)
        assertEquals(listOf("CommonEra", "Branch"), relation.validTime.map { it.timeline })
        assertEquals(1.0, relation.validTime[1].from?.timecode)
        assertEquals(2.0, relation.validTime[1].to?.timecode)
    }

    @Test
    fun `extracts noncanonical timeline ids from directive validTime grammar`() {
        val extracted = extractor.extract(
            """@props(validTime=Era@Branch){age=25} @link(validTime=Other/Line)[Bob](bob friendOf)""",
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        val propsEntry = (extracted.propsBlocks.single().props.getValue("age") as RawArray).values.single() as RawObject
        val propsTime = (propsEntry.values.getValue("validTime") as RawArray).values.single() as RawObject
        assertEquals("Era@Branch", (propsTime.values.getValue("timeline") as RawString).value)
        assertEquals("Other/Line", extracted.relations.single().validTime.single().timeline)
    }

    @Test
    fun `extracts props-wide and per-property validTime assertions`() {
        val extracted = extractor.extract(
            """@props(validTime=[CommonEra]){age=25,name(validTime=Branch(from=1,to=2))="Alice"}""",
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        val props = extracted.propsBlocks.single().props
        val ageEntry = ((props.getValue("age") as RawArray).values.single() as RawObject)
        val ageTime = (ageEntry.values.getValue("validTime") as RawArray).values.single() as RawObject
        assertEquals("CommonEra", (ageTime.values.getValue("timeline") as RawString).value)
        val nameEntry = ((props.getValue("name") as RawArray).values.single() as RawObject)
        val nameTime = (nameEntry.values.getValue("validTime") as RawArray).values.single() as RawObject
        assertEquals("Branch", (nameTime.values.getValue("timeline") as RawString).value)
    }

    @Test
    fun `allows spaces around validTime equals in directive arguments`() {
        val extracted = extractor.extract(
            """@props(validTime = CommonEra){age=25} @link(validTime = CommonEra)[Bob](bob friendOf)""",
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        val ageEntry = ((extracted.propsBlocks.single().props.getValue("age") as RawArray).values.single() as RawObject)
        val ageTime = (ageEntry.values.getValue("validTime") as RawArray).values.single() as RawObject
        assertEquals("CommonEra", (ageTime.values.getValue("timeline") as RawString).value)
        assertEquals("CommonEra", extracted.relations.single().validTime.single().timeline)
    }

    @Test
    fun `extracts named nested blocks and applies nearest last validTime`() {
        val extracted = extractor.extract(
            """
            ::: history history validTime=Outer(from=0 ,to=10)
            @props{age=10}
            ::::: spoiler annotation validTime=Discarded validTime = Inner(from=2,to=3)
            @link[Bob](bob friendOf)
            :::::
            :::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals(2, extracted.blocks.size)
        val outer = extracted.blocks.first { it.fenceLength == 3 }
        val inner = extracted.blocks.first { it.fenceLength == 5 }
        assertEquals(listOf("history", "history"), outer.names)
        assertEquals(listOf("spoiler", "annotation"), inner.names)
        assertEquals("Outer", outer.validTime.single().timeline)
        assertEquals("Inner", inner.validTime.single().timeline)
        assertEquals(0.0, outer.validTime.single().from?.timecode)
        assertEquals(3.0, inner.validTime.single().to?.timecode)

        val age = (extracted.propsBlocks.single().props.getValue("age") as RawArray).values.single() as RawObject
        val ageTime = (age.values.getValue("validTime") as RawArray).values.single() as RawObject
        assertEquals("Outer", (ageTime.values.getValue("timeline") as RawString).value)
        assertEquals("Inner", extracted.relations.single().validTime.single().timeline)
    }

    @Test
    fun `names-only nested block inherits parent validTime while explicit directives win`() {
        val extracted = extractor.extract(
            """
            ::: history validTime=Outer
            ::::: note
            @props{inherited=1,explicit(validTime=Property)=2}
            @link(validTime=Relation)[Bob](bob friendOf)
            :::::
            :::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        val props = extracted.propsBlocks.single().props
        fun timeline(name: String): String {
            val entry = (props.getValue(name) as RawArray).values.single() as RawObject
            val validTime = (entry.values.getValue("validTime") as RawArray).values.single() as RawObject
            return (validTime.values.getValue("timeline") as RawString).value
        }
        assertEquals("Outer", timeline("inherited"))
        assertEquals("Property", timeline("explicit"))
        assertEquals("Relation", extracted.relations.single().validTime.single().timeline)
    }

    @Test
    fun `parses skipped nesting levels and whitespace inside validTime arrays and strings`() {
        val extracted = extractor.extract(
            """
            ::: outer
            ::::: middle
            :::::::: inner validTime=[EraA, EraB(from=1, to={timecode=2, value="two words"})] tail
            text
            ::::::::
            :::::
            :::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals(listOf(3, 5, 8), extracted.blocks.map { it.fenceLength })
        val inner = extracted.blocks.last()
        assertEquals(listOf("inner", "tail"), inner.names)
        assertEquals(listOf("EraA", "EraB"), inner.validTime.map { it.timeline })
        assertEquals("two words", inner.validTime.last().to?.value)
    }

    @Test
    fun `reports invalid fence structure and ignores code block fences`() {
        val extracted = extractor.extract(
            """
            ```
            ::: code validTime=Hidden
            :::
            ```
            ::: outer
            ::: invalidNested
            ::::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.blocks.isEmpty())
        assertTrue(extracted.diagnostics.any { it.message.startsWith("Nested block fence must be longer") })
        assertTrue(extracted.diagnostics.any { it.message.startsWith("Block closing fence must match") })
        assertTrue(extracted.diagnostics.any { it.message.startsWith("Unclosed block fence") })
        assertTrue(extracted.diagnostics.none { "Hidden" in it.message })
    }

    @Test
    fun `does not apply a completed child block inside an unclosed parent`() {
        val extracted = extractor.extract(
            """
            ::: outer validTime=Outer
            ::::: child validTime=Child
            @props{age=10}
            :::::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.blocks.isEmpty())
        assertTrue(extracted.diagnostics.any { it.message.startsWith("Unclosed block fence") })
        val age = extracted.propsBlocks.single().props.getValue("age")
        assertTrue(age !is RawArray)
    }

    @Test
    fun `reports an isolated closing fence`() {
        val extracted = extractor.extract(":::::", "/tmp/alice.md", "alice")

        assertTrue(extracted.blocks.isEmpty())
        assertTrue(extracted.diagnostics.any { it.message == "Unexpected block closing fence" })
    }

    @Test
    fun `does not recognize fences in list or quote containers`() {
        val extracted = extractor.extract(
            """
            ::: outer validTime=Outer
            - list item
              ::::: nested validTime=ListTime
              :::::
            > ::::: quoted validTime=QuoteTime
            > :::::
            after containers
            :::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals(1, extracted.blocks.size)
        assertEquals("Outer", extracted.blocks.single().validTime.single().timeline)
    }

    @Test
    fun `block names use the ASCII identifier grammar`() {
        val extracted = extractor.extract(
            """
            ::: 履歴 validTime=CommonEra
            text
            :::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.blocks.isEmpty())
        assertTrue(extracted.diagnostics.any { it.message.startsWith("Invalid block header:") })
    }

    @Test
    fun `rejects malformed earlier validTime even when a later value is valid`() {
        val extracted = extractor.extract(
            """
            ::: history validTime=Broken(from=) validTime=Valid
            text
            :::
            """.trimIndent(),
            "/tmp/alice.md",
            "alice",
        )

        assertTrue(extracted.blocks.isEmpty())
        assertTrue(extracted.diagnostics.any { it.message.startsWith("Invalid block header:") })
    }
}
