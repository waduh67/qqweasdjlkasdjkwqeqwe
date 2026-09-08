package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.*
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehousePdfGraphicsSnapshotTest {
    @Test fun `immutable Form snapshot retains caller fields and applies local matrix and clip`() {
        val parent = PdfGraphicsSyntax(stroke = PdfColourSpace(4), fill = PdfColourSpace(3),
            transform = PdfTransform(2.0, 0.0, 0.0, 2.0, 10.0, 20.0), text = PdfTextSyntax(leading = 8.0), line = PdfLineSyntax(width = 2.0))
        COSStream().use { form ->
            form.setItem(COSName.BBOX, COSArray().apply { listOf(0, 0, 20, 20).forEach { add(COSInteger.get(it.toLong())) } })
            form.setItem(COSName.MATRIX, COSArray().apply { listOf(1, 0, 0, 1, 3, 4).forEach { add(COSInteger.get(it.toLong())) } })
            val local = parent.enterForm(form)
            assertThat(local.transform.e).isEqualTo(16.0)
            assertThat(local.transform.f).isEqualTo(28.0)
            assertThat(local.clip?.boundary).isInstanceOf(PdfClipBoundary.Box::class.java)
            assertThat(local.stroke).isEqualTo(parent.stroke)
            assertThat(local.fill).isEqualTo(parent.fill)
            assertThat(local.text).isEqualTo(parent.text)
            assertThat(local.line).isEqualTo(parent.line)
            assertThat(parent.clip).isNull()
            assertThat(parent.transform.e).isEqualTo(10.0)
        }
    }
}
