package io.github.pintofi.beanbook.app.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.File

/**
 * Reading a photograph the way it was taken.
 *
 * A camera writes the sensor's own orientation into EXIF and leaves the pixels alone, so a
 * page photographed in portrait is stored sideways with a tag saying so. `BitmapFactory`
 * ignores that tag. Every decode in the app did too, which is why a scanned receipt appeared
 * rotated in the strip, in the crop editor, in the detector and in the finished PDF — four
 * places, one cause.
 *
 * `android.media.ExifInterface` rather than the AndroidX one: this reads a file the app just
 * wrote with CameraX, the platform class handles it, and adding a dependency for it would
 * need a licence check for no gain.
 */
object Photos {

    private const val TAG = "Photos"

    /**
     * [file] decoded and turned upright, sampled down to [maxEdge] pixels on its longest side
     * when one is given.
     *
     * Null when the file is not an image the platform can read. Callers show what they can
     * rather than crashing: a page that will not decode is a page the user can retake.
     */
    fun decodeUpright(file: File, maxEdge: Int = 0): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = if (maxEdge <= 0) 1 else generateSequence(1) { it * 2 }
                .first { longest / it <= maxEdge }
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        return upright(decoded, degreesFor(file))
    }

    /** The rotation EXIF says this file needs, in degrees clockwise. */
    fun degreesFor(file: File): Int = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.onFailure { Log.w(TAG, "could not read the orientation of ${file.name}", it) }.getOrDefault(0)

    /** [bitmap] rotated by [degrees]; the same instance when there is nothing to do. */
    fun upright(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.onFailure { Log.w(TAG, "could not rotate a page", it) }.getOrDefault(bitmap)
    }
}
