package io.github.pintofi.beanbook.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.scanner.ScanStyle
import io.github.pintofi.beanbook.scanner.Scanner
import java.io.File

/** What a photograph on disk shows: the page in it, if one was found, and the style it suits. */
internal class PageInspection(val quad: CropQuad?, val style: ScanStyle)

/**
 * The page in a photograph on disk, found on the upright image -- the one the crop editor and
 * the assembler both place corners on. In the full photograph's pixels; null when no page is found.
 */
internal fun detectPageIn(file: File): CropQuad? = inspectPage(file, chooseStyle = false).quad

/**
 * The page in the photograph and the style the scanner would give it (a colour scan when the page has
 * real colour, grey when it does not), from one decode. [chooseStyle] false skips the style.
 */
internal fun inspectPage(file: File, chooseStyle: Boolean = true): PageInspection {
    val sampled = io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = DETECT_EDGE)
        ?: return PageInspection(null, ScanStyle.COLOUR)
    try {
        val found = Scanner.detect(sampled)
        val style = if (chooseStyle) Scanner.chooseStyle(sampled, found) else ScanStyle.COLOUR
        return PageInspection(found?.let { scaledToFull(it, sampled, file) }, style)
    } finally {
        sampled.recycle()
    }
}

private fun scaledToFull(quad: CropQuad, sampled: Bitmap, file: File): CropQuad {
    val full = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(file.path, it) }
    val longest = maxOf(full.outWidth, full.outHeight).toFloat()
    val factor = longest / maxOf(sampled.width, sampled.height)
    fun CropPoint.up() = CropPoint(x * factor, y * factor)
    return CropQuad(quad.topLeft.up(), quad.topRight.up(), quad.bottomRight.up(), quad.bottomLeft.up())
}

/**
 * The size the detector reads a saved photograph at. It resizes to 500 px itself, so decoding
 * a 12 MP photograph at full size first -- which it used to -- was 48 MB spent on nothing.
 */
private const val DETECT_EDGE = 1600

/**
 * The style the scanner would give the page in [file] inside [quad] (the whole photograph when null),
 * for a crop: the corners are in the full photograph's pixels, so the photograph is read at the size
 * they are scaled for.
 */
internal fun styleFor(file: File, quad: CropQuad?): io.github.pintofi.beanbook.core.domain.PageStyle {
    val sampled = io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = DETECT_EDGE)
        ?: return io.github.pintofi.beanbook.core.domain.PageStyle.COLOUR
    try {
        val full = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(file.path, it) }
        val factor = maxOf(sampled.width, sampled.height).toFloat() / maxOf(full.outWidth, full.outHeight)
        fun CropPoint.down() = CropPoint(x * factor, y * factor)
        val scaled = quad?.let {
            CropQuad(it.topLeft.down(), it.topRight.down(), it.bottomRight.down(), it.bottomLeft.down())
        }
        return when (Scanner.chooseStyle(sampled, scaled)) {
            ScanStyle.DOCUMENT -> io.github.pintofi.beanbook.core.domain.PageStyle.DOCUMENT
            ScanStyle.GREYSCALE -> io.github.pintofi.beanbook.core.domain.PageStyle.GREYSCALE
            else -> io.github.pintofi.beanbook.core.domain.PageStyle.COLOUR
        }
    } finally {
        sampled.recycle()
    }
}
