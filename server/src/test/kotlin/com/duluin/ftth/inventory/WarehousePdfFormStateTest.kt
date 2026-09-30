package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.validateReceiptEvidence
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.security.MessageDigest

class WarehousePdfFormStateTest {
    @Test fun `fixtures match verifier inherited and explicit reset documents`() {
        val inherited = ReceiptPdfFormFixtures.exact()
        val reset = ReceiptPdfFormFixtures.exact(true)
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertThat(inherited.size).isEqualTo(653)
        assertThat(hash(inherited)).isEqualTo("13a68f2c92ff8bd28ab062307b24092a2167a1f6e2b1b3f1087ae659a1a9c951")
        assertThat(reset.size).isEqualTo(667)
        assertThat(hash(reset)).isEqualTo("0707c233fc7e9f08c6f5a4ec45d28159c91a689b930ab489ab90f04a2bcdc459")
    }
    @ParameterizedTest @ValueSource(strings = ["EXACT", "RESET", "GRAY", "CMYK", "NAMED", "STROKE", "ISOLATED", "SHARED", "NESTED", "SIBLINGS", "DETACHED", "Q_RESTORE", "MATRIX", "NULL_MATRIX"])
    fun `forms inherit caller state and isolate their local changes`(kind: String) {
        validateReceiptEvidence("application/pdf", ReceiptPdfFormFixtures.positive(kind))
    }
    @ParameterizedTest @ValueSource(strings = ["GRAY_MISMATCH", "RGB_MISMATCH", "CMYK_MISMATCH", "NAMED_MISMATCH", "SECOND_INVOCATION", "CYCLE", "NESTED_CYCLE", "UNDERFLOW", "UNCLOSED", "BUDGET", "DEPTH", "MATRIX", "DETACHED_CONFLICT", "DETACHED_CHILD_CONFLICT"])
    fun `every invocation enforces operand state cycles stacks and shared limits`(kind: String) {
        assertThatThrownBy { validateReceiptEvidence("application/pdf", ReceiptPdfFormFixtures.negative(kind)) }.isInstanceOf(WarehouseContractException::class.java)
    }
}
