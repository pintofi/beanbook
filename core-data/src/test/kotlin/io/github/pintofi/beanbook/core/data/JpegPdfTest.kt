package io.github.pintofi.beanbook.core.data

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The scanner's PDF writer: valid structure, JPEG carried as it is, same bytes every time. */
class JpegPdfTest {

    private fun jpeg(width: Int, height: Int, colour: Color = Color.WHITE): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { this.color = colour; fillRect(0, 0, width, height); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    private fun text(pdf: ByteArray) = String(pdf, Charsets.ISO_8859_1)

    @Test
    fun `every cross-reference entry points at the object it names`() {
        val pdf = JpegPdf.write(
            listOf(JpegPdf.Page(jpeg(60, 90), 60, 90), JpegPdf.Page(jpeg(90, 60, Color.GRAY), 90, 60)),
        )
        val body = text(pdf)
        val xref = body.substringAfter("startxref\n").substringBefore("\n").toInt()
        assertTrue(body.startsWith("xref", xref), "startxref does not point at the table")

        val entries = body.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(2 + 3 * 2, entries.size)
        entries.forEachIndexed { index, entry ->
            val offset = entry.take(10).toInt()
            assertTrue(body.startsWith("${index + 1} 0 obj", offset), "object ${index + 1} is not at $offset")
        }
        assertTrue(body.trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun `the photograph is embedded byte for byte`() {
        val photo = jpeg(40, 40, Color.RED)

        val pdf = JpegPdf.write(listOf(JpegPdf.Page(photo, 40, 40)))

        val start = text(pdf).indexOf("stream\n", text(pdf).indexOf("/DCTDecode")) + "stream\n".length
        assertContentEquals(photo, pdf.copyOfRange(start, start + photo.size))
    }

    @Test
    fun `a page has a physical size, not a point per pixel`() {
        // A4 at 200 dpi.
        val pdf = text(JpegPdf.write(listOf(JpegPdf.Page(jpeg(1654, 2339), 1654, 2339))))

        assertTrue(pdf.contains("/MediaBox [0 0 595.44 842.04]"), pdf.substringBefore("stream"))
    }

    @Test
    fun `the same pages make the same bytes`() {
        val photo = jpeg(50, 70)

        assertContentEquals(
            JpegPdf.write(listOf(JpegPdf.Page(photo, 50, 70))),
            JpegPdf.write(listOf(JpegPdf.Page(photo, 50, 70))),
        )
    }

    @Test
    fun `nothing that is not a JPEG, and no empty document`() {
        assertFailsWith<IllegalArgumentException> { JpegPdf.Page(byteArrayOf(1, 2, 3, 4), 1, 1) }
        assertFailsWith<IllegalArgumentException> { JpegPdf.write(emptyList()) }
    }

    private fun fixedJpeg(seed: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
        ByteArray(64) { (it * 7 + seed).toByte() } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /**
     * An export that keeps each page as it was photographed is byte for byte what it has always
     * been (#184): the hash below was taken from this writer before page sizes existed. If this
     * fails, files already filed would be filed again under a new name.
     */
    @Test
    fun `a document of as-scanned pages is the same bytes as ever`() {
        val pdf = JpegPdf.write(
            listOf(
                JpegPdf.Page(fixedJpeg(1), 1654, 2339),
                JpegPdf.Page(fixedJpeg(2), 600, 2400),
            ),
        )
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(pdf)
            .joinToString("") { "%02x".format(it) }
        assertEquals("3e41e15a860e54f1f02a139354f367fadd832c5604376b65937a4a5cfaa93a01", sha)
    }

    @Test
    fun `a page placed on paper has the paper's size and the picture where it was put`() {
        val placement = io.github.pintofi.beanbook.core.domain.PagePlacement(59528, 84189, 1417, 2007, 56694, 80174)
        val pdf = text(JpegPdf.write(listOf(JpegPdf.Page(fixedJpeg(1), 1654, 2339, placement))))
        assertTrue("/MediaBox [0 0 595.28 841.89]" in pdf, pdf.take(400))
        assertTrue("q 566.94 0 0 801.74 14.17 20.07 cm /Im0 Do Q" in pdf)
    }

    @Test
    fun `the cross-reference table is still right with a placement`() {
        val placement = io.github.pintofi.beanbook.core.domain.PagePlacement(61200, 79200, 19800, 25200, 21600, 28800)
        val pdf = JpegPdf.write(
            listOf(JpegPdf.Page(fixedJpeg(3), 300, 400, placement), JpegPdf.Page(fixedJpeg(4), 60, 90)),
        )
        val body = text(pdf)
        val xref = body.substringAfter("startxref\n").substringBefore("\n").toInt()
        val entries = body.substring(xref).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { index, entry ->
            assertTrue(body.startsWith("${index + 1} 0 obj", entry.take(10).toInt()), "object ${index + 1}")
        }
    }

    @Test
    fun `the same pages with the same placement make the same bytes`() {
        val placement = io.github.pintofi.beanbook.core.domain.PagePlacement(59528, 84189, 1417, 2007, 56694, 80174)
        fun make() = JpegPdf.write(listOf(JpegPdf.Page(fixedJpeg(1), 1654, 2339, placement)))
        assertContentEquals(make(), make())
    }

    @Test
    fun `the layout and the writer agree on how many hundredths of a point a pixel is`() {
        val perPixel = io.github.pintofi.beanbook.core.domain.PageLayout.HUNDREDTHS_PER_PIXEL
        assertEquals(7200, perPixel * JpegPdf.DOTS_PER_INCH)
    }
}
