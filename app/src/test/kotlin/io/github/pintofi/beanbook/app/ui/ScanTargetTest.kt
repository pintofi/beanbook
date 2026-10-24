package io.github.pintofi.beanbook.app.ui

import io.github.pintofi.beanbook.core.domain.CapturedPage
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTargetTest {

    private val pages = listOf(CapturedPage("a.jpg"), CapturedPage("b.jpg"))

    @Test fun `a target survives being saved and restored`() {
        listOf(ScanTarget.Append, ScanTarget.Replace(1, "b.jpg")).forEach {
            assertEquals(it, ScanTarget.decode(it.encode()))
        }
    }

    @Test fun `anything unreadable restores as adding`() {
        assertEquals(ScanTarget.Append, ScanTarget.decode(emptyList()))
        assertEquals(ScanTarget.Append, ScanTarget.decode(listOf("replace", 1)))
    }

    @Test fun `a retake of the page that is still there stays a retake`() {
        val target = ScanTarget.Replace(1, "b.jpg")
        assertEquals(target, target.checkedAgainst(pages))
    }

    @Test fun `a retake of a page that moved or went falls back to adding`() {
        assertEquals(ScanTarget.Append, ScanTarget.Replace(0, "b.jpg").checkedAgainst(pages))
        assertEquals(ScanTarget.Append, ScanTarget.Replace(5, "b.jpg").checkedAgainst(pages))
        assertEquals(ScanTarget.Append, ScanTarget.Replace(1, "b.jpg").checkedAgainst(emptyList()))
    }
}
