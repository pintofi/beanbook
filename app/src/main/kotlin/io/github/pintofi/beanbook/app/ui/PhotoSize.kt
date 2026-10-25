package io.github.pintofi.beanbook.app.ui

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionStrategy
import kotlin.math.abs

/**
 * A photograph size the phone's back camera offers, as the system lists it. Only the sizes that are
 * 4:3, the sensor's own shape, which is what the scanner shoots in: a different shape would crop the
 * picture to something other than the viewfinder showed.
 *
 * The camera's default (its largest ordinary picture) is what the scanner has always used; a smaller
 * size makes a smaller file, a faster save and quicker page detection, and a phone with a
 * high-resolution mode lists those sizes too.
 */
internal data class PhotoSize(val width: Int, val height: Int, val highResolution: Boolean = false) {

    /** Saved and compared by this. */
    val key: String get() = "${width}x$height"

    val megapixels: Double get() = width.toLong() * height / ONE_MILLION

    val label: String get() = "%.1f MP (%d × %d)".format(java.util.Locale.ROOT, megapixels, width, height)

    /** The resolution to ask CameraX for: this one, or the nearest below it, then above. */
    fun strategy(): ResolutionStrategy =
        ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)

    companion object {
        private const val ONE_MILLION = 1_000_000.0
        private const val MIN_MEGAPIXELS = 0.3
        private const val SHAPE_SLACK = 8

        /**
         * The sizes of the back camera, largest first, from the system's own list. Empty if it cannot be
         * read, in which case the scanner uses the camera's default and offers no choice.
         */
        fun supported(context: Context): List<PhotoSize> = runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: return@runCatching emptyList()
            val map = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching emptyList()
            val ordinary = map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { PhotoSize(it.width, it.height) }
            val high = map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty()
                .map { PhotoSize(it.width, it.height, highResolution = true) }
            (ordinary + high)
                .filter { abs(it.width * 3 - it.height * 4) <= SHAPE_SLACK && it.megapixels >= MIN_MEGAPIXELS }
                .distinctBy { it.key }
                .sortedByDescending { it.width.toLong() * it.height }
        }.getOrDefault(emptyList())

        /** The size the camera gives when none is chosen: the largest ordinary one. */
        fun defaultOf(sizes: List<PhotoSize>): PhotoSize? =
            sizes.firstOrNull { !it.highResolution } ?: sizes.firstOrNull()
    }
}
