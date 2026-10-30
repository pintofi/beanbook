package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.web.OldWebViewNotice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reports say so when the System WebView is too old for Fava's pages, and say nothing otherwise. */
@RunWith(AndroidJUnit4::class)
class OldWebViewNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anOldWebViewIsNamedWithItsVersion() {
        compose.setContent { MaterialTheme { OldWebViewNotice(major = 74) } }
        compose.onNodeWithText("This phone's WebView is out of date").assertIsDisplayed()
        compose.onNodeWithText("(Chrome 74)", substring = true).assertIsDisplayed()
    }

    @Test fun aCurrentWebViewGetsNoNotice() {
        compose.setContent { MaterialTheme { OldWebViewNotice(major = 135) } }
        compose.onNodeWithText("This phone's WebView is out of date").assertDoesNotExist()
    }

    @Test fun aWebViewThatDoesNotSayItsVersionGetsNoNotice() {
        compose.setContent { MaterialTheme { OldWebViewNotice(major = null) } }
        compose.onNodeWithText("This phone's WebView is out of date").assertDoesNotExist()
    }

    @Test fun atDoubleTextTheWholeNoticeIsStillThere() {
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 2f),
            ) { MaterialTheme { OldWebViewNotice(major = 74) } }
        }
        compose.onNodeWithText("Your ledger and the rest of the app are not affected.", substring = true)
            .assertExists()
    }
}
