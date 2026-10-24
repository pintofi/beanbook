package io.github.pintofi.beanbook.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Where the loupe's centre goes: above the finger by [gap] plus its radius, clear of the finger;
 * below it when there is no room above; clamped inside the canvas either way.
 */
internal fun loupeCentre(finger: Offset, canvas: Size, diameter: Float, gap: Float): Offset {
    val r = diameter / 2f
    val above = finger.y - gap - r
    val y = if (above - r >= 0f) above else (finger.y + gap + r).coerceAtMost(canvas.height - r)
    val x = finger.x.coerceIn(r, (canvas.width - r).coerceAtLeast(r))
    return Offset(x, y)
}
