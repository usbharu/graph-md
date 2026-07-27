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
        val body = """
                @props{name = "Ignored"}
                @link{}[Ignored](ignored friendOf)

            @link{}[Bob](bob friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
        assertEquals("bob", extracted.relations.single().target)
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
}
