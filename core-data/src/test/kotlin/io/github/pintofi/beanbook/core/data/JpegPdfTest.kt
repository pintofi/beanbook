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

/** The scanner's PDF writer: valid structure, JPEG carried as it is, same bytes every time (B79). */
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
}
