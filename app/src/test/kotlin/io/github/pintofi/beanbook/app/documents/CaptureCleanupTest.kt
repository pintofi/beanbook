package io.github.pintofi.beanbook.app.documents

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCleanupTest {

    private fun folder(): File = Files.createTempDirectory("captures").toFile()

    @Test
    fun `only the files a session used are deleted`() {
        val dir = folder()
        val used = File(dir, "page-1.jpg").apply { writeText("a") }
        val other = File(dir, "page-2.jpg").apply { writeText("b") }

        assertEquals(1, deleteCaptureFiles(dir, listOf("page-1.jpg")))

        assertFalse(used.exists())
        assertTrue("a capture from another session must stay", other.exists())
    }

    @Test
    fun `a name that points outside the folder is ignored`() {
        val dir = folder()
        val outside = File(dir.parentFile, "keep-${System.nanoTime()}.txt").apply { writeText("x") }

        assertEquals(0, deleteCaptureFiles(dir, listOf("../${outside.name}")))

        assertTrue(outside.exists())
        outside.delete()
    }

    @Test
    fun `a file that is already gone is not an error`() {
        assertEquals(0, deleteCaptureFiles(folder(), listOf("page-9.jpg")))
    }
}
