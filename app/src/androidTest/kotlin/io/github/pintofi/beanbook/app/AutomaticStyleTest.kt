package io.github.pintofi.beanbook.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.documents.DocumentCapture
import io.github.pintofi.beanbook.core.data.DocumentStore
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.domain.CropQuad
import io.github.pintofi.beanbook.core.domain.PageStyle
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page whose style the scanner chose is chosen again when it is cropped, because the table is no
 * longer in the count; a page the person styled is left alone, and a result that arrives after the
 * person moved on is dropped (#181).
 */
@RunWith(AndroidJUnit4::class)
class AutomaticStyleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "auto-style-${System.nanoTime()}").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val quad = CropQuad.whole(100f, 100f)

    @After fun clean() {
        root.deleteRecursively()
    }

    private fun capture(choose: suspend (File, CropQuad?) -> PageStyle?): DocumentCapture {
        val workspace = ProjectWorkspace(root)
        return DocumentCapture(context, DocumentStore(workspace), scope, chooseStyle = choose)
    }

    private fun page(name: String) = File(root, name).also { it.writeBytes(byteArrayOf(1)) }

    private fun DocumentCapture.styleOfFirst() = pages.value.pages.first().style

    private fun waitFor(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) kotlinx.coroutines.delay(20) }
    }

    @Test fun anAutomaticPageIsChosenAgainWhenItIsCropped() {
        val capture = capture { _, _ -> PageStyle.GREYSCALE }
        capture.add(page("a.jpg"), PageStyle.DOCUMENT, automatic = true)
        capture.crop(0, quad)
        waitFor { capture.styleOfFirst() == PageStyle.GREYSCALE }
        assertTrue("it stays automatic", capture.pages.value.pages.first().automaticStyle)
    }

    @Test fun aPageThePersonStyledIsLeftAlone() {
        var asked = false
        val capture = capture { _, _ -> asked = true; PageStyle.GREYSCALE }
        capture.add(page("a.jpg"), PageStyle.DOCUMENT, automatic = true)
        capture.restyle(0, PageStyle.COLOUR)
        capture.crop(0, quad)
        runBlocking { kotlinx.coroutines.delay(300) }
        assertEquals(PageStyle.COLOUR, capture.styleOfFirst())
        assertFalse(asked)
    }

    @Test fun aResultThatArrivesAfterThePersonMovedOnIsDropped() {
        val gate = CompletableDeferred<Unit>()
        val capture = capture { _, _ -> gate.await(); PageStyle.GREYSCALE }
        capture.add(page("a.jpg"), PageStyle.DOCUMENT, automatic = true)
        capture.crop(0, quad)
        // While the answer is pending, the person picks a style themselves.
        capture.restyle(0, PageStyle.BLACK_AND_WHITE)
        gate.complete(Unit)
        runBlocking { kotlinx.coroutines.delay(300) }
        assertEquals(PageStyle.BLACK_AND_WHITE, capture.styleOfFirst())
        assertFalse(capture.pages.value.pages.first().automaticStyle)
    }
}
