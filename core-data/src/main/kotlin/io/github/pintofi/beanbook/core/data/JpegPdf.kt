package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.PagePlacement
import java.io.ByteArrayOutputStream

/**
 * A PDF whose pages are JPEG photographs, written directly.
 *
 * Android's `PdfDocument` draws a bitmap into the page as raw pixels and deflates them, so a
 * photographed receipt was stored far larger than its JPEG -- and every page was a pixel per
 * point, a 2000-pixel photograph becoming a page 70 cm tall. The scanner already has the page
 * as a JPEG. PDF carries JPEG natively (`/DCTDecode`), so the bytes go in as they are: a page
 * is no larger than its photograph, and it has a physical size a printer or a
 * desktop reader agrees with.
 *
 * What is written is the smallest valid file for the job, and nothing in it varies between
 * runs -- no creation date, no random identifier -- so the same pages always make the same
 * bytes. That matters: documents are named by a hash of their content, and a retried save has
 * to find the file it already wrote rather than file a second copy (invariant 6).
 */
object JpegPdf {

    /**
     * One page: a baseline JPEG and its size in pixels. With a [placement] the page is a sheet of
     * paper and the picture is laid on it; without one the page is the picture's own size.
     */
    class Page(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val placement: PagePlacement? = null,
    ) {
        init {
            require(width > 0 && height > 0) { "a page needs a size, got ${width}x$height" }
            placement?.let {
                require(it.pageWidth > 0 && it.pageHeight > 0 && it.width > 0 && it.height > 0) {
                    "a placement needs sizes, got $it"
                }
            }
            require(jpeg.size > 3 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) {
                "page data is not a JPEG"
            }
        }
    }

    /**
     * The resolution a page is assumed to have been scanned at. At 200 dpi an A4 sheet is
     * 1654 x 2339 pixels, which is what the assembler produces, and it prints at A4.
     */
    const val DOTS_PER_INCH = 200

    fun write(pages: List<Page>): ByteArray {
        require(pages.isNotEmpty()) { "a document needs at least one page" }
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun beginObject(): Int {
            offsets += out.size()
            val number = offsets.size
            ascii("$number 0 obj\n")
            return number
        }

        // The binary comment on line two tells transfer tools the file is not text.
        ascii("%PDF-1.4\n")
        out.write(BINARY_MARKER)

        // Object numbers are fixed by position: 1 catalogue, 2 page tree, then three per page.
        val pageNumbers = pages.indices.map { 3 + it * 3 }

        beginObject()
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        beginObject()
        val kids = pageNumbers.joinToString(" ") { "$it 0 R" }
        ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")

        pages.forEachIndexed { index, page ->
            val pageNumber = pageNumbers[index]
            val placement = page.placement
            val width = if (placement == null) points(page.width) else hundredths(placement.pageWidth)
            val height = if (placement == null) points(page.height) else hundredths(placement.pageHeight)
            val content = if (placement == null) {
                "q $width 0 0 $height 0 0 cm /Im0 Do Q\n"
            } else {
                "q ${hundredths(placement.width)} 0 0 ${hundredths(placement.height)} " +
                    "${hundredths(placement.x)} ${hundredths(placement.y)} cm /Im0 Do Q\n"
            }

            beginObject()
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] " +
                    "/Resources << /XObject << /Im0 ${pageNumber + 1} 0 R >> >> " +
                    "/Contents ${pageNumber + 2} 0 R >>\nendobj\n",
            )
            beginObject()
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                    "/Length ${page.jpeg.size} >>\nstream\n",
            )
            out.write(page.jpeg)
            ascii("\nendstream\nendobj\n")
            beginObject()
            ascii("<< /Length ${content.length} >>\nstream\n$content")
            ascii("endstream\nendobj\n")
        }

        val xref = out.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    private val BINARY_MARKER =
        byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte())

    /** A length in hundredths of a point, as PDF points to two places. */
    private fun hundredths(value: Int): String =
        String.format(java.util.Locale.ROOT, "%d.%02d", value / 100, value % 100)

    /** Pixels at [DOTS_PER_INCH] as PDF points, to two places. */
    private fun points(pixels: Int): String {
        val hundredths = Math.round(pixels * 72.0 * 100 / DOTS_PER_INCH)
        return String.format(java.util.Locale.ROOT, "%d.%02d", hundredths / 100, hundredths % 100)
    }
}
