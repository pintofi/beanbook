package io.github.pintofi.beanbook.app.ui

import android.graphics.BitmapFactory
import io.github.pintofi.beanbook.core.domain.CropPoint
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.scanner.Scanner
import java.io.File

/**
 * The page in a photograph on disk, found on the upright image -- the one the crop editor and
 * the assembler both place corners on. In the full photograph's pixels; null when no page is found.
 */
internal fun detectPageIn(file: File): CropQuad? =
    io.github.pintofi.beanbook.app.documents.Photos.decodeUpright(file, maxEdge = DETECT_EDGE)
        ?.let { sampled ->
            val full = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeFile(file.path, it) }
            val longest = maxOf(full.outWidth, full.outHeight).toFloat()
            val factor = longest / maxOf(sampled.width, sampled.height)
            Scanner.detect(sampled)?.let { quad ->
                fun CropPoint.up() = CropPoint(x * factor, y * factor)
                CropQuad(quad.topLeft.up(), quad.topRight.up(), quad.bottomRight.up(), quad.bottomLeft.up())
            }.also { sampled.recycle() }
        }

/**
 * The size the detector reads a saved photograph at. It resizes to 500 px itself, so decoding
 * a 12 MP photograph at full size first -- which it used to -- was 48 MB spent on nothing.
 */
private const val DETECT_EDGE = 1600
