package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * Back, as one stack rather than four conditions that had to agree with each other.
 */
@RunWith(AndroidJUnit4::class)
class BackstackTest {

    @Test fun backClosesTheNewestThingFirst() {
        val stack = Backstack()
            .open(Overlay.Section(MoreSection.BACKUP))
            .open(Overlay.AddEntry)

        val afterOne = stack.back()!!
        assertTrue("the add form should close first", !afterOne.isAdding)
        assertEquals(MoreSection.BACKUP, afterOne.openSection)

        val afterTwo = afterOne.back()!!
        assertNull("the section should close next", afterTwo.openSection)
    }

    @Test fun backFromAnotherTabReturnsHomeBeforeLeaving() {
        val onJournal = Backstack(tab = Surface.JOURNAL)
        assertEquals(Surface.HOME, onJournal.back()!!.tab)
    }

    @Test fun backFromHomeWithNothingOpenLeavesTheApp() {
        // null, not an unchanged copy: the caller has to be able to tell "handled" from
        // "let the system take it", or Back does nothing on the home screen.
        assertNull(Backstack().back())
    }

    @Test fun openingTheSameOverlayTwiceDoesNotStackIt() {
        val once = Backstack().open(Overlay.AddEntry)
        val twice = once.open(Overlay.AddEntry)
        assertEquals(1, twice.overlays.size)
        assertNull("one Back should leave nothing open", twice.back()!!.top)
    }

    @Test fun switchingTabClosesWhatWasOpenOverTheOldOne() {
        val stack = Backstack()
            .open(Overlay.Section(MoreSection.PLUGINS))
            .switchTo(Surface.BALANCES)
        assertEquals(Surface.BALANCES, stack.tab)
        assertNull("a section from More should not follow you to Balances", stack.openSection)
    }

    @Test fun onlyTheTopOverlayIsReportedOpen() {
        val stack = Backstack()
            .open(Overlay.Contribute)
            .open(Overlay.AddEntry)
        assertTrue(stack.isAdding)
        assertTrue("Contribute is underneath, not open", !stack.isContributing)
    }
}
