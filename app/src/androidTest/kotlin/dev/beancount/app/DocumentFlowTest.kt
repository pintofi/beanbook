package dev.beancount.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.beancount.app.documents.PdfAssembler
import dev.beancount.core.data.DocumentStore
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.domain.CapturedPages
import java.io.File
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capture to filed PDF, on a real device.
 *
 * The camera itself is not driven here — an emulator's virtual scene makes for a flaky
 * assertion and proves nothing about the part that can lose data. What is exercised is
 * everything downstream of the shutter: real JPEGs, Android's own PDF writer, the naming
 * rules, and the promise that the originals survive.
 */
@RunWith(AndroidJUnit4::class)
class DocumentFlowTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "document-flow-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val workspace = ProjectWorkspace(root)
    private val store = DocumentStore(workspace)
    private val assembler = PdfAssembler()
    private val date = LocalDate.parse("2026-08-24")

    init {
        workspace.createProject("p", title = "Receipts")
    }

    @After fun cleanUp() {
        root.deleteRecursively()
    }

    /**
     * The size of every page in [pdf], read back with Android's own PDF reader.
     *
     * Reading the result rather than measuring the bytes is the difference between "the
     * assembler wrote something" and "the platform can open what it wrote".
     */
    private fun pageSizes(pdf: ByteArray): List<Pair<Int, Int>> {
        val file = File(root, "read-back.pdf").apply { writeBytes(pdf) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                return (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page -> page.width to page.height }
                }
            }
        }
    }

    /** A capture of the kind the camera writes: a real JPEG on disk. */
    private fun capture(name: String, color: Int, width: Int = 600, height: Int = 900): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val file = File(root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return file
    }

    @Test fun capturedPagesBecomeOnePdfFiledUnderTheAccount() {
        val first = capture("page-1.jpg", Color.WHITE)
        val second = capture("page-2.jpg", Color.LTGRAY)
        val pages = CapturedPages().add(first.name).add(second.name)

        val pdf = assembler.assemble(pages) { File(root, it) }
        val stored = store.store("p", "Expenses:Food:Groceries", date, pdf)

        // A PDF, not something that merely has the extension.
        assertEquals("%PDF", String(pdf.copyOfRange(0, 4)))
        assertTrue(
            stored.relativePath,
            stored.relativePath.startsWith("documents/Expenses/Food/Groceries/2026-08-24."),
        )
        assertTrue(File(workspace.projectDir("p"), stored.relativePath).length() > 0)
        // Two captures, two pages — and the platform's own reader can open the result.
        assertEquals(2, pageSizes(pdf).size)
    }

    @Test fun theOriginalCapturesAreUntouchedByAssembly() {
        val original = capture("page-1.jpg", Color.WHITE)
        val before = original.readBytes()

        assembler.assemble(CapturedPages().add(original.name).rotate(0)) { File(root, it) }

        assertTrue("the original capture was removed", original.isFile)
        assertArrayEquals("the original capture was modified", before, original.readBytes())
    }

    @Test fun rotatingAPageTurnsThePdfPageButNotTheCapture() {
        val original = capture("page-1.jpg", Color.WHITE, width = 600, height = 900)
        val upright = CapturedPages().add(original.name)

        val straight = pageSizes(assembler.assemble(upright) { File(root, it) }).single()
        val turned = pageSizes(assembler.assemble(upright.rotate(0)) { File(root, it) }).single()

        // Portrait becomes landscape: the assertion is the page itself, not a byte count.
        assertTrue("expected a portrait page, got $straight", straight.second > straight.first)
        assertEquals("a quarter turn should swap the page's sides", straight.first, turned.second)
        assertEquals(straight.second, turned.first)
        assertTrue(original.isFile)
    }

    @Test fun savingTheSameDocumentTwiceLeavesOneFile() {
        val page = capture("page-1.jpg", Color.WHITE)
        val pages = CapturedPages().add(page.name)

        val first = store.store("p", "Assets:Cash", date, assembler.assemble(pages) { File(root, it) })
        val second = store.store("p", "Assets:Cash", date, assembler.assemble(pages) { File(root, it) })

        assertEquals(
            "a retried save must not duplicate a receipt",
            first.relativePath,
            second.relativePath,
        )
        assertEquals(1, store.list("p").size)
        assertTrue(second.alreadyExisted)
    }

    @Test fun aDocumentTooLargeForOnePageIsScaledRatherThanRefused() {
        // What a modern phone camera actually produces. It must not become a 12-megapixel
        // page: a project full of those cannot be backed up over a phone connection.
        val huge = capture("page-1.jpg", Color.WHITE, width = 4000, height = 3000)

        val pdf = assembler.assemble(CapturedPages().add(huge.name)) { File(root, it) }

        assertTrue("a scaled page should be well under a megabyte, was ${pdf.size}", pdf.size < 1_000_000)
    }

    @Test fun anEmptyDocumentIsRefusedBeforeAnythingIsWritten() {
        val error = runCatching { assembler.assemble(CapturedPages()) { File(root, it) } }
            .exceptionOrNull()

        assertTrue("expected a refusal, got $error", error is IllegalArgumentException)
        assertEquals(emptyList<String>(), store.list("p"))
    }

    @Test fun aMissingCaptureFailsLoudlyRatherThanFilingAnEmptyPage() {
        val pages = CapturedPages().add("never-taken.jpg")

        val error = runCatching { assembler.assemble(pages) { File(root, it) } }.exceptionOrNull()

        assertTrue("expected a failure naming the page, got $error", error != null)
        assertTrue(error!!.message!!, error.message!!.contains("never-taken.jpg"))
    }
}
