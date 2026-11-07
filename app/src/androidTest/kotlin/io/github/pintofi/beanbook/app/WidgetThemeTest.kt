package io.github.pintofi.beanbook.app

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.app.ui.theme.ThemeSettings
import io.github.pintofi.beanbook.app.widget.WidgetMetric
import io.github.pintofi.beanbook.app.widget.WidgetRenderer
import io.github.pintofi.beanbook.core.data.WidgetSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A widget is drawn in the app's own colours: under dynamic colour from the system palette, and in light
 * or dark as the launcher shows it. Drawn here the way a launcher draws it, once as day and once as night.
 */
@RunWith(AndroidJUnit4::class)
class WidgetThemeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val stateDir = context.filesDir.resolve("state")
    private val appearance = stateDir.resolve("appearance")
    private val before = appearance.takeIf { it.isFile }?.readText()

    @After fun restore() {
        if (before == null) appearance.delete() else appearance.writeText(before)
    }

    private fun snapshot() = WidgetSnapshot(
        projectId = "p", projectTitle = "Books", generatedAtEpochMs = System.currentTimeMillis(),
        balances = mapOf("Assets:Bank:Current" to "10.00 EUR", "Assets:Bank:Savings" to "250.00 EUR"),
        netWorth = "260.00 EUR", periodIncome = null, periodExpense = null, clean = true,
    )

    private fun themed(night: Boolean): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return context.createConfigurationContext(configuration)
    }

    private fun draw(night: Boolean, name: String): Pair<Int, Int> {
        val views = WidgetRenderer.render(
            context = context, metric = WidgetMetric.NET_WORTH, snapshot = snapshot(), redacted = false,
        )
        val host = themed(night)
        val root = views.apply(host, FrameLayout(host))
        val density = host.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (150 * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        save(bitmap, name)
        // A pixel of blank paper, near the bottom right: the label, value and rule are on the left.
        val paper = bitmap.getPixel(width - 24, height - 24)
        return paper to root.findViewById<TextView>(R.id.widget_value).currentTextColor
    }

    private fun save(bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun appearance(dynamic: Boolean, choice: ThemeChoice = ThemeChoice.SYSTEM) {
        val settings = ThemeSettings(stateDir)
        settings.setChoice(choice)
        settings.setDynamicColour(dynamic)
    }

    @Test fun theLauncherPicksLightOrDarkWithoutARedraw() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        appearance(dynamic = false)
        val (dayPaper, dayText) = draw(night = false, name = "widget-own-day")
        val (nightPaper, nightText) = draw(night = true, name = "widget-own-night")
        assertNotEquals("paper is the same in light and dark", dayPaper, nightPaper)
        assertNotEquals("text is the same in light and dark", dayText, nightText)
    }

    @Test fun dynamicColourChangesThePaperAndTheText() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        appearance(dynamic = false)
        val own = draw(night = false, name = "widget-own-day-2")
        appearance(dynamic = true)
        val dynamic = draw(night = false, name = "widget-dynamic-day")
        draw(night = true, name = "widget-dynamic-night")
        assertNotEquals("paper ignored dynamic colour", own.first, dynamic.first)
        assertEquals(
            "paper is not the system's neutral surface",
            context.getColor(android.R.color.system_neutral1_10), dynamic.first,
        )
    }

    @Test fun anExplicitLightOrDarkChoiceIsNotLeftToTheLauncher() {
        appearance(dynamic = false, choice = ThemeChoice.DARK)
        val inDay = draw(night = false, name = "widget-dark-in-day")
        val inNight = draw(night = true, name = "widget-dark-in-night")
        assertEquals("a dark choice changed with the launcher", inDay, inNight)
    }
}
