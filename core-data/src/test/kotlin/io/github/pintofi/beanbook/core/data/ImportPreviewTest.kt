package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.LinkedFile
import io.github.pintofi.beanbook.core.domain.LinkedKind
import io.github.pintofi.beanbook.core.domain.LinkedLedger
import kotlin.test.Test
import kotlin.test.assertEquals

class ImportPreviewTest {

    private fun ledger(vararg files: LinkedFile, up: Int = 0) =
        LinkedLedger("hmw", files.toList(), emptyList(), up, up, false)

    @Test
    fun `the tree is the project's layout, folders first, with the main file marked`() {
        val lines = ImportPreview.lines(
            ledger(
                LinkedFile("hmw", LinkedKind.MAIN, 10),
                LinkedFile("2025/jan.bean", LinkedKind.LEDGER, 5),
                LinkedFile("accounts.bean", LinkedKind.LEDGER, 3),
                LinkedFile("scans/a.pdf", LinkedKind.DOCUMENT, 99),
            ),
            emptyList(),
        )

        assertEquals(
            listOf("2025", "jan.bean", "scans", "a.pdf", "accounts.bean", "hmw"),
            lines.map { it.name },
        )
        assertEquals(listOf(0, 1, 0, 1, 0, 0), lines.map { it.depth })
        assertEquals(listOf(true, false, true, false, false, false), lines.map { it.isFolder })
        assertEquals("hmw", lines.single { it.isMain }.name)
        assertEquals(LinkedKind.DOCUMENT, lines.single { it.name == "a.pdf" }.kind)
    }

    @Test
    fun `a ledger that reaches above the main file shows the folders it reaches into`() {
        val lines = ImportPreview.lines(
            ledger(
                LinkedFile("hmw", LinkedKind.MAIN, 1),
                LinkedFile("../shared.bean", LinkedKind.LEDGER, 1),
                up = 1,
            ),
            listOf("books"),
        )

        assertEquals(listOf("books", "hmw", "shared.bean"), lines.map { it.name })
        assertEquals(listOf(true, false, false), lines.map { it.isFolder })
    }

    @Test
    fun `nothing but the linked files is named`() {
        val lines = ImportPreview.lines(ledger(LinkedFile("hmw", LinkedKind.MAIN, 1)), emptyList())

        assertEquals(listOf("hmw"), lines.map { it.name })
    }
}
