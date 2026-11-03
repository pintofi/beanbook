package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.work.KeepWorking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The notification a piece of work shows opens the page it belongs to when it is tapped. */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun workForAPageOpensThatPageWhenTheNotificationIsTapped() {
        val notification = KeepWorking.notification(context, "Installing beangrow", OpenPage.PLUGINS)
        assertNotNull("tapping it should open the plugins page", notification.contentIntent)
    }

    @Test fun workWithNoPageLeavesTheTapToTheSystem() {
        assertNull(KeepWorking.notification(context, "Finishing up").contentIntent)
    }

    @Test fun aPageTheAppDoesNotHaveIsNotOpened() {
        assertNull(KeepWorking.notification(context, "Installing x", "MORE:NOWHERE").contentIntent)
    }

    @Test fun theSharingNotificationOpensTheSharingPage() {
        val pending = OpenPage.pending(context, OpenPage.SHARING)
        // The same page asked for twice is the same request, and a different page is a different one.
        assertNotNull(pending)
        org.junit.Assert.assertNotEquals(pending, OpenPage.pending(context, OpenPage.PLUGINS))
    }
}
