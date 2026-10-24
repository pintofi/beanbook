package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageEditsTest {

    private val quad = CropQuad.whole(100f, 100f)
    private val three = CapturedPages().add("a.jpg").add("b.jpg").add("c.jpg")

    @Test fun `an edit that names a page that is not there changes nothing`() {
        assertEquals(three, three.apply(PageEdit.Rotate(7, true)))
        assertEquals(three, three.apply(PageEdit.Remove(-1)))
        assertEquals(three, three.apply(PageEdit.Crop(9, quad, 90)))
    }

    @Test fun `apply to all styles every page and keeps the paper`() {
        val sized = three.resized(PageSize.A4)
        val styled = sized.apply(PageEdit.RestyleAll(PageStyle.GREYSCALE))
        assertTrue(styled.pages.all { it.style == PageStyle.GREYSCALE })
        assertEquals(PageSize.A4, styled.pageSize)
    }

    @Test fun `every operation carries the paper size through`() {
        val sized = three.resized(PageSize.LETTER)
        listOf(
            PageEdit.Rotate(0, true), PageEdit.Restyle(1, PageStyle.DOCUMENT), PageEdit.Move(0, 2),
            PageEdit.Remove(2), PageEdit.Crop(0, quad, 0),
        ).forEach { assertEquals(PageSize.LETTER, sized.apply(it).pageSize, "$it") }
        assertEquals(PageSize.LETTER, sized.add("d.jpg").pageSize)
    }

    @Test fun `a crop sets the corners and the turn together`() {
        val cropped = three.apply(PageEdit.Crop(1, quad, 270))
        assertEquals(quad, cropped.pages[1].crop)
        assertEquals(270, cropped.pages[1].rotation)
        assertEquals(0, cropped.pages[0].rotation)
    }

    @Test fun `a retake replaces the photo, restarts the turn and keeps the style`() {
        val before = three.apply(PageEdit.Rotate(1, true)).apply(PageEdit.Restyle(1, PageStyle.DOCUMENT))
        val after = before.apply(PageEdit.Retake(1, "b.jpg", "b2.jpg", quad))
        assertEquals(CapturedPage("b2.jpg", rotation = 0, crop = quad, style = PageStyle.DOCUMENT), after.pages[1])
        assertEquals(3, after.size)
    }

    @Test fun `a retake of a page that moved is added instead, so the photo is never lost`() {
        val moved = three.apply(PageEdit.Move(1, 2))
        val after = moved.apply(PageEdit.Retake(1, "b.jpg", "b2.jpg", null))
        assertEquals(4, after.size)
        assertEquals("b2.jpg", after.pages.last().source)
        val gone = three.apply(PageEdit.Remove(1)).apply(PageEdit.Retake(5, "b.jpg", "b2.jpg", null))
        assertEquals("b2.jpg", gone.pages.last().source)
    }

    @Test fun `the page shown follows a move and a removal`() {
        assertEquals(2, PageEdit.Move(0, 2).shownAfter(shown = 0, sizeAfter = 3))
        assertEquals(1, PageEdit.Remove(0).shownAfter(shown = 2, sizeAfter = 2))
        assertEquals(1, PageEdit.Remove(2).shownAfter(shown = 2, sizeAfter = 2))
        assertEquals(0, PageEdit.Remove(0).shownAfter(shown = 0, sizeAfter = 0))
        assertEquals(1, PageEdit.Rotate(1, true).shownAfter(shown = 1, sizeAfter = 3))
    }

    @Test fun `only the edits that lose something offer Undo`() {
        assertTrue(PageEdit.Remove(0).offersUndo)
        assertTrue(PageEdit.RestyleAll(PageStyle.COLOUR).offersUndo)
        assertTrue(PageEdit.Retake(0, "a", "b", null).offersUndo)
        assertFalse(PageEdit.Rotate(0, true).offersUndo)
        assertFalse(PageEdit.Move(0, 1).offersUndo)
    }

    @Test fun `an undo restores the pages only while nothing else has changed`() {
        val edit = PageEdit.Remove(1)
        val after = three.apply(edit)
        val offer = UndoOffer(edit, before = three, after = after, shownBefore = 1)
        assertEquals(three, offer.undo(after))
        assertNull(offer.undo(after.add("d.jpg")))
        assertNull(offer.undo(after.apply(PageEdit.Rotate(0, true))))
    }
}
