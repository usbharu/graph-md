package dev.usbharu.graphmd.core

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
            Alice is friends with @[Bob](bob friendOf){weight = 0.82}

            ```md
            @props{name = "Ignored"}
            @[Ignored](ignored friendOf)
            ```

            `@[Inline](inline friendOf)`
            \@[Escaped](escaped friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.propsBlocks.size)
        assertEquals(1, extracted.relations.size)
        assertEquals("Alice", (extracted.propsBlocks.single().props.getValue("name") as RawString).value)
        assertEquals("bob", extracted.relations.single().target)
    }

    @Test
    fun `supports escaped label characters`() {
        val body = """@[Bob \] Jr.](bob-jr friendOf)"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals("Bob ] Jr.", extracted.relations.single().label)
    }

    @Test
    fun `supports quoted rel type`() {
        val body = """@[Bob](bob "friendOf"){weight = 0.82}"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.isEmpty(), extracted.diagnostics.joinToString("\n") { it.message })
        assertEquals("friendOf", extracted.relations.single().relType)
    }

    @Test
    fun `reports malformed relation`() {
        val body = """@[Bob](bob)"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { it.category == DiagnosticCategory.SyntaxError })
    }

    @Test
    fun `reports malformed props and invalid relation props`() {
        val body = """
            @props{name = "Alice"
            @[Bob](bob friendOf){weight = }
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { "Unclosed @props block" in it.message })
        assertTrue(extracted.diagnostics.any { it.category == DiagnosticCategory.SyntaxError })
    }

    @Test
    fun `ignores indented code blocks`() {
        val body = """
                @props{name = "Ignored"}
                @[Ignored](ignored friendOf)

            @[Bob](bob friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
        assertEquals("bob", extracted.relations.single().target)
    }

    @Test
    fun `reports unclosed relation label and target`() {
        val body = """
            @[Bob(bob friendOf)
            @[Carol](carol friendOf
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { "Unclosed relation label" in it.message })
        assertTrue(extracted.diagnostics.any { "Unclosed relation target" in it.message })
    }

    @Test
    fun `reports missing relation paren and unclosed relation props`() {
        val body = """
            @[Bob]x
            @[Carol](carol friendOf){weight = 1
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertTrue(extracted.diagnostics.any { "Relation must be followed by (...)" in it.message })
        assertTrue(extracted.diagnostics.any { "Unclosed relation props" in it.message })
    }

    @Test
    fun `handles unterminated fenced and inline code spans`() {
        val body = """
            ```md
            @[Ignored](ignored friendOf)
            @props{name = "Ignored"}
            @[Bob](bob friendOf)
            `@[StillIgnored](ignored friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(0, extracted.relations.size)
        assertEquals(0, extracted.propsBlocks.size)
    }

    @Test
    fun `treats double backslash marker as unescaped`() {
        val body = """\\@[Bob](bob friendOf)"""

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
    }

    @Test
    fun `supports relation without props and ignores props without brace`() {
        val body = """
            @[Bob](bob friendOf)
            @props name = "ignored"
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
        assertEquals(0, extracted.propsBlocks.size)
    }

    @Test
    fun `treats nested braces and escaped strings as balanced`() {
        val body = """
            @props{meta = { text = "a\"b", nested = { value = "x" } }}
            @[Bob](bob friendOf){meta = { value = "{x}" }}
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
            @[Bob](bob friendOf)
        """.trimIndent()

        val extracted = extractor.extract(body, "/tmp/alice.md", "alice")

        assertEquals(1, extracted.relations.size)
        assertTrue(extracted.diagnostics.isEmpty())
    }

    @Test
    fun `returns empty extraction for plain text`() {
        val extracted = extractor.extract("plain text only", "/tmp/plain.md", "plain")

        assertEquals(0, extracted.relations.size)
        assertEquals(0, extracted.propsBlocks.size)
        assertEquals(0, extracted.diagnostics.size)
    }
}
