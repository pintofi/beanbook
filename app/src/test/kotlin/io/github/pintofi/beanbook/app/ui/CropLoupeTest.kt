package io.github.pintofi.beanbook.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropLoupeTest {

    private val canvas = Size(1000f, 1600f)

    @Test fun `above the finger when there is room`() {
        val centre = loupeCentre(Offset(500f, 800f), canvas, diameter = 200f, gap = 100f)
        assertEquals(Offset(500f, 600f), centre)
    }

    @Test fun `below the finger at the top edge`() {
        val centre = loupeCentre(Offset(500f, 100f), canvas, diameter = 200f, gap = 100f)
        assertEquals(Offset(500f, 300f), centre)
    }

    @Test fun `clamped inside the canvas at both sides`() {
        assertEquals(100f, loupeCentre(Offset(5f, 800f), canvas, 200f, 100f).x, 0f)
        assertEquals(900f, loupeCentre(Offset(995f, 800f), canvas, 200f, 100f).x, 0f)
    }

    @Test fun `never outside the canvas near the bottom`() {
        val centre = loupeCentre(Offset(500f, 10f), Size(1000f, 300f), 200f, 100f)
        assertTrue(centre.y <= 300f - 100f)
    }

    @Test fun `a canvas smaller than the loupe still gives a point`() {
        val centre = loupeCentre(Offset(20f, 20f), Size(80f, 80f), 200f, 40f)
        assertEquals(100f, centre.x, 0f)
    }
}
