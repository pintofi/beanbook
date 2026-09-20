package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconciling the files in a project's document folder with the ledger's `document`
 * directives (B18).
 *
 * The screen listed the folder and nothing else, so it could not say what a document was
 * filed under, and a file the ledger had never heard of looked exactly like one it had.
 * Those are different situations and only one of them is finished bookkeeping.
 *
 * The third case is the one invariant 5 cares about: a directive naming a file that is not
 * there. Silently dropping it from the list would hide a broken link, which is the failure
 * that invariant exists to catch.
 */
class DocumentIndexTest {

    private val root = "/data/project"

    private fun directive(path: String, account: String, date: String = "2026-02-01") =
        DocumentDirective(path = path, account = account, date = date)

    @Test
    fun `a file with a directive is filed under its account`() {
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/receipt.pdf"),
            directives = listOf(directive("/data/project/documents/receipt.pdf", "Expenses:Food")),
        )

        assertEquals(1, rows.size)
        assertEquals("documents/receipt.pdf", rows[0].path)
        assertEquals("Expenses:Food", rows[0].account)
        assertEquals(DocumentStatus.FILED, rows[0].status)
    }

    @Test
    fun `a file with no directive is unfiled rather than hidden`() {
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/stray.pdf"),
            directives = emptyList(),
        )

        assertEquals(DocumentStatus.UNFILED, rows[0].status)
        assertEquals(null, rows[0].account)
    }

    @Test
    fun `a directive naming a file that is gone is reported, not dropped`() {
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = emptyList(),
            directives = listOf(directive("/data/project/documents/lost.pdf", "Assets:Bank")),
        )

        assertEquals(1, rows.size)
        assertEquals(DocumentStatus.MISSING, rows[0].status)
        assertEquals("Assets:Bank", rows[0].account)
    }

    @Test
    fun `a directive pointing outside the project keeps its absolute path`() {
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = emptyList(),
            directives = listOf(directive("/sdcard/Download/tax.pdf", "Assets:Bank")),
        )

        assertEquals("/sdcard/Download/tax.pdf", rows[0].path)
        assertEquals(DocumentStatus.MISSING, rows[0].status)
    }

    @Test
    fun `one file filed under two accounts is listed once per directive`() {
        // Beancount allows it, so the app has to show it rather than pick one. Showing a
        // single row would make the second directive invisible and unremovable.
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/shared.pdf"),
            directives = listOf(
                directive("/data/project/documents/shared.pdf", "Assets:Bank"),
                directive("/data/project/documents/shared.pdf", "Expenses:Food"),
            ),
        )

        assertEquals(2, rows.size)
        assertEquals(setOf("Assets:Bank", "Expenses:Food"), rows.mapNotNull { it.account }.toSet())
        assertTrue(rows.all { it.status == DocumentStatus.FILED })
    }

    @Test
    fun `a trailing slash on the root does not stop a file matching its directive`() {
        val rows = DocumentIndex.reconcile(
            root = "$root/",
            onDisk = listOf("documents/receipt.pdf"),
            directives = listOf(directive("/data/project/documents/receipt.pdf", "Expenses:Food")),
        )

        assertEquals(DocumentStatus.FILED, rows[0].status)
    }

    @Test
    fun `rows are ordered by path so the list does not reshuffle between loads`() {
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/b.pdf", "documents/a.pdf"),
            directives = listOf(directive("/data/project/documents/c.pdf", "Assets:Bank")),
        )

        assertEquals(listOf("documents/a.pdf", "documents/b.pdf", "documents/c.pdf"), rows.map { it.path })
    }

    /** B89: the directive and Beancount's own discovery of the same file are one document. */
    @Test
    fun `a document found twice under the same account is listed once`() {
        val path = "/data/project/documents/Expenses/Food/2026-09-13.receipt.pdf"
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/Expenses/Food/2026-09-13.receipt.pdf"),
            directives = listOf(
                directive(path, "Expenses:Food", date = "2026-09-13"),
                directive(path, "Expenses:Food", date = "2026-09-13"),
            ),
        )

        assertEquals(1, rows.size)
    }

    /** B92: unlinking removes the written directive; the folder keeps making one of its own. */
    @Test
    fun `a document filed only by its folder is shown as not linked`() {
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/Expenses/Food/2026-09-13.receipt.pdf"),
            directives = listOf(
                DocumentDirective(
                    path = "/data/project/documents/Expenses/Food/2026-09-13.receipt.pdf",
                    account = "Expenses:Food",
                    date = "2026-09-13",
                    auto = true,
                ),
            ),
        )

        val row = rows.single()
        assertEquals(false, row.linked)
        assertEquals(
            "Not linked. It counts under Expenses:Food because of the folder it is in",
            row.summary,
        )
    }

    /** A line somebody wrote wins over the one the folder produced, so Unlink still works. */
    @Test
    fun `a written directive is preferred to the folder's own`() {
        val path = "/data/project/documents/Expenses/Food/2026-09-13.receipt.pdf"
        val rows = DocumentIndex.reconcile(
            root = root,
            onDisk = listOf("documents/Expenses/Food/2026-09-13.receipt.pdf"),
            directives = listOf(
                DocumentDirective(path, "Expenses:Food", "2026-09-13", auto = true),
                DocumentDirective(path, "Expenses:Food", "2026-09-13", auto = false),
            ),
        )

        val row = rows.single()
        assertEquals(true, row.linked)
        assertEquals("Filed under Expenses:Food", row.summary)
    }
}

