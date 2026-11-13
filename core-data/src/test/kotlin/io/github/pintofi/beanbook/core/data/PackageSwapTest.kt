package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageSwapTest {

    private val base: File = Files.createTempDirectory("swap").toFile()
    private val root = File(base, "ondemand")
    private val swap = PackageSwap(root)

    @AfterTest fun cleanUp() = base.deleteRecursively().let { }

    private fun folder(dir: File, marker: String) {
        dir.mkdirs()
        File(dir, "marker").writeText(marker)
    }

    private fun current() = File(root, "marker").readText()

    @Test
    fun `the staged copy becomes the folder and the old one is kept, on trial until proved`() {
        folder(root, "old")
        File(swap.stageCopy(), "marker").writeText("new")

        swap.commit().getOrThrow()

        assertEquals("new", current())
        assertEquals("old", File(swap.previous, "marker").readText())
        assertTrue(swap.isOnTrial())
        assertFalse(swap.hasPrevious()) // not until it is proved

        swap.proven()
        assertTrue(swap.hasPrevious())
    }

    @Test
    fun `going back restores the earlier packages and drops the new ones`() {
        folder(root, "old")
        File(swap.stageCopy(), "marker").writeText("new")
        swap.commit().getOrThrow()
        swap.proven()

        swap.rollback().getOrThrow()

        assertEquals("old", current())
        assertFalse(swap.previous.exists())
    }

    @Test
    fun `a kill with the update still on trial puts the earlier packages back`() {
        folder(root, "old")
        File(swap.stageCopy(), "marker").writeText("new")
        swap.commit().getOrThrow()

        val did = PackageSwap(root).recover()

        assertEquals("old", current())
        assertTrue(did!!.contains("never proved"))
        assertFalse(swap.isOnTrial())
    }

    @Test
    fun `a kill after the folder was set aside and before the copy moved in is undone`() {
        folder(swap.previous, "old")
        folder(swap.next, "new")
        File(root.parentFile, "ondemand-swap").writeText("1")
        File(root.parentFile, "ondemand-trial").writeText("1")

        PackageSwap(root).recover()

        assertEquals("old", current())
        assertFalse(swap.next.exists())
    }

    @Test
    fun `a proved update survives a restart`() {
        folder(root, "old")
        File(swap.stageCopy(), "marker").writeText("new")
        swap.commit().getOrThrow()
        swap.proven()

        assertNull(PackageSwap(root).recover())

        assertEquals("new", current())
        assertTrue(swap.hasPrevious())
    }

    @Test
    fun `a staged copy nobody switched to is deleted at the next start`() {
        folder(root, "old")
        File(swap.stageCopy(), "marker").writeText("half built")

        PackageSwap(root).recover()

        assertFalse(swap.next.exists())
        assertEquals("old", current())
    }

    @Test
    fun `nothing is rolled back when there is nothing earlier`() {
        folder(root, "old")

        assertTrue(swap.rollback().isFailure)
        assertEquals("old", current())
    }

    @Test
    fun `the earlier packages can be discarded once proved, and not while on trial`() {
        folder(root, "old")
        File(swap.stageCopy(), "marker").writeText("new")
        swap.commit().getOrThrow()

        swap.discardPrevious()
        assertTrue(swap.previous.exists())

        swap.proven()
        swap.discardPrevious()
        assertFalse(swap.previous.exists())
    }
}
