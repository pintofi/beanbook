package io.github.pintofi.beanbook.scanner

import android.graphics.Bitmap
import android.util.Log
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.core.domain.PageStyle
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * The one entry point, and the one place OpenCV's native library is loaded.
 *
 * `initLocal` loads the `.so` bundled in the APK. It is not the old `initAsync` that pulled
 * an OpenCV Manager app off the Play Store — nothing here downloads anything, which is what
 * keeps this side of the Play edition's no-downloaded-code rule uninteresting.
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

    /**
     * The page in [photo], or null when none is found or OpenCV is unavailable. [thorough] false is
     * for the live preview: a handful of passes instead of all of them, so it runs several times a second.
     */
    fun detect(photo: Bitmap, thorough: Boolean = true): CropQuad? {
        if (!available) return null
        val mat = photo.toMat()
        return runCatching { PageDetector.detect(mat, thorough) }
            .onFailure { Log.w(TAG, "detection failed", it) }
            .getOrNull()
            .also { mat.release() }
    }

    /**
     * [photo] straightened onto [quad] and rendered in [style].
     *
     * Returns the photo unchanged when OpenCV is unavailable, so a caller never has to decide
     * what to show instead — an unstraightened receipt is still a receipt.
     */
    fun straighten(photo: Bitmap, quad: CropQuad, style: ScanStyle): Bitmap {
        if (!available) return photo
        val input = photo.toMat()
        return runCatching {
            val out = PageWarp.straighten(input, quad, style)
            try {
                out.toBitmap()
            } finally {
                // A page too small to warp comes back as the input itself.
                if (out !== input) out.release()
            }
        }.onFailure { Log.w(TAG, "warp failed", it) }.getOrDefault(photo).also { input.release() }
    }

    /**
     * The style to give a page nobody has chosen one for: a colour scan when the straightened page has
     * real colour (a stamp, a highlighter), grey when it does not (a thermal receipt). The photograph
     * is straightened on [quad] first, so the table around it does not count. Colour when OpenCV is
     * unavailable, since the styler would return the photograph anyway.
     */
    fun chooseStyle(photo: Bitmap, quad: CropQuad?): ScanStyle {
        if (!available) return ScanStyle.COLOUR
        val input = photo.toMat()
        var page: Mat? = null
        return runCatching {
            page = if (quad != null) PageWarp.straighten(input, quad, ScanStyle.COLOUR) else input
            when (PageStyles.chooseFor(requireNotNull(page))) {
                PageStyle.DOCUMENT -> ScanStyle.DOCUMENT
                else -> ScanStyle.GREYSCALE
            }
        }.onFailure { Log.w(TAG, "style choice failed", it) }.getOrDefault(ScanStyle.COLOUR).also {
            page?.takeIf { it !== input }?.release()
            input.release()
        }
    }

    /** [photo] re-rendered in [style] without changing its shape. */
    fun restyle(photo: Bitmap, style: ScanStyle): Bitmap {
        if (!available || style == ScanStyle.COLOUR) return photo
        val input = photo.toMat()
        return runCatching {
            val out = PageWarp.applyStyle(input, style)
            try {
                out.toBitmap()
            } finally {
                if (out !== input) out.release()
            }
        }.onFailure { Log.w(TAG, "restyle failed", it) }.getOrDefault(photo).also { input.release() }
    }

    private fun Bitmap.toMat(): Mat = Mat().also { Utils.bitmapToMat(this, it) }

    private fun Mat.toBitmap(): Bitmap =
        Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
            .also { Utils.matToBitmap(this, it) }
}
