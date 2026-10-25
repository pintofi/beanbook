package io.github.pintofi.beanbook.app.ui

import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * How large the scanner's photographs are. The camera's largest picture is the default: the most
 * detail for small print. A smaller one makes a smaller file, a faster save and quicker page
 * detection, and is enough for a printed receipt held close.
 */
internal enum class PhotoSize(val label: String, val detail: String, private val longEdge: Int?) {
    LARGE("Large", "The camera's largest picture. Most detail, biggest files.", null),
    MEDIUM("Medium", "About 8 megapixels. Plenty for a printed page.", MEDIUM_EDGE),
    SMALL("Small", "About 3 megapixels. Smallest files, fastest to save.", SMALL_EDGE),
    ;

    /** The resolution to ask CameraX for, 4:3, or null for the largest it has. */
    fun strategy(): ResolutionStrategy? = longEdge?.let {
        ResolutionStrategy(Size(it, it * 3 / 4), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
    }

}

private const val MEDIUM_EDGE = 3264
private const val SMALL_EDGE = 2048
