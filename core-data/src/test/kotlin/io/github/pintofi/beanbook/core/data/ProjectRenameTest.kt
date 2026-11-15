package io.github.pintofi.beanbook.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectRenameTest {
    private fun apply(text: String, title: String) = ProjectRename.apply(text.toByteArray(), title)

    @Test
    fun `a ledger with no title gets one line at the top and the rest is untouched`() {
        val result = apply("; header\n2024-01-01 open Assets:Cash\n", "Household").getOrThrow()
        assertEquals("option \"title\" \"Household\"\n; header\n2024-01-01 open Assets:Cash\n", result)
    }

    @Test
    fun `every title line is replaced and what follows it stays`() {
        val text = "; My old name\noption \"title\" \"Old\" ; keep\noption \"operating_currency\" \"USD\"\n" +
            "option \"title\" \"Older\"\n"
        val result = apply(text, "New").getOrThrow()
        assertEquals(
            "; My old name\noption \"title\" \"New\" ; keep\noption \"operating_currency\" \"USD\"\n" +
                "option \"title\" \"New\"\n",
            result,
        )
    }

    @Test
    fun `quotes and backslashes are escaped`() {
        val result = apply("", "A \"b\" \\ c").getOrThrow()
        assertTrue(result.startsWith("option \"title\" \"A \\\"b\\\" \\\\ c\""), result)
    }

    @Test
    fun `the file's own line ending is used when a title is added`() {
        assertEquals("option \"title\" \"X\"\r\n2024\r\n", apply("2024\r\n", "X").getOrThrow())
    }

    @Test
    fun `a byte order mark stays first`() {
        assertEquals("﻿option \"title\" \"X\"\n;a\n", apply("﻿;a\n", "X").getOrThrow())
    }

    @Test
    fun `a file that is not UTF-8 is refused`() {
        val failure = ProjectRename.apply(byteArrayOf(0x3b, 0xE9.toByte(), 0x0a), "X").exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure.message!!.contains("UTF-8"))
    }

    @Test
    fun `blank, multi-line and over-long names are refused, duplicates are not`() {
        assertNotNull(ProjectRename.problemWith("  "))
        assertNotNull(ProjectRename.problemWith("a\nb"))
        assertNotNull(ProjectRename.problemWith("x".repeat(101)))
        assertEquals(null, ProjectRename.problemWith("x".repeat(100)))
        assertTrue(apply("option \"title\" \"Same\"\n", "Same").isSuccess)
    }

    @Test
    fun `the name is trimmed`() {
        assertEquals("option \"title\" \"X\"\n", apply("", "  X ").getOrThrow())
    }
}
