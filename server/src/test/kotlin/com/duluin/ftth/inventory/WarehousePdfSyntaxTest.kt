package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.validateReceiptEvidence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest

class WarehousePdfSyntaxTest {
    @Test fun `raw gap matches exact verifier reproduction`() {
        val bytes = ReceiptPdfSyntaxFixtures.rawGap()
        assertThat(bytes.size).isEqualTo(474)
        assertThat(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .isEqualTo("0d1beb189093fb5f730111816aae48930920eb62f570f90ea1df72017c457b84")
    }
    @Test fun `raw non PDF gap is rejected despite correct xref offsets`() {
        assertThatThrownBy { validateReceiptEvidence("application/pdf", ReceiptPdfSyntaxFixtures.rawGap()) }.isInstanceOf(WarehouseContractException::class.java)
    }
    @ParameterizedTest
    @ValueSource(strings = ["<html><script>alert(1)</script></html>", "12 34 UnknownPaint", "1 q Q", "BT /F1 Tf ET", "BT (unclosed Tj ET", "BT [(unclosed) TJ ET", "/Tag << /ActualText (unclosed) BDC EMC", "BT (text) Tj", "Q", "0 0 m 42", "BX 1 q EX", "BX", "BT BT ET ET", "1 2 3 cm", "BI /W 1 ID data"])
    fun `malformed content instructions are rejected`(content: String) {
        assertThatThrownBy { validateReceiptEvidence("application/pdf", ReceiptPdfSyntaxFixtures.classic(content)) }.isInstanceOf(WarehouseContractException::class.java)
    }
    @Test fun `malformed form content is rejected without rendering`() {
        assertThatThrownBy { validateReceiptEvidence("application/pdf", ReceiptPdfSyntaxFixtures.formImage("12 UnknownPaint")) }.isInstanceOf(WarehouseContractException::class.java)
    }
    @Test fun `untyped XObject cannot hide executable content`() {
        assertThatThrownBy { validateReceiptEvidence("application/pdf", ReceiptPdfSyntaxFixtures.untypedXObject()) }.isInstanceOf(WarehouseContractException::class.java)
    }
    @ParameterizedTest @ValueSource(strings = ["EMPTY", "TEXT", "HTML_LITERAL", "VECTOR", "COMMENTS", "INLINE_IMAGE", "INLINE_CRLF", "INLINE_FLATE", "COMPATIBILITY", "FORM_IMAGE", "INHERITED_FORM", "INCREMENTAL"])
    fun `valid diverse document syntax is preserved`(kind: String) {
        com.duluin.ftth.inventory.application.service.ReceiptPdfValidation.validate(ReceiptPdfSyntaxFixtures.positive(kind))
    }
}
