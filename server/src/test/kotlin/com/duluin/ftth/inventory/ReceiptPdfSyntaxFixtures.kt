package com.duluin.ftth.inventory

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

internal object ReceiptPdfSyntaxFixtures {
    fun rawGap(): ByteArray {
        val original = ReceiptEvidenceFixtures.pdf().toString(Charsets.US_ASCII)
        val gap = "<html><script>alert(1)</script></html>\n"
        val xref = original.indexOf("xref\n")
        return (original.substring(0, xref) + gap + original.substring(xref))
            .replace("startxref\n$xref\n", "startxref\n${xref + gap.length}\n").toByteArray(Charsets.US_ASCII)
    }

    fun classic(content: String = "", gap: String = "", indirectLength: Boolean = false): ByteArray {
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${if (indirectLength) "6 0 R" else content.length} >>\nstream\n${content}\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>") + if (indirectLength) listOf(content.length.toString()) else emptyList()
        return serialize(objects, gap)
    }

    internal fun serialize(objects: List<String>, gap: String = ""): ByteArray {
        val text = StringBuilder("%PDF-1.7\n%\u00e2\u00e3\u00cf\u00d3\n")
        val offsets = objects.mapIndexed { index, body -> val offset = text.length; text.append("${index + 1} 0 obj\n$body\nendobj\n"); offset }
        text.append(gap)
        val xref = text.length
        text.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { text.append("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        text.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return text.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun untypedXObject(): ByteArray {
        val content = "/X Do"
        val hidden = "<html><script>alert(1)</script></html>"
        return serialize(listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << /XObject << /X 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream", "<< /Length ${hidden.length} >>\nstream\n$hidden\nendstream"))
    }

    fun inheritedForm(): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle(100f, 100f)).apply { resources = PDResources() }
        document.addPage(page)
        page.resources.cosObject.setItem(COSName.COLORSPACE, COSDictionary().apply { setItem(COSName.getPDFName("CS1"), COSName.DEVICERGB) })
        val form = PDFormXObject(document).apply { bBox = PDRectangle(20f, 20f); cosObject.removeItem(COSName.RESOURCES) }
        form.contentStream.createOutputStream().use { it.write("/CS1 cs 1 0 0 sc 0 0 10 10 re f".toByteArray()) }
        PDPageContentStream(document, page).use { it.drawForm(form) }
        ByteArrayOutputStream().use { output -> document.save(output); output.toByteArray() }
    }

    fun formImage(formContent: String = "0 0 20 20 re f\n"): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle(100f, 100f))
        document.addPage(page)
        val form = PDFormXObject(document).apply { resources = PDResources(); bBox = PDRectangle(20f, 20f) }
        form.contentStream.createOutputStream().use { it.write(formContent.toByteArray(Charsets.US_ASCII)) }
        val image = LosslessFactory.createFromImage(document, BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB))
        PDPageContentStream(document, page).use { content -> content.drawForm(form); content.drawImage(image, 20f, 20f, 10f, 10f) }
        ByteArrayOutputStream().use { output -> document.save(output); output.toByteArray() }
    }

    fun positive(kind: String): ByteArray = when (kind) {
        "EMPTY" -> classic()
        "TEXT" -> classic("BT /F1 12 Tf 10 10 Td (Receipt) Tj ET\n")
        "HTML_LITERAL" -> classic("BT /F1 12 Tf (<html><script>alert\\(1\\)</script></html> endobj endstream) Tj ET\n", indirectLength = true)
        "VECTOR" -> classic("q 0.1 0.2 0.3 rg 0 0 m 10 10 l 1 2 3 4 5 6 c h S Q\n")
        "COMMENTS" -> classic("% <html> is a PDF comment\nq\t0 0 10 10 re\nf Q\n", " \r\n% comment between objects and xref\n\t")
        "INLINE_IMAGE" -> classic("q BI /W 1 /H 1 /BPC 8 /CS /RGB ID \u0000\u007f\u00ff EI Q\n")
        "INLINE_CRLF" -> classic("q BI /W 1 /H 1 /BPC 8 /CS /RGB ID\r\n\u0000\u007f\u00ff \r\nEI Q\n")
        "INLINE_FLATE" -> {
            val encoded = ByteArrayOutputStream().use { output -> DeflaterOutputStream(output).use { it.write(byteArrayOf(0, 127, -1)) }; output.toByteArray() }
            classic("q BI /W 1 /H 1 /BPC 8 /CS /RGB /F /Fl ID ${encoded.toString(Charsets.ISO_8859_1)} EI Q\n")
        }
        "COMPATIBILITY" -> classic("BX 12 /Parameter FuturePaint EX\n")
        "FORM_IMAGE" -> formImage()
        "INHERITED_FORM" -> inheritedForm()
        "INCREMENTAL" -> {
            val original = classic().toString(Charsets.ISO_8859_1)
            val previous = original.substringAfterLast("startxref\n").substringBefore('\n')
            val update = "7 0 obj\n<< /Producer (Receipt update) >>\nendobj\n"
            (original + update + "xref\n7 1\n${"%010d".format(java.util.Locale.ROOT, original.length)} 00000 n \ntrailer\n<< /Size 8 /Root 1 0 R /Info 7 0 R /Prev $previous >>\nstartxref\n${original.length + update.length}\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)
        }
        else -> error("Unknown positive fixture")
    }
}
