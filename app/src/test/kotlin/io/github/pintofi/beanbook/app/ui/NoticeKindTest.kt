package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeKindTest {
    @Test
    fun refusals_are_errors() {
        listOf(
            "Not saved: it would add 1 error to your ledger. Your ledger is unchanged.",
            "Could not read that file.",
            "Not imported. The file is too large.",
            "Backup failed: no space left.",
        ).forEach { assertEquals(it, NoticeKind.ERROR, noticeKindOf(it)) }
    }

    @Test
    fun finished_work_is_a_success() {
        listOf("Imported 5 files.", "Saved.", "Moved to the trash. Nothing was deleted from any export you made.")
            .forEach { assertEquals(it, NoticeKind.SUCCESS, noticeKindOf(it)) }
    }

    @Test
    fun anything_else_is_information() {
        listOf("Nothing was imported yet.", "Sharing. Stops when you tap Stop sharing.", "Taken 2026-11-15.")
            .forEach { assertEquals(it, NoticeKind.INFO, noticeKindOf(it)) }
    }
}
