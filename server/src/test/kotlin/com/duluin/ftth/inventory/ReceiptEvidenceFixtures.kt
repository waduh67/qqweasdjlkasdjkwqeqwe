package com.duluin.ftth.inventory

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

internal object ReceiptEvidenceFixtures {
    fun pdf(): ByteArray {
        val text = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>",
            "<< /Length 4 >>\nstream\nq\nQ\nendstream")
        objects.forEachIndexed { index, body -> offsets += text.length; text.append("${index + 1} 0 obj\n$body\nendobj\n") }
        val xref = text.length
        text.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { text.append("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        text.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return text.toString().toByteArray(Charsets.US_ASCII)
    }
    fun image(format: String): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output))
        output.toByteArray()
    }
}
