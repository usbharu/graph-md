package dev.usbharu.graphmd.core

import dev.usbharu.graphmd.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InlinePropsParserTest {
    @Test
    fun `parses nested object and array`() {
        val parsed = InlinePropsParser("""{ name = "Alice", tags = [foo, "bar"], meta = { active = true, score = 1.5 } }""").parseObject()

        assertEquals("Alice", (parsed.values.getValue("name") as RawString).value)
        assertEquals(2, (parsed.values.getValue("tags") as RawArray).values.size)
        assertEquals(true, ((parsed.values.getValue("meta") as RawObject).values.getValue("active") as RawBoolean).value)
    }

    @Test
    fun `parses escaped strings`() {
        val parsed = InlinePropsParser("""{ text = "line\nquote: \"" }""").parseObject()

        assertEquals("line\nquote: \"", (parsed.values.getValue("text") as RawString).value)
    }

    @Test
    fun `rejects duplicate keys`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ name = "Alice", name = "Bob" }""").parseObject()
        }
    }

    @Test
    fun `rejects non comma array separator`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ tags = [foo bar] }""").parseObject()
        }
    }

    @Test
    fun `parses null negative integer and decimal`() {
        val parsed = InlinePropsParser("""{ missing = null, offset = -12, score = -0.5 }""").parseObject()

        assertEquals(RawNull, parsed.values.getValue("missing"))
        assertEquals(-12, (parsed.values.getValue("offset") as RawInteger).value)
        assertEquals(-0.5, (parsed.values.getValue("score") as RawNumber).value)
    }

    @Test
    fun `rejects unsupported escape and trailing content`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ text = "\x" }""").parseObject()
        }
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ name = "Alice" } trailing""").parseObject()
        }
    }

    @Test
    fun `parses unicode and empty containers`() {
        val parsed = InlinePropsParser("""{ value = "\u0041", empty = {}, list = [] }""").parseObject()

        assertEquals("A", (parsed.values.getValue("value") as RawString).value)
        assertEquals(emptyMap(), (parsed.values.getValue("empty") as RawObject).values)
        assertEquals(emptyList(), (parsed.values.getValue("list") as RawArray).values)
    }

    @Test
    fun `rejects invalid identifier and unterminated string`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ 1name = "Alice" }""").parseObject()
        }
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ name = "Alice }""").parseObject()
        }
    }

    @Test
    fun `rejects invalid numeric token and missing value`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ value = - }""").parseObject()
        }
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ value = }""").parseObject()
        }
    }

    @Test
    fun `parses all supported escapes and identifier characters`() {
        val parsed = InlinePropsParser("""{ _id = "a\r\t\\", ref = org:example.test-1 }""").parseObject()

        assertEquals("a\r\t\\", (parsed.values.getValue("_id") as RawString).value)
        assertEquals("org:example.test-1", (parsed.values.getValue("ref") as RawString).value)
    }

    @Test
    fun `rejects incomplete escape incomplete unicode and wrong opener`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ text = "\"""").parseObject()
        }
        assertFailsWith<Throwable> {
            InlinePropsParser("""{ text = "\u12" }""").parseObject()
        }
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""[1,2]""").parseObject()
        }
    }

    @Test
    fun `accepts trailing separators and multiline separators`() {
        val parsed = InlinePropsParser(
            """
            {
              name = "Alice",
              role = backend
            }
            """.trimIndent()
        ).parseObject()

        assertEquals("Alice", (parsed.values.getValue("name") as RawString).value)
        assertTrue(parsed.values.containsKey("role"))
    }

    @Test
    fun `rejects eof and malformed decimal edge cases`() {
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("").parseObject()
        }
        assertFailsWith<InlinePropsParseException> {
            InlinePropsParser("""{ value = 12. }""").parseObject()
        }
    }

    @Test
    fun `parses booleans null and trailing array comma`() {
        val parsed = InlinePropsParser("""{ flags = [true, false, null,], role = backend }""").parseObject()

        val flags = parsed.values.getValue("flags") as RawArray
        assertEquals(3, flags.values.size)
        assertEquals(RawNull, flags.values[2])
        assertEquals("backend", (parsed.values.getValue("role") as RawString).value)
    }
}
