package dev.beancount.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Colouring Beancount source without changing a byte of it (B12).
 *
 * The editor is the one screen where the user sees the file as it really is, so the
 * highlighter has two obligations beyond looking right: it must never alter the text, and its
 * spans must never overlap — an overlap means two colours claim the same character and which
 * one wins is a detail of the renderer rather than a decision anybody made.
 *
 * The precedence that matters is comments and strings first. A comment mentioning
 * `Assets:Bank` is prose, and an account name inside a quoted narration is part of the
 * string, not an account.
 */
class HighlightTest {

    private fun kindsIn(source: String) =
        BeancountText.highlight(source).map { it.kind }.toSet()

    private fun spanText(source: String, kind: BeancountText.TokenKind) =
        BeancountText.highlight(source).filter { it.kind == kind }
            .map { source.substring(it.start, it.end) }

    @Test
    fun `spans never overlap and stay inside the text`() {
        val source = """
            ; a comment mentioning Assets:Bank and 2026-01-01
            2026-02-01 * "Shop" "coffee at Assets:Fake"
              Expenses:Food   12.00 USD
              Assets:Bank:Checking
        """.trimIndent()

        val spans = BeancountText.highlight(source).sortedBy { it.start }

        spans.zipWithNext().forEach { (a, b) ->
            assertTrue(a.end <= b.start, "spans overlap: $a then $b")
        }
        spans.forEach {
            assertTrue(it.start in 0..source.length && it.end in 0..source.length, "out of range: $it")
            assertTrue(it.start < it.end, "empty or inverted span: $it")
        }
    }

    @Test
    fun `a comment is one span, whatever it mentions`() {
        val source = "; Assets:Bank 2026-01-01 12.00 USD\n"

        val comments = spanText(source, BeancountText.TokenKind.COMMENT)

        assertEquals(1, comments.size, "expected the whole comment as one span: $comments")
        assertTrue(comments.first().startsWith(";"), comments.first())
        assertEquals(
            setOf(BeancountText.TokenKind.COMMENT),
            kindsIn(source),
            "something inside a comment was coloured as code",
        )
    }

    @Test
    fun `an account name inside a string stays part of the string`() {
        val source = """2026-02-01 * "paid Assets:Bank:Checking""""

        val accounts = spanText(source, BeancountText.TokenKind.ACCOUNT)

        assertTrue(
            accounts.isEmpty(),
            "an account inside a quoted string was coloured as an account: $accounts",
        )
        assertEquals(1, spanText(source, BeancountText.TokenKind.STRING).size)
    }

    @Test
    fun `dates accounts amounts and currencies are found in an ordinary posting`() {
        val source = "2026-02-01 * \"Shop\"\n  Expenses:Food   12.00 USD\n"

        assertEquals(listOf("2026-02-01"), spanText(source, BeancountText.TokenKind.DATE))
        assertEquals(listOf("Expenses:Food"), spanText(source, BeancountText.TokenKind.ACCOUNT))
        assertEquals(listOf("12.00"), spanText(source, BeancountText.TokenKind.NUMBER))
        assertEquals(listOf("USD"), spanText(source, BeancountText.TokenKind.CURRENCY))
    }

    @Test
    fun `directive keywords are recognised`() {
        val source = "2026-01-01 open Assets:Bank\n2026-01-02 balance Assets:Bank 1.00 USD\n"

        val keywords = spanText(source, BeancountText.TokenKind.KEYWORD)

        assertTrue("open" in keywords && "balance" in keywords, "found: $keywords")
    }

    @Test
    fun `an empty file produces nothing rather than failing`() {
        assertEquals(emptyList(), BeancountText.highlight(""))
    }
}
