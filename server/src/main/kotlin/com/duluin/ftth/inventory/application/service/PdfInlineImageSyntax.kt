package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage

internal object PdfInlineImageSyntax {
    fun validate(cursor: PdfLexicalCursor, operator: Operator, resources: PDResources?, budget: PdfSyntaxBudget, maskOnly: Boolean) {
        val names = mutableSetOf<String>()
        while (true) {
            cursor.trivia()
            if (cursor.matches("ID")) { cursor.expect("ID"); break }
            val name = cursor.value(references = false) as? PdfName ?: error("Inline image key required")
            require(names.add(name.value))
            cursor.value(references = false)
        }
        require(pdfWhitespace(cursor.peek()))
        if (cursor.peek() == 13) cursor.eol() else cursor.position++
        val data = requireNotNull(operator.imageData)
        require(data.size <= cursor.limit - cursor.position)
        require(data.indices.all { data[it] == cursor.bytes[cursor.position + it] })
        cursor.position += data.size
        cursor.whitespace()
        cursor.expect("EI")
        val dictionary = requireNotNull(operator.imageParameters)
        require(names.size == dictionary.size())
        val image = PDInlineImage(dictionary, data, resources)
        require(image.width in 1..10000 && image.height in 1..10000 && image.width.toLong() * image.height <= 25000000)
        require(!maskOnly || image.isStencil)
        val bits = if (image.isStencil) 1 else image.bitsPerComponent
        require(bits in setOf(1, 2, 4, 8, 16))
        val components = if (image.isStencil) 1 else image.colorSpace.numberOfComponents
        require(components in 1..32)
        val expected = ((image.width.toLong() * bits * components + 7) / 8) * image.height
        require(expected <= 16777216)
        val filtered = dictionary.containsKey(COSName.FILTER) || dictionary.containsKey(COSName.F)
        val samples = if (filtered) data else {
            require(data.size >= expected && data.drop(expected.toInt()).all { pdfWhitespace(it.toInt().and(255)) })
            data.copyOf(expected.toInt())
        }
        val decoded = PDInlineImage(dictionary, samples, resources).createInputStream().use { it.readNBytes(expected.toInt() + 1) }
        budget.decoded(decoded.size)
        require(decoded.size.toLong() == expected)
    }
}
