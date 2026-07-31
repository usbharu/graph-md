package dev.usbharu.graphmd.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommonMarkCodeMaskerTest {
    @Test
    fun `retains offsets and ends fences when their containers end`() {
        val source = """
            > ```
            > hidden
            after quote
            - ```
              hidden
            after list
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        assertEquals(source.length, masked.length)
        assertEquals(
            source.mapIndexedNotNull { index, char -> index.takeIf { char == '\n' } },
            masked.mapIndexedNotNull { index, char -> index.takeIf { char == '\n' } },
        )
        assertTrue("hidden" !in masked, masked)
        assertTrue("after quote" in masked, masked)
        assertTrue("after list" in masked, masked)
    }

    @Test
    fun `blank lines end quote fences but remain inside list fences`() {
        val source = """
            > ```
            > @link[QuoteCode](quote-code friendOf)

            > @link[NewQuote](new-quote friendOf)
            - ```
              @link[ListCode](list-code friendOf)

              @link[ListCodeAfterBlank](list-code-after-blank friendOf)
              ```
            @link[AfterList](after-list friendOf)
            - > ```
              > @link[NestedQuoteCode](nested-quote-code friendOf)

              > @link[NewNestedQuote](new-nested-quote friendOf)
            > - ```
            >   @link[QuoteListCode](quote-list-code friendOf)
            >
            >   @link[QuoteListAfterBlank](quote-list-after-blank friendOf)
            >   ```
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        listOf("quote-code", "list-code", "list-code-after-blank", "nested-quote-code", "quote-list-code", "quote-list-after-blank")
            .forEach { assertTrue(it !in masked, masked) }
        listOf("new-quote", "after-list", "new-nested-quote")
            .forEach { assertTrue(it in masked, masked) }
    }

    @Test
    fun `keeps leaf paragraph state inside list and quote containers`() {
        val source = """
            - paragraph
                @link[ListParagraph](list-paragraph friendOf)
            > paragraph
            >     @link[QuoteParagraph](quote-paragraph friendOf)
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        assertTrue("@link[ListParagraph]" in masked, masked)
        assertTrue("@link[QuoteParagraph]" in masked, masked)
    }

    @Test
    fun `tracks ordered nested container stacks for fences`() {
        val source = """
            - > ```
              > @link[ListQuoteCode](list-quote-code friendOf)
              > ```
            @link[AfterListQuote](after-list-quote friendOf)
            > - ~~~
            >   @link[QuoteListCode](quote-list-code friendOf)
            >   ~~~
            @link[AfterQuoteList](after-quote-list friendOf)
            - - ````
                @link[NestedListCode](nested-list-code friendOf)
                `````
            @link[AfterNestedList](after-nested-list friendOf)
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        assertTrue("list-quote-code" !in masked, masked)
        assertTrue("quote-list-code" !in masked, masked)
        assertTrue("nested-list-code" !in masked, masked)
        assertTrue("after-list-quote" in masked, masked)
        assertTrue("after-quote-list" in masked, masked)
        assertTrue("after-nested-list" in masked, masked)
    }

    @Test
    fun `ordered list interruption respects the paragraph start number rule`() {
        val source = """
            paragraph
            2. ~~~
            @link[Visible](visible friendOf)

            1. ~~~
               @link[Hidden](hidden friendOf)
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        assertTrue("@link[Visible]" in masked, masked)
        assertTrue("hidden" !in masked, masked)
    }

    @Test
    fun `does not pair backtick runs across inline block boundaries`() {
        val source = """
            ` @link[FirstParagraph](first friendOf)

            ` @link[SecondParagraph](second friendOf)
            # ` @link[Heading](heading friendOf)
            ` @link[AfterHeading](after-heading friendOf)

            ` @link[SetextHeading](setext-heading friendOf)
            ===
            ` @link[AfterSetext](after-setext friendOf)
            - ` @link[FirstItem](first-item friendOf)
            - ` @link[SecondItem](second-item friendOf)
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        listOf(
            "FirstParagraph",
            "SecondParagraph",
            "Heading",
            "AfterHeading",
            "SetextHeading",
            "AfterSetext",
            "FirstItem",
            "SecondItem",
        ).forEach { assertTrue("@link[$it]" in masked, masked) }
    }

    @Test
    fun `allows an escaped backtick to close a span but not open one`() {
        val source = """
            `@link[Hidden](hidden friendOf)\`
            \` @link[Visible](visible friendOf)
        """.trimIndent()

        val masked = CommonMarkCodeMasker.mask(source)

        assertTrue("hidden" !in masked, masked)
        assertTrue("@link[Visible]" in masked, masked)
    }

    @Test
    fun `supports empty list item containers without interrupting paragraphs`() {
        val blocks = listOf(
            """
                -
                  ```
                  @link[BulletCode](bullet-code friendOf)
                  ```
                @link[AfterBullet](after-bullet friendOf)
            """.trimIndent(),
            """
                1.
                   ~~~
                   @link[OrderedCode](ordered-code friendOf)
                @link[AfterOrdered](after-ordered friendOf)
            """.trimIndent(),
        )

        blocks.forEach { source ->
            val masked = CommonMarkCodeMasker.mask(source)
            assertTrue("Code]" !in masked, masked)
            assertTrue("@link[After" in masked, masked)
        }

        listOf(
            "paragraph\n*\n    @link[BulletParagraph](bullet-paragraph friendOf)",
            "paragraph\n1.\n   @link[OneParagraph](one-paragraph friendOf)",
            "paragraph\n2.\n   @link[TwoParagraph](two-paragraph friendOf)",
        ).forEach { source ->
            val masked = CommonMarkCodeMasker.mask(source)
            assertTrue("@link[" in masked, masked)
        }
    }

    @Test
    fun `handles many unmatched delimiter lengths without rescanning block text`() {
        val source = buildString {
            for (length in 1..512) {
                append("`".repeat(length))
                append('x')
            }
            append("@link[Visible](visible friendOf)")
        }

        val masked = CommonMarkCodeMasker.mask(source)

        assertEquals(source.length, masked.length)
        assertTrue("@link[Visible]" in masked)
    }
}
