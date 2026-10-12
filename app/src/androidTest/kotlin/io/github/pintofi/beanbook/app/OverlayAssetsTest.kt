package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plugin overlays are really in the APK (B101).
 *
 * Android's asset packaging drops directories whose names start with an underscore, and the
 * fava-portfolio-returns overlay lives under `_vendor`: the SciPy-free `returns.py` was
 * missing from every build while its `REMOVES.txt` shipped, and the plugin failed to load.
 */
@RunWith(AndroidJUnit4::class)
class OverlayAssetsTest {

    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test fun theVendoredBeangrowOverlayIsPackaged() {
        val path = "overlays/fava_portfolio_returns-2.7.0/fava_portfolio_returns/_vendor/beangrow/returns.py"
        val text = assets.open(path).bufferedReader().use { it.readText() }

        assertTrue("overlay file is empty", text.isNotBlank())
        assertFalse("the overlay still imports scipy", Regex("""(?m)^\s*(from|import)\s+scipy""").containsMatchIn(text))
    }
}
