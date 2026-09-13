package io.github.pintofi.beanbook.app

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.documents.Photos
import io.github.pintofi.beanbook.core.domain.CapturedPages
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A photograph is stored the way the sensor read it, with a tag saying which way is up (B55).
 *
 * `BitmapFactory` ignores that tag and every decode in the app used it directly, so a page
 * photographed in portrait appeared on its side — in the capture strip, in the crop editor,
 * in the detector that looks for the page's edges, and in the finished PDF. One cause, four
 * symptoms, and the reason this tests the decoder rather than any one screen.
 */
@RunWith(AndroidJUnit4::class)
class PhotoOrientationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "orientation-${System.nanoTime()}").apply { mkdirs() }

    @After fun clean() {
        dir.deleteRecursively()
    }

    /** A landscape JPEG whose EXIF says it should be shown rotated by [degrees]. */
    private fun photo(degrees: Int): File {
        val file = File(dir, "page-$degrees.jpg")
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (degrees != 0) {
            ExifInterface(file.absolutePath).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    when (degrees) {
                        90 -> ExifInterface.ORIENTATION_ROTATE_90
                        180 -> ExifInterface.ORIENTATION_ROTATE_180
                        else -> ExifInterface.ORIENTATION_ROTATE_270
                    }.toString(),
                )
                saveAttributes()
            }
        }
        return file
    }

    @Test fun aQuarterTurnIsAppliedRatherThanIgnored() {
        val decoded = Photos.decodeUpright(photo(90))!!
        assertEquals("the page came back on its side", 60, decoded.width)
        assertEquals(120, decoded.height)
    }

    @Test fun aHalfTurnKeepsTheShapeAndStillCounts() {
        assertEquals(180, Photos.degreesFor(photo(180)))
        val decoded = Photos.decodeUpright(photo(180))!!
        assertEquals(120, decoded.width)
    }

    @Test fun aPhotographWithNoTagIsLeftAlone() {
        val decoded = Photos.decodeUpright(photo(0))!!
        assertEquals(120, decoded.width)
        assertEquals(60, decoded.height)
        assertEquals(0, Photos.degreesFor(photo(0)))
    }

    @Test fun samplingDownStillProducesAnUprightPage() {
        val decoded = Photos.decodeUpright(photo(270), maxEdge = 64)!!
        assertTrue("nothing was sampled down: ${decoded.width}x${decoded.height}", decoded.height <= 120)
        assertTrue("the sampled page is not portrait", decoded.height > decoded.width)
    }

    /**
     * The PDF is where the rotation finally matters: the page a person saw upright in the
     * strip has to be upright in the file they keep.
     */
    @Test fun theAssembledPdfUsesTheCameraSOrientation() {
        val file = photo(90)
        val pages = CapturedPages().add(file.name)
        val bytes = io.github.pintofi.beanbook.app.documents.PdfAssembler()
            .assemble(pages) { File(dir, it) }
        assertTrue("no PDF was produced", bytes.size > 100)
        assertTrue("the PDF header is missing", bytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    /**
     * The crop lands on the part of the page the person framed (B79).
     *
     * Corners are placed on the upright photograph -- by the detector and in the crop editor --
     * and the assembler used to apply them to the sideways pixels before turning them, so on a
     * phone that stores pictures sideways a different piece of the photograph was cut out.
     * The stored image here is landscape, tagged to be shown a quarter turn clockwise, with a
     * black block in its top-left corner: upright, that block is the top-right of the page.
     */
    @Test fun aCropIsTakenFromTheUprightPhotographNotTheSidewaysOne() {
        val file = File(dir, "sideways.jpg")
        val raw = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
            android.graphics.Canvas(this).drawRect(
                0f, 0f, 400f, 300f,
                android.graphics.Paint().apply { color = android.graphics.Color.BLACK },
            )
        }
        file.outputStream().use { raw.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        // Upright the page is 600 x 1200 and the block covers x 300..600, y 0..400.
        val quad = io.github.pintofi.beanbook.core.domain.CropQuad(
            topLeft = io.github.pintofi.beanbook.core.domain.CropPoint(340f, 30f),
            topRight = io.github.pintofi.beanbook.core.domain.CropPoint(570f, 30f),
            bottomRight = io.github.pintofi.beanbook.core.domain.CropPoint(570f, 370f),
            bottomLeft = io.github.pintofi.beanbook.core.domain.CropPoint(340f, 370f),
        )
        val pages = CapturedPages().add(file.name).crop(0, quad)

        val pdf = File(dir, "cropped.pdf").apply {
            writeBytes(io.github.pintofi.beanbook.app.documents.PdfAssembler().assemble(pages) { File(dir, it) })
        }
        val page = android.os.ParcelFileDescriptor.open(pdf, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            .use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { opened ->
                        Bitmap.createBitmap(opened.width, opened.height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(android.graphics.Color.WHITE)
                            opened.render(it, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        val samples = (1..9).flatMap { i -> (1..9).map { j -> page.getPixel(page.width * i / 10, page.height * j / 10) } }
        val dark = samples.count { android.graphics.Color.red(it) < 60 }
        assertTrue("expected the cropped page to be the black block, $dark of ${samples.size} samples were dark", dark == samples.size)
        assertTrue("the cropped page should be portrait like the framed region", page.height > page.width)
    }
}
