package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class FlagChoiceTest {

    @Test fun `none, a star and txn are cleared, and an exclamation mark is pending`() {
        listOf(null, "", "*", "txn").forEach { assertEquals(FlagChoice(), FlagChoice.of(it)) }
        assertEquals(FlagOption.PENDING, FlagChoice.of("!").option)
    }

    @Test fun `any other flag is an other flag and keeps its text, even one Beancount would refuse`() {
        assertEquals(FlagChoice(FlagOption.OTHER, "P"), FlagChoice.of("P"))
        assertEquals("p", FlagChoice.of("p").flag)
    }

    @Test fun `the flag follows the chosen option, and what was typed survives a change of option`() {
        val typed = FlagChoice().withOther(" T ")
        assertEquals("T", typed.flag)
        assertEquals("!", typed.choose(FlagOption.PENDING).flag)
        assertEquals("T", typed.choose(FlagOption.PENDING).choose(FlagOption.OTHER).flag)
    }
}
