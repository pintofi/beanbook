package io.github.pintofi.beanbook.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The colours a widget is drawn in, for light and dark, with and without dynamic colour. */
class WidgetPaletteTest {

    /** A stand-in for the system's colour resources: each resource reads as its own id, so a pick is visible. */
    private val system: (Int) -> Int = { it }

    @Test fun `light and dark are different palettes`() {
        val light = WidgetPalette.build(dark = false, black = false, dynamic = null)
        val dark = WidgetPalette.build(dark = true, black = false, dynamic = null)
        assertNotEquals(light.paper, dark.paper)
        assertNotEquals(light.value, dark.value)
    }

    @Test fun `amoled is black paper, and only in dark`() {
        assertEquals(0xFF000000.toInt(), WidgetPalette.build(dark = true, black = true, dynamic = null).paper)
        assertNotEquals(0xFF000000.toInt(), WidgetPalette.build(dark = false, black = true, dynamic = null).paper)
    }

    @Test fun `without dynamic colour the app's own teal is the accent`() {
        assertNotEquals(
            WidgetPalette.build(false, false, null).rule,
            WidgetPalette.build(false, false, system).rule,
        )
    }

    @Test fun `dynamic colour takes paper, text, segments and accent from the system, light and dark`() {
        val light = WidgetPalette.build(dark = false, black = false, dynamic = system)
        assertEquals(android.R.color.system_neutral1_10, light.paper)
        assertEquals(android.R.color.system_neutral1_900, light.value)
        assertEquals(android.R.color.system_accent2_100, light.segmentOn)
        assertEquals(android.R.color.system_accent1_600, light.rule)

        val dark = WidgetPalette.build(dark = true, black = false, dynamic = system)
        assertEquals(android.R.color.system_neutral1_900, dark.paper)
        assertEquals(android.R.color.system_neutral1_100, dark.value)
        assertEquals(android.R.color.system_accent2_700, dark.segmentOn)
        assertEquals(android.R.color.system_accent1_200, dark.rule)
    }

    @Test fun `amoled stays black under dynamic colour`() {
        assertEquals(0xFF000000.toInt(), WidgetPalette.build(dark = true, black = true, dynamic = system).paper)
    }
}
