package io.github.pintofi.beanbook.scanner

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * Photographs of a page that are drawn, so the corners are known and a test can say where the page
 * is, not only that something was found. Every scene is a pure function of its arguments and a seed.
 */
internal object SyntheticScenes {

    const val WIDTH = 900
    const val HEIGHT = 1200

    class Scene(val bitmap: Bitmap, val truth: CropQuad?)

    fun quad(vararg p: Float) = CropQuad(
        CropPoint(p[0], p[1]), CropPoint(p[2], p[3]), CropPoint(p[4], p[5]), CropPoint(p[6], p[7]),
    )

    /** The page's four corners as an Android path. */
    private fun path(q: CropQuad) = Path().apply {
        moveTo(q.topLeft.x, q.topLeft.y)
        lineTo(q.topRight.x, q.topRight.y)
        lineTo(q.bottomRight.x, q.bottomRight.y)
        lineTo(q.bottomLeft.x, q.bottomLeft.y)
        close()
    }

    /**
     * A table of [table] with [page] on it at [corners], or just the table when [corners] is null.
     * [edgeSoftness] blurs the page's edge; [noise] is the standard deviation of per-pixel grain;
     * [text] draws rows of print that follow the page; [shadow] darkens one side of the frame;
     * [jpegQuality] adds real compression artefacts.
     */
    fun scene(
        table: Int,
        page: Int,
        corners: CropQuad?,
        seed: Int,
        edgeSoftness: Float = 0f,
        noise: Double = 0.0,
        text: Boolean = false,
        shadow: Float = 0f,
        gradient: Pair<Int, Int>? = null,
        stripes: Boolean = false,
        jpegQuality: Int = 0,
        innerBox: Boolean = false,
    ): Scene {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(table)
        if (gradient != null) {
            val paint = Paint().apply {
                shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), gradient.first, gradient.second, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        }
        if (stripes) {
            val band = Paint().apply { color = Color.argb(60, 90, 60, 30) }
            var x = 0f
            while (x < WIDTH) { canvas.drawRect(x, 0f, x + 40f, HEIGHT.toFloat(), band); x += 80f }
        }
        if (corners != null) {
            val paint = Paint().apply {
                color = page
                isAntiAlias = true
                if (edgeSoftness > 0f) maskFilter = BlurMaskFilter(edgeSoftness, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(path(corners), paint)
            if (text) drawText(canvas, corners)
            if (innerBox) drawBox(canvas, corners)
        }
        if (shadow > 0f) {
            val paint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, WIDTH * 0.6f, 0f, Color.argb((shadow * 255).toInt(), 0, 0, 0), Color.TRANSPARENT,
                    Shader.TileMode.CLAMP,
                )
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        }
        if (noise > 0.0) addNoise(bitmap, noise, Random(seed.toLong()))
        val result = if (jpegQuality > 0) viaJpeg(bitmap, jpegQuality) else bitmap
        return Scene(result, corners)
    }

    private fun drawText(canvas: Canvas, q: CropQuad) {
        val paint = Paint().apply { color = Color.rgb(50, 50, 55); isAntiAlias = true; textSize = 26f }
        val width = hypot(q.topRight, q.topLeft)
        val height = hypot(q.bottomLeft, q.topLeft)
        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(
            floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height), 0,
            q.asFloatArray(), 0, 4,
        )
        canvas.save()
        canvas.concat(matrix)
        for (row in 0 until 14) canvas.drawText("ITEM $row      12.50", width * 0.08f, height * 0.08f + row * (height * 0.065f), paint)
        canvas.restore()
    }

    private fun drawBox(canvas: Canvas, q: CropQuad) {
        val cx = (q.topLeft.x + q.bottomRight.x) / 2f
        val cy = (q.topLeft.y + q.bottomRight.y) / 2f
        val w = (q.topRight.x - q.topLeft.x) * 0.3f
        val h = (q.bottomLeft.y - q.topLeft.y) * 0.3f
        val paint = Paint().apply { color = Color.rgb(20, 20, 20); style = Paint.Style.STROKE; strokeWidth = 4f }
        canvas.drawRect(cx - w, cy - h, cx + w, cy + h, paint)
    }

    private fun hypot(a: CropPoint, b: CropPoint) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    private fun addNoise(bitmap: Bitmap, sigma: Double, random: Random) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val n = (random.nextGaussian() * sigma).toInt()
            fun c(v: Int) = (v + n).coerceIn(0, 255)
            pixels[i] = Color.rgb(c(Color.red(p)), c(Color.green(p)), c(Color.blue(p)))
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun viaJpeg(bitmap: Bitmap, quality: Int): Bitmap {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
