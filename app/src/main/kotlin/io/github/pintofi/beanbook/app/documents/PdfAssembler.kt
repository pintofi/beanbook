package io.github.pintofi.beanbook.app.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import io.github.pintofi.beanbook.core.domain.CapturedPage
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.CropQuad
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns captured pages into a single PDF.
 *
 * Each page is encoded as a JPEG and carried into the file as it is by
 * [io.github.pintofi.beanbook.core.data.JpegPdf] -- no PDF library, no licence question.
 * A receipt is a picture on a page, and that is the whole of what the file needs to say.
 *
 * The captures themselves are never modified. Rotation is applied while drawing into the
 * PDF, so the original JPEG the camera wrote is still on disk, at its original orientation,
 * if the user wants to start again (the original is preserved unless explicitly
 * discarded).
 */
class PdfAssembler(
    private val maxPageEdge: Int = DEFAULT_MAX_EDGE,
    /**
     * Renders a page in the style it was given.
     *
     * A function rather than a call into `:scanner`, so this class stays testable without
     * OpenCV and a device without it still assembles documents -- unstyled, which is the
     * photograph, which is the honest fallback.
     */
    private val styler: (Bitmap, io.github.pintofi.beanbook.core.domain.PageStyle) -> Bitmap =
        { bitmap, _ -> bitmap },
) {

    /**
     * @param pages what to draw, in order.
     * @param resolve turns a page's [CapturedPage.source] into the file holding it.
     * @return the PDF bytes, ready to be hashed and stored.
     */
    fun assemble(pages: CapturedPages, resolve: (String) -> File): ByteArray {
        require(!pages.isEmpty) { "a document needs at least one page" }

        // JPEG pages written straight into the PDF. `PdfDocument` stored every page as
        // deflated raw pixels at a point per pixel: several megabytes, and a page 70 cm tall.
        val written = pages.pages.mapIndexed { index, page ->
            val bitmap = renderPage(page, resolve)
                ?: error("page ${index + 1} could not be read: ${page.source}")
            try {
                val jpeg = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)) {
                    "page ${index + 1} could not be encoded"
                }
                // On paper when the document has a page size (#184): the orientation is decided on the
                // page as it will be filed, after the crop and the turn. As scanned stays the page's own shape.
                val placement = if (pages.pageSize == io.github.pintofi.beanbook.core.domain.PageSize.AS_SCANNED) {
                    null
                } else {
                    io.github.pintofi.beanbook.core.domain.PageLayout.place(pages.pageSize, bitmap.width, bitmap.height)
                }
                io.github.pintofi.beanbook.core.data.JpegPdf.Page(
                    jpeg.toByteArray(), bitmap.width, bitmap.height, placement,
                )
            } finally {
                bitmap.recycle()
            }
        }
        return io.github.pintofi.beanbook.core.data.JpegPdf.write(written)
    }

    /**
     * One page exactly as it goes into the file -- cropped, turned and rendered in its style --
     * or null when the photograph cannot be read.
     *
     * Public so the page editor can show *this*, not the raw photograph: it used to show the
     * picture as taken, so nobody could see whether the corners had been found or what
     * "Document" would do to a page until the PDF was filed.
     */
    fun renderPage(page: CapturedPage, resolve: (String) -> File, maxEdge: Int = maxPageEdge): Bitmap? {
        val decoded = decode(resolve(page.source), page.rotation, page.crop, maxEdge) ?: return null
        return if (page.style == io.github.pintofi.beanbook.core.domain.PageStyle.COLOUR) {
            decoded
        } else {
            styler(decoded, page.style).also { if (it !== decoded) decoded.recycle() }
        }
    }

    /**
     * Flattens a photographed page onto a rectangle.
     *
     * `setPolyToPoly` with four points is a full perspective transform, which is what a
     * receipt shot at an angle needs: its outline is a general quadrilateral, and a plain
     * rectangular crop can only cut a box out of that, leaving the page still keystoned.
     *
     * The corners are in the pixels of the *upright, full-size* photograph -- the image the
     * detector and the crop editor both look at -- and the bitmap here is sampled down from
     * it, so they are scaled by the same factor before use. Getting that wrong crops a
     * different part of the page than the one the user framed.
     */
    private fun applyCrop(bitmap: Bitmap, quad: CropQuad, scale: Float): Bitmap {
        val (outWidth, outHeight) = quad.outputSize()
        val width = (outWidth * scale).toInt().coerceAtLeast(1)
        val height = (outHeight * scale).toInt().coerceAtLeast(1)

        val source = quad.asFloatArray().map { it * scale }.toFloatArray()
        val destination = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(source, 0, destination, 0, 4)) return bitmap

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // White behind the page: a corner dragged past the edge of the photograph leaves a
        // gap, and a JPEG has no transparency to leave it in.
        output.eraseColor(android.graphics.Color.WHITE)
        Canvas(output).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }

    /**
     * Reads a capture: upright, cropped, turned, and no larger than [maxPageEdge].
     *
     * **In that order, and at enough resolution.** Two faults lived here. The crop was
     * applied to the photograph *before* the camera's EXIF rotation, while the detector and
     * the crop editor both place corners on the upright image -- so on a phone that stores its
     * pictures sideways, which is most of them, the page that was cut out was a different
     * region of the photograph. And the decode sampled by the largest power of two that fitted
     * [maxPageEdge], which for a 12 MP camera (4032 px) is a quarter: a 1008-pixel page, about
     * 90 dpi across A4, before cropping made it smaller still. Now the decode keeps at least
     * [maxPageEdge] pixels, the page is cut from that, and it is scaled down smoothly last.
     *
     * @param rotation what the user asked for, in degrees, applied after the crop -- the
     *   corners were placed on the page before it was turned.
     */
    private fun decode(file: File, rotation: Int, crop: CropQuad? = null, maxEdge: Int = maxPageEdge): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return null

        var sample = 1
        while (longestEdge / (sample * 2) >= maxEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val upright = Photos.upright(decoded, Photos.degreesFor(file))
        // The decoder may sample by less than asked, so the scale is measured, not assumed.
        val scale = maxOf(upright.width, upright.height).toFloat() / longestEdge

        val cropped = if (crop == null) {
            upright
        } else {
            applyCrop(upright, crop, scale).also { if (it !== upright) upright.recycle() }
        }
        val turned = Photos.upright(cropped, rotation % 360)
        return fit(turned, maxEdge)
    }

    /** [bitmap] scaled down to [maxEdge] on its longest side, filtered; the same one if it fits. */
    private fun fit(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val factor = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt().coerceAtLeast(1),
            (bitmap.height * factor).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== bitmap) bitmap.recycle() }
    }

    private companion object {
        /**
         * Long edge in pixels: an A4 page at 200 dpi, which is what `JpegPdf` sizes pages by.
         * Comfortably legible for small print, and a small fraction of the raw capture.
         */
        const val DEFAULT_MAX_EDGE = 2400

        /** High enough that small print survives, low enough that a page stays a few hundred kB. */
        const val JPEG_QUALITY = 88
    }
}

/** Draws [bitmap] into a one-page PDF. Used by tests and by the single-image path. */
internal fun Canvas.drawFullPage(bitmap: Bitmap) {
    drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
}
