package io.github.pintofi.beanbook.scanner

import android.graphics.Bitmap
import android.util.Log
import io.github.pintofi.beanbook.core.domain.CropQuad
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * The one entry point, and the one place OpenCV's native library is loaded.
 *
 * `initLocal` loads the `.so` bundled in the APK. It is not the old `initAsync` that pulled
 * an OpenCV Manager app off the Play Store — nothing here downloads anything, which is what
 * keeps this side of D1/D3 uninteresting.
 *
 * Loading is attempted once and its result remembered. A device where it fails must still
 * open the app: every entry point below returns the un-scanned image instead of throwing, so
 * a failed load costs the user the straightening and nothing else. It is logged, not
 * swallowed silently, because "the scanner did nothing" is otherwise unexplainable.
 */
object Scanner {

    private const val TAG = "Scanner"

    val available: Boolean by lazy {
        runCatching { OpenCVLoader.initLocal() }
            .onFailure { Log.w(TAG, "OpenCV native load failed; scanning falls back to the photo", it) }
            .getOrDefault(false)
            .also { if (!it) Log.w(TAG, "OpenCV reported an unsuccessful load") }
    }

    /** The page in [photo], or null when none is found or OpenCV is unavailable. */
    fun detect(photo: Bitmap): CropQuad? {
        if (!available) return null
        val mat = photo.toMat()
        return runCatching { PageDetector.detect(mat) }
            .onFailure { Log.w(TAG, "detection failed", it) }
            .getOrNull()
    }

    /**
     * [photo] straightened onto [quad] and rendered in [style].
     *
     * Returns the photo unchanged when OpenCV is unavailable, so a caller never has to decide
     * what to show instead — an unstraightened receipt is still a receipt.
     */
    fun straighten(photo: Bitmap, quad: CropQuad, style: ScanStyle): Bitmap {
        if (!available) return photo
        return runCatching {
            val out = PageWarp.straighten(photo.toMat(), quad, style)
            out.toBitmap()
        }.onFailure { Log.w(TAG, "warp failed", it) }.getOrDefault(photo)
    }

    /** [photo] re-rendered in [style] without changing its shape. */
    fun restyle(photo: Bitmap, style: ScanStyle): Bitmap {
        if (!available || style == ScanStyle.COLOUR) return photo
        return runCatching { PageWarp.applyStyle(photo.toMat(), style).toBitmap() }
            .onFailure { Log.w(TAG, "restyle failed", it) }
            .getOrDefault(photo)
    }

    private fun Bitmap.toMat(): Mat = Mat().also { Utils.bitmapToMat(this, it) }

    private fun Mat.toBitmap(): Bitmap =
        Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
            .also { Utils.matToBitmap(this, it) }
}
