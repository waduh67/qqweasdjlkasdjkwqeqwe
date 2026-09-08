package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.*
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDResources
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarehousePdfGraphicsStateTest {
    @Test fun `Form entry composes matrix and intersects symbolic clip while preserving inherited state`() {
        val initial = PdfContentSyntax.validate("/DeviceRGB cs /DeviceCMYK CS 2 0 0 2 10 20 cm 0 0 100 100 re W n 2 w 7 Tc 8 TL".toByteArray(), null, PdfSyntaxBudget())
        assertThat((initial.clip?.boundary as PdfClipBoundary.Path).commands.map { it.operator }).containsExactly("re")
        COSStream().use { form ->
            form.setItem(COSName.BBOX, numbers(0, 0, 20, 20))
            form.setItem(COSName.MATRIX, numbers(1, 0, 0, 1, 3, 4))
            val child = initial.enterForm(form)
            assertThat(child.fill).isEqualTo(PdfColourSpace(3))
            assertThat(child.stroke).isEqualTo(PdfColourSpace(4))
            assertThat(child.transform).isEqualTo(PdfTransform(2.0, 0.0, 0.0, 2.0, 16.0, 28.0))
            assertThat(child.clip?.previous).isSameAs(initial.clip)
            assertThat((child.clip?.boundary as PdfClipBoundary.Box).corners).containsExactly(PdfPoint(16.0, 28.0), PdfPoint(56.0, 28.0), PdfPoint(56.0, 68.0), PdfPoint(16.0, 68.0))
            assertThat(child.line.width).isEqualTo(2.0)
            assertThat(child.text.characterSpacing).isEqualTo(7.0)
            assertThat(child.text.leading).isEqualTo(8.0)
            assertThat(initial.transform).isEqualTo(PdfTransform(2.0, 0.0, 0.0, 2.0, 10.0, 20.0))
        }
    }

    @Test fun `local Form matrix clip line and text modifications do not escape implicit restore`() {
        COSStream().use { form ->
            form.setName(COSName.SUBTYPE, "Form"); form.setItem(COSName.BBOX, numbers(0, 0, 20, 20))
            form.setItem(COSName.MATRIX, numbers(1, 0, 0, 1, 3, 4))
            form.createOutputStream().use { it.write("3 0 0 3 0 0 cm 99 w 99 Tc 99 TL 0 0 10 10 re W n /DeviceGray cs .5 sc".toByteArray()) }
            val resources = PDResources().apply { cosObject.setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Fm"), form) }) }
            val parent = PdfContentSyntax.validate("/DeviceRGB cs 2 0 0 2 10 20 cm 2 w 7 Tc 8 TL /Fm Do 1 0 0 sc".toByteArray(), resources, PdfSyntaxBudget())
            assertThat(parent.transform).isEqualTo(PdfTransform(2.0, 0.0, 0.0, 2.0, 10.0, 20.0))
            assertThat(parent.clip).isNull()
            assertThat(parent.line.width).isEqualTo(2.0)
            assertThat(parent.text.characterSpacing).isEqualTo(7.0)
            assertThat(parent.text.leading).isEqualTo(8.0)
            assertThat(parent.fill).isEqualTo(PdfColourSpace(3))
        }
    }

    @Test fun `q and Q restore the complete tracked snapshot`() {
        val state = PdfContentSyntax.validate("2 w 7 Tc q 99 w 99 Tc 2 0 0 2 3 4 cm 0 0 10 10 re W n Q".toByteArray(), null, PdfSyntaxBudget())
        assertThat(state.line.width).isEqualTo(2.0)
        assertThat(state.text.characterSpacing).isEqualTo(7.0)
        assertThat(state.transform).isEqualTo(PdfTransform())
        assertThat(state.clip).isNull()
    }

    @Test fun `Form invocation uses the same decoded-byte budget as its caller`() {
        val budget = PdfSyntaxBudget()
        repeat(4) { budget.decoded(16777216) }
        COSStream().use { form ->
            form.setName(COSName.SUBTYPE, "Form"); form.setItem(COSName.BBOX, numbers(0, 0, 20, 20))
            form.createOutputStream().use { it.write("q Q".toByteArray()) }
            val resources = PDResources().apply { cosObject.setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Fm"), form) }) }
            assertThatThrownBy { PdfContentSyntax.validate("/Fm Do".toByteArray(), resources, budget) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun `extended graphics text and line state is captured without mutating resource dictionaries`() {
        val font = COSDictionary().apply { setName(COSName.SUBTYPE, "Type1"); setName(COSName.BASE_FONT, "Helvetica") }
        val settings = COSDictionary().apply {
            setInt(COSName.getPDFName("LW"), 5)
            setItem(COSName.FONT, COSArray().apply { add(font); add(COSInteger.get(12)) })
            setFloat(COSName.getPDFName("CA"), .5f)
        }
        val resources = PDResources().apply { cosObject.setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("GS"), settings) }) }
        val state = PdfContentSyntax.validate("/GS gs q 99 w Q".toByteArray(), resources, PdfSyntaxBudget())
        assertThat(state.line.width).isEqualTo(5.0)
        assertThat(state.text.font).isSameAs(font)
        assertThat(state.text.fontSize).isEqualTo(12.0)
        assertThat(state.extended?.dictionary).isSameAs(settings)
        assertThat(settings.getInt(COSName.getPDFName("LW"))).isEqualTo(5)
    }

    @Test fun `reused extended graphics operands are charged to the invocation budget`() {
        val settings = COSDictionary().apply { setItem(COSName.D, COSArray().apply {
            add(numbers(*IntArray(20) { 1 })); add(COSInteger.ZERO)
        }) }
        val resources = PDResources().apply { cosObject.setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("GS"), settings) }) }
        val budget = PdfSyntaxBudget().apply { charge(99990) }
        assertThatThrownBy { PdfContentSyntax.validate("/GS gs".toByteArray(), resources, budget) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun numbers(vararg values: Int) = COSArray().apply { values.forEach { add(COSInteger.get(it.toLong())) } }
}
