package io.github.pintofi.beanbook.app

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.work.KeepWorking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The notice shown while work runs says what is being done, and nothing more. */
@RunWith(AndroidJUnit4::class)
class WorkNotificationTest {
    @Test fun theNotificationHasATitleAndNoDescription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extras = KeepWorking.notification(context, "Installing pandas").extras

        assertEquals("Installing pandas", extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNull(extras.getCharSequence(Notification.EXTRA_TEXT))
    }
}
