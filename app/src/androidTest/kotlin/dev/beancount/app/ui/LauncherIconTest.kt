package dev.beancount.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher icon, rendered by the platform and masked the way a home screen masks it.
 *
 * Checking the XML would only prove the XML parses. This asks `PackageManager` for the icon a
 * launcher would get, draws it through `AdaptiveIconDrawable` — which applies the device's own
 * mask — and looks at the pixels. It catches the two failures that matter and that reading the
 * source does not: a mark that lands outside the safe zone and gets cropped, and a foreground
 * that has quietly become invisible against its background.
 *
 * The rendered image is written to the external cache so a person can look at it, which is
 * still the only way to judge whether an icon is any *good*.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun icon(): AdaptiveIconDrawable {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(
            "the launcher icon is a ${drawable.javaClass.simpleName}, not an adaptive icon — " +
                "every launcher from API 26 masks icons, and a non-adaptive one gets a white " +
                "plate drawn behind it",
            drawable is AdaptiveIconDrawable,
        )
        return drawable as AdaptiveIconDrawable
    }

    private fun render(size: Int = 432): Bitmap {
        val drawable = icon()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        // Internal cache, not external: `externalCacheDir` can be null and a failed write
        // there is silent, which is how the first run produced a passing test and no image.
        File(context.cacheDir, "launcher-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    @Test fun theIconHasAllThreeLayersALauncherLooksFor() {
        val drawable = icon()

        assertNotNull("no background layer", drawable.background)
        assertNotNull("no foreground layer", drawable.foreground)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertNotNull(
                "no monochrome layer, so a themed launcher falls back to the full-colour icon",
                drawable.monochrome,
            )
        }
    }

    @Test fun theMarkSurvivesTheDeviceMask() {
        val bitmap = render()
        val centre = bitmap.width / 2

        // The brand teal, fixed in ic_launcher_background.xml.
        val background = Color.parseColor("#006A6A")

        // A launcher mask keeps the middle and cuts the corners, so the centre must be inside
        // the icon whatever shape the device uses.
        assertEquals(
            "the very centre of the icon is not drawn at all",
            255,
            Color.alpha(bitmap.getPixel(centre, centre)),
        )

        // The mark has to be visible against the background: count pixels in the safe zone
        // that are neither transparent nor the background colour.
        var marked = 0
        val safeFrom = (bitmap.width * 0.25).toInt()
        val safeTo = (bitmap.width * 0.75).toInt()
        for (x in safeFrom until safeTo step 2) {
            for (y in safeFrom until safeTo step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) == 255 && pixel != background) marked++
            }
        }
        val sampled = ((safeTo - safeFrom) / 2) * ((safeTo - safeFrom) / 2)
        assertTrue(
            "only $marked of $sampled sampled pixels in the safe zone differ from the " +
                "background — the mark is missing, or the same colour as what is behind it",
            marked > sampled / 20,
        )
    }

    @Test fun nothingOfTheMarkIsCutOffByTheMask() {
        val bitmap = render()
        val background = Color.parseColor("#006A6A")

        // The mask's edge: if the mark reached it, a circular launcher would slice the
        // brackets. Sample the ring just inside the icon's bounds and require it to be
        // background or nothing — never mark.
        val inset = (bitmap.width * 0.06).toInt()
        var markAtEdge = 0
        for (i in inset until bitmap.width - inset) {
            listOf(
                bitmap.getPixel(i, inset),
                bitmap.getPixel(i, bitmap.height - inset - 1),
                bitmap.getPixel(inset, i),
                bitmap.getPixel(bitmap.width - inset - 1, i),
            ).forEach { pixel ->
                if (Color.alpha(pixel) == 255 && pixel != background) markAtEdge++
            }
        }

        assertEquals(
            "$markAtEdge pixels of the mark reach the icon's edge, where a mask will cut them",
            0,
            markAtEdge,
        )
    }
}
