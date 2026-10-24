package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AccountDetailsTest {

    private val block = listOf(
        "2026-01-01 open Assets:Bank:Checking EUR",
        "  institution: \"Corner Bank\"",
        "    ; the spacing and this comment are the person's own",
        "  iban-last4:   4321",
        "  rank: 2",
    ).joinToString("\n")

    private val original = linkedMapOf("institution" to "Corner Bank", "iban-last4" to "4321", "rank" to "2")

    private fun changed(edited: Map<String, String>): String =
        assertIs<AccountDetails.Result.Changed>(AccountDetails.rewrite(block, original, edited)).text

    @Test
    fun `nothing edited leaves the directive alone`() {
        assertEquals(AccountDetails.Result.Unchanged, AccountDetails.rewrite(block, original, original))
    }

    @Test
    fun `a changed value is rewritten and every other line stays byte for byte`() {
        val text = changed(original + ("institution" to "Harbour Bank"))
        assertEquals(
            block.replace("\"Corner Bank\"", "\"Harbour Bank\""),
            text,
        )
    }

    @Test
    fun `a removed key loses its line and a blank value removes it too`() {
        val without = original - "rank"
        assertEquals(block.replace("\n  rank: 2", ""), changed(without))
        assertEquals(block.replace("\n  rank: 2", ""), changed(original + ("rank" to "  ")))
    }

    @Test
    fun `new keys are added at the end in the order given`() {
        val text = changed(original + ("number" to "0123") + ("branch" to "North"))
        assertEquals(block + "\n  number: \"0123\"\n  branch: \"North\"", text)
    }

    @Test
    fun `everything at once`() {
        val text = changed(linkedMapOf("institution" to "Harbour Bank", "rank" to "3", "note" to "main"))
        assertEquals(
            listOf(
                "2026-01-01 open Assets:Bank:Checking EUR",
                "  institution: \"Harbour Bank\"",
                "    ; the spacing and this comment are the person's own",
                "  rank: 3",
                "  note: \"main\"",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun `a name Beancount would reject is refused and a block that is not an open directive is too`() {
        assertIs<AccountDetails.Result.Refused>(AccountDetails.rewrite(block, original, original + ("Bad Key" to "x")))
        assertIs<AccountDetails.Result.Refused>(
            AccountDetails.rewrite("2026-01-01 close Assets:Cash", emptyMap(), mapOf("a" to "b")),
        )
    }

    @Test
    fun `an open directive with no details gains them`() {
        val bare = "2026-01-01 open Assets:Cash"
        val result = AccountDetails.rewrite(bare, emptyMap(), mapOf("institution" to "Corner Bank"))
        assertEquals(
            AccountDetails.Result.Changed("2026-01-01 open Assets:Cash\n  institution: \"Corner Bank\""),
            result,
        )
    }
}
