package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapturedPagesTest {

    private val three = CapturedPages()
        .add("page-1.jpg")
        .add("page-2.jpg")
        .add("page-3.jpg")

    @Test
    fun `pages keep the order they were captured in`() {
        assertEquals(listOf("page-1.jpg", "page-2.jpg", "page-3.jpg"), three.pages.map { it.source })
    }

    @Test
    fun `rotating a page changes that page and nothing else`() {
        val rotated = three.rotate(1)

        assertEquals(listOf(0, 90, 0), rotated.pages.map { it.rotation })
        assertEquals(three.pages.map { it.source }, rotated.pages.map { it.source })
    }

    @Test
    fun `four clockwise rotations come back to where they started`() {
        var pages = three
        repeat(4) { pages = pages.rotate(0) }

        assertEquals(0, pages.pages.first().rotation)
    }

    @Test
    fun `rotating anticlockwise from zero gives 270, not minus 90`() {
        assertEquals(270, three.rotate(0, clockwise = false).pages.first().rotation)
    }

    @Test
    fun `a page cannot be created at an angle a PDF page cannot be`() {
        assertFailsWith<IllegalArgumentException> { CapturedPage("p.jpg", rotation = 45) }
        assertFailsWith<IllegalArgumentException> { CapturedPage("p.jpg", rotation = -90) }
    }

    @Test
    fun `moving a page slides the others along`() {
        val moved = three.move(from = 0, to = 2)

        assertEquals(listOf("page-2.jpg", "page-3.jpg", "page-1.jpg"), moved.pages.map { it.source })
    }

    @Test
    fun `moving backwards works the same way`() {
        val moved = three.move(from = 2, to = 0)

        assertEquals(listOf("page-3.jpg", "page-1.jpg", "page-2.jpg"), moved.pages.map { it.source })
    }

    @Test
    fun `a move that overshoots the end means put it last, not throw`() {
        val moved = three.move(from = 0, to = 99)

        assertEquals(listOf("page-2.jpg", "page-3.jpg", "page-1.jpg"), moved.pages.map { it.source })
    }

    @Test
    fun `a move from nowhere changes nothing`() {
        assertEquals(three, three.move(from = 7, to = 0))
        assertEquals(three, three.move(from = -1, to = 0))
    }

    @Test
    fun `a move to where the page already is changes nothing`() {
        assertEquals(three, three.move(from = 1, to = 1))
    }

    @Test
    fun `removing a page keeps the rest in order`() {
        val remaining = three.remove(1)

        assertEquals(listOf("page-1.jpg", "page-3.jpg"), remaining.pages.map { it.source })
    }

    @Test
    fun `rotation survives reordering, because it belongs to the page`() {
        val reordered = three.rotate(0).move(from = 0, to = 2)

        assertEquals("page-1.jpg", reordered.pages.last().source)
        assertEquals(90, reordered.pages.last().rotation)
    }

    @Test
    fun `editing never touches the file the camera wrote`() {
        // The source is the identity of the original capture; every edit is a description of
        // what to do with it, so the original is still there to fall back on.
        val edited = three.rotate(0).rotate(0).move(0, 2).remove(0)

        assertTrue(edited.pages.all { it.source in three.pages.map { page -> page.source } })
    }

    @Test
    fun `an empty document knows it is empty`() {
        assertTrue(CapturedPages().isEmpty)
        assertEquals(0, CapturedPages().size)
        assertTrue(!three.isEmpty)
    }
}
