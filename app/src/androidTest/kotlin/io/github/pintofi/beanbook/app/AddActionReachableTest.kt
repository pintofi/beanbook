package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * The app's primary action stays reachable.
 *
 * Written after it was not. Add stopped being a navigation destination when it became a
 * floating button, and the button was then gated on `PaneLayout.COMPACT` — so on any screen
 * wider than a phone there was no way to add an entry at all. Every unit test, both
 * flavours and both API levels passed, because they render composables at one width and
 * none of them asks whether the app can reach its own primary action.
 *
 * `showsAddAction` takes no layout, which is the real guard; this pins the rest of the rule
 * so the surfaces it appears on are a decision somebody has to change on purpose.
 */
@RunWith(AndroidJUnit4::class)
class AddActionReachableTest {

    @Test fun addIsOfferedOnEverySurfaceThatShowsTheLedger() {
        listOf(Surface.HOME, Surface.JOURNAL, Surface.BALANCES).forEach { surface ->
            // Message first: this is org.junit's Assert. Written the kotlin.test way
            // round, the message becomes the condition and the assertion always passes.
            assertTrue(
                "Add is not offered on $surface, so the ledger cannot be added to from it",
                showsAddAction(surface, adding = false, hasLedger = true),
            )
        }
    }

    @Test fun addIsNotOfferedWhereItWouldBeWrong() {
        // More is a list it would cover a row of; Reports is a Fava page.
        assertFalse("Add on More", showsAddAction(Surface.MORE, adding = false, hasLedger = true))
        assertFalse("Add on Reports", showsAddAction(Surface.REPORTS, adding = false, hasLedger = true))
        // A button that opens the form that is already open.
        assertFalse("Add while the form is open", showsAddAction(Surface.HOME, adding = true, hasLedger = true))
        // Nothing to add to yet.
        assertFalse("Add with no ledger", showsAddAction(Surface.HOME, adding = false, hasLedger = false))
    }
}
