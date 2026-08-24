package dev.beancount.app.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import dev.beancount.core.domain.CapturedPage
import dev.beancount.core.domain.CapturedPages
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns captured pages into a single PDF.
 *
 * Android's own `PdfDocument` does the writing — no PDF library, no licence question, and
 * nothing to keep up to date. It is enough for what a receipt is: a picture on a page.
 *
 * The captures themselves are never modified. Rotation is applied while drawing into the
 * PDF, so the original JPEG the camera wrote is still on disk, at its original orientation,
 * if the user wants to start again (SPEC.md, J3: the original is preserved unless explicitly
 * discarded).
 */
class PdfAssembler(private val maxPageEdge: Int = DEFAULT_MAX_EDGE) {

    /**
     * @param pages what to draw, in order.
     * @param resolve turns a page's [CapturedPage.source] into the file holding it.
     * @return the PDF bytes, ready to be hashed and stored.
     */
    fun assemble(pages: CapturedPages, resolve: (String) -> File): ByteArray {
        require(!pages.isEmpty) { "a document needs at least one page" }

        val document = PdfDocument()
        try {
            pages.pages.forEachIndexed { index, page ->
                val bitmap = decode(resolve(page.source), page.rotation)
                    ?: error("page ${index + 1} could not be read: ${page.source}")
                try {
                    val info = PdfDocument.PageInfo
                        .Builder(bitmap.width, bitmap.height, index + 1)
                        .create()
                    val pdfPage = document.startPage(info)
                    pdfPage.canvas.drawBitmap(
                        bitmap,
                        null,
                        Rect(0, 0, bitmap.width, bitmap.height),
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    document.finishPage(pdfPage)
                } finally {
                    bitmap.recycle()
                }
            }

            val out = ByteArrayOutputStream()
            document.writeTo(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    /**
     * Reads a capture, downscaled and rotated.
     *
     * Downscaling matters: a modern phone camera produces an image far larger than a
     * receipt needs, and a 12-megapixel page per receipt would make a project unbackupable.
     * The scale is a power of two so the decoder does the work rather than us.
     */
    private fun decode(file: File, rotation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longestEdge <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = generateSequence(1) { it * 2 }
                .first { longestEdge / it <= maxPageEdge }
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        if (rotation == 0) return decoded

        val rotated = Bitmap.createBitmap(
            decoded,
            0,
            0,
            decoded.width,
            decoded.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        )
        // createBitmap can hand back the very same instance when there is nothing to do, so
        // the source is only recycled when a genuinely different bitmap replaced it —
        // otherwise this would recycle the bitmap it is about to return.
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private companion object {
        /**
         * Long edge in pixels. About 200 dpi across an A4 page — comfortably legible for a
         * receipt, and roughly a hundredth of the bytes of the raw capture.
         */
        const val DEFAULT_MAX_EDGE = 2000
    }
}

/** Draws [bitmap] into a one-page PDF. Used by tests and by the single-image path. */
internal fun Canvas.drawFullPage(bitmap: Bitmap) {
    drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
}
