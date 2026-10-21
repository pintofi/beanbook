package io.github.pintofi.beanbook.app.ui.theme

import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.Shape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * A squircle: the superellipse |x|^n + |y|^n = 1 with n = 4.5, which is rounder than a square with
 * rounded corners and flatter than a circle, and whose curve meets its sides without a seam.
 *
 * Material 3's checkbox is a square with a 2dp corner, which reads as sharp; this is what the
 * app's checkboxes use instead.
 */
val SquircleShape: Shape = GenericShape { size, _ ->
    val n = 4.5
    val halfWidth = size.width / 2f
    val halfHeight = size.height / 2f
    val steps = 80
    for (step in 0..steps) {
        val angle = 2.0 * PI * step / steps
        val c = cos(angle)
        val s = sin(angle)
        val x = halfWidth + halfWidth * sign(c).toFloat() * abs(c).pow(2.0 / n).toFloat()
        val y = halfHeight + halfHeight * sign(s).toFloat() * abs(s).pow(2.0 / n).toFloat()
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
