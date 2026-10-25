package io.github.pintofi.beanbook.app.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail

/**
 * Compares a rendered screen with the picture kept for it in `src/androidTest/assets/screenshots`.
 *
 * This is the other half of [PixelGate]. The gate states rules about what a correct render looks
 * like and fails only when something is wrong. This notices that a screen *changed*, so a new
 * spacing, a colour or a font change shows up in a review as a picture, not as a surprise.
 * It is kept out of the required checks for the reason the gate gives: it fails on every
 * intentional edit until the pictures are replaced.
 *
 * - A screen is rendered at a fixed width, density and theme, so the only inputs are the
 *   code and the emulator's fonts and renderer. pictures are made on the weekly job's emulator
 *   (see `weekly.yml`), because another emulator draws text a little differently.
 * - A pixel counts as different only when a colour channel is off by more than [CHANNEL_TOLERANCE],
 *   and a picture only fails when more than [MAX_DIFFERENT_SHARE] of its pixels differ, which lets
 *   antialiasing through and nothing else.
 * - To replace the pictures after a change you meant to make, run with the instrumentation
 *   argument `updateScreenshots=true`; the new ones are written to `Download/beanbook-screenshots`
 *   on the device, to be pulled and committed. A failing picture leaves what it drew and a diff
 *   in `Download/beanbook-screenshots/failed`.
 */
object Screenshots {
    private const val CHANNEL_TOLERANCE = 8
    private const val MAX_DIFFERENT_SHARE = 0.002
    private const val FOLDER = "Download/beanbook-screenshots"

    private val updating: Boolean
        get() = InstrumentationRegistry.getArguments().getString("updateScreenshots") == "true"

    fun check(name: String, image: ImageBitmap) {
        val actual = image.asAndroidBitmap()
        if (updating) {
            save("$name.png", actual, FOLDER)
            return
        }
        val golden = golden(name)
        if (golden == null) {
            save("$name.png", actual, "$FOLDER/failed")
            fail("no picture kept for $name; run with updateScreenshots=true and commit the result")
            return
        }
        if (golden.width != actual.width || golden.height != actual.height) {
            save("$name.png", actual, "$FOLDER/failed")
            fail("$name is ${actual.width}x${actual.height}, the kept picture is ${golden.width}x${golden.height}")
        }
        val differing = differing(golden, actual)
        val share = differing.count { it }.toDouble() / differing.size
        if (share > MAX_DIFFERENT_SHARE) {
            save("$name.png", actual, "$FOLDER/failed")
            save("$name-diff.png", diffPicture(actual, differing), "$FOLDER/failed")
            fail("$name differs from its kept picture in ${"%.2f".format(share * 100)}% of its pixels")
        }
    }

    private fun golden(name: String): Bitmap? = runCatching {
        InstrumentationRegistry.getInstrumentation().context.assets.open("screenshots/$name.png")
            .use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun differing(a: Bitmap, b: Bitmap): BooleanArray {
        val width = a.width
        val height = a.height
        val left = IntArray(width * height)
        val right = IntArray(width * height)
        a.getPixels(left, 0, width, 0, 0, width, height)
        b.getPixels(right, 0, width, 0, 0, width, height)
        return BooleanArray(left.size) { i ->
            val x = left[i]
            val y = right[i]
            kotlin.math.abs((x shr 16 and 0xff) - (y shr 16 and 0xff)) > CHANNEL_TOLERANCE ||
                kotlin.math.abs((x shr 8 and 0xff) - (y shr 8 and 0xff)) > CHANNEL_TOLERANCE ||
                kotlin.math.abs((x and 0xff) - (y and 0xff)) > CHANNEL_TOLERANCE
        }
    }

    /** What was drawn, faded, with the pixels that differ in red. */
    private fun diffPicture(actual: Bitmap, differing: BooleanArray): Bitmap {
        val width = actual.width
        val pixels = IntArray(differing.size)
        actual.getPixels(pixels, 0, width, 0, 0, width, actual.height)
        for (i in pixels.indices) {
            pixels[i] = if (differing[i]) {
                0xffff0000.toInt()
            } else {
                val p = pixels[i]
                val faded = { c: Int -> 255 - (255 - c) / 4 }
                (0xff shl 24) or (faded(p shr 16 and 0xff) shl 16) or
                    (faded(p shr 8 and 0xff) shl 8) or faded(p and 0xff)
            }
        }
        return Bitmap.createBitmap(pixels, width, actual.height, Bitmap.Config.ARGB_8888)
    }

    private fun save(fileName: String, bitmap: Bitmap, folder: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
