package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.*
import org.apache.pdfbox.contentstream.operator.OperatorName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WarehousePdfContentSyntaxTest {
    @Test fun `operator table covers every PDFBox standard operator constant`() {
        val standard = OperatorName::class.java.fields.filter { it.type == String::class.java }.map { it.get(null).toString() }.toSet()
        assertThat(PdfOperatorSyntax.operators).containsExactlyInAnyOrderElementsOf(standard)
    }
    @ParameterizedTest @ValueSource(strings = ["BT /F1 12 Tf (<html>script</html>) Tj ET", "BX 1 FutureOp EX", "BX nullOperator EX", "q /DeviceRGB cs 0.1 0.2 0.3 scn 0 0 10 10 re f Q", "/Tag << /ActualText (<html>) >> BDC EMC"])
    fun `standard operands and compatibility syntax remain valid`(content: String) {
        PdfContentSyntax.validate(content.toByteArray(), null, PdfSyntaxBudget())
    }
    @ParameterizedTest @ValueSource(strings = ["1 FutureOp", "BX 1 q EX", "1 2 3 4 5 cm", "BT 1 Tj ET", "BT /F1 12 Tf [(text) /Bad] TJ ET", "1 0 0 sc", "1000 0 d0", "1 2 m W q"])
    fun `unknown operators and malformed operand sequences fail`(content: String) {
        assertThatThrownBy { PdfContentSyntax.validate(content.toByteArray(), null, PdfSyntaxBudget()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
