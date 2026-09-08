package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.PdfFileTopology
import com.duluin.ftth.inventory.application.service.PdfSyntaxBudget
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdfparser.PDFParser
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehousePdfTopologyTest {
    @ParameterizedTest @ValueSource(strings = ["RAW_GAP", "UNINDEXED_OBJECT", "STREAM_LENGTH", "GENERATION", "FALSE_STARTXREF"])
    fun `complete topology rejects gaps unindexed spans lengths and reference disagreements`(kind: String) {
        val bytes = when (kind) {
            "RAW_GAP" -> ReceiptPdfSyntaxFixtures.rawGap()
            "UNINDEXED_OBJECT" -> ReceiptPdfSyntaxFixtures.classic(gap = "99 0 obj\nnull\nendobj\n")
            "STREAM_LENGTH" -> ReceiptPdfSyntaxFixtures.classic("q Q").toString(Charsets.ISO_8859_1).replace("/Length 3", "/Length 2").toByteArray(Charsets.ISO_8859_1)
            "GENERATION" -> ReceiptEvidenceFixtures.pdf().toString(Charsets.US_ASCII).replace("4 0 obj", "4 1 obj").toByteArray()
            "FALSE_STARTXREF" -> ReceiptEvidenceFixtures.pdf().toString(Charsets.US_ASCII).replace("startxref\n272", "startxref\n271").toByteArray()
            else -> error("Unknown fixture")
        }
        assertThatThrownBy { validate(bytes) }.isInstanceOfAny(IllegalArgumentException::class.java, IllegalStateException::class.java, java.io.IOException::class.java)
    }
    @ParameterizedTest @ValueSource(strings = ["EMPTY", "HTML_LITERAL", "COMMENTS", "FORM_IMAGE", "INCREMENTAL"])
    fun `xref streams object streams indirect lengths and incremental revisions validate`(kind: String) = validate(ReceiptPdfSyntaxFixtures.positive(kind))
    private fun validate(bytes: ByteArray) = RandomAccessReadBuffer(bytes).use { source ->
        PDFParser(source).parse(false).use { document -> PdfFileTopology.validate(bytes, document, PdfSyntaxBudget()) }
    }
}
