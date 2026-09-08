package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.PdfLexicalCursor
import com.duluin.ftth.inventory.application.service.PdfDictionary
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehousePdfLexicalTest {
    @ParameterizedTest @ValueSource(strings = ["(unclosed", "<nothex>", "[1 2", "<< /Key (value)", "<< /Key 1 /Key 2 >>", "1e3", "+", "--3", "/Bad#GG"])
    fun `incomplete and ambiguous direct objects fail lexically`(source: String) {
        assertThatThrownBy { PdfLexicalCursor(source.toByteArray()).value() }.isInstanceOfAny(IllegalArgumentException::class.java, IllegalStateException::class.java)
    }
    @ParameterizedTest @ValueSource(strings = ["<< /Text (<html> script endobj) /Value -0.5 >>", "<< /L#65ngth 5 0 R /Array [1 .25 true null <ABC>] >>", "<< /String (nested\\(parentheses\\) \\101) >>"])
    fun `complete PDF dictionaries accept literal text escapes references and odd hex strings`(source: String) {
        val cursor = PdfLexicalCursor(source.toByteArray())
        assertThat(cursor.value()).isInstanceOf(PdfDictionary::class.java)
        assertThat(cursor.end()).isTrue()
    }
}
