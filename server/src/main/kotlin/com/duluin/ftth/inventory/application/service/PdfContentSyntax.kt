package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdmodel.PDResources

internal enum class PdfContentKind { PAGE, FORM, UNCOLOURED_PATTERN, GLYPH }

internal object PdfContentSyntax {
    fun validate(bytes: ByteArray, resources: PDResources?, budget: PdfSyntaxBudget, kind: PdfContentKind = PdfContentKind.PAGE) {
        val cursor = PdfLexicalCursor(bytes)
        val parser = PDFStreamParser(bytes)
        val operands = mutableListOf<COSBase>()
        val graphics = ArrayDeque<PdfGraphicsSyntax>()
        var current = PdfGraphicsSyntax()
        var text = false
        var path = false
        var clipping = false
        var marked = 0
        var compatibility = 0
        var instructions = 0
        var uncoloured = kind == PdfContentKind.UNCOLOURED_PATTERN
        val pathEnd = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n")
        val pathBuild = setOf("m", "l", "c", "v", "y", "h", "re")
        val textOnly = setOf("Td", "TD", "Tm", "T*", "Tj", "TJ", "'", "\"")
        try {
            while (!cursor.end()) {
                budget.token()
                val start = cursor.peek()
                val scalar = start in listOf('/'.code, '('.code, '['.code, '<'.code, '+'.code, '-'.code, '.'.code) ||
                    start in '0'.code..'9'.code || cursor.keyword("true") || cursor.keyword("false") || cursor.keyword("null")
                if (scalar) {
                    cursor.value(references = false)
                    operands += parser.parseNextToken() as? COSBase ?: error("PDF parser token differs from lexical value")
                    require(operands.size <= 100000)
                    continue
                }
                val name = cursor.word()
                val operator = parser.parseNextToken() as? Operator ?: error("PDF operator required")
                require(name == operator.name)
                if (name !in PdfOperatorSyntax.operators) {
                    require(compatibility > 0)
                    operands.clear(); instructions++; continue
                }
                if (clipping) require(name in pathEnd)
                if (name in textOnly) require(text)
                if (name in pathBuild || name in pathEnd || name in setOf("W", "W*", "Do", "sh", "BI")) require(!text)
                if (uncoloured) require(name !in PdfOperatorSyntax.colours)
                if (name == "BI") {
                    require(operands.isEmpty())
                    PdfInlineImageSyntax.validate(cursor, operator, resources, budget, uncoloured)
                } else {
                    require(name !in setOf("ID", "EI"))
                    current = PdfOperatorSyntax.validate(name, operands, current, resources)
                }
                if (kind == PdfContentKind.GLYPH && instructions == 0) require(name in setOf("d0", "d1"))
                when (name) {
                    "q" -> { graphics.addLast(current); require(graphics.size <= 64) }
                    "Q" -> { require(graphics.isNotEmpty()); current = graphics.removeLast() }
                    "BT" -> { require(!text && !path); text = true }
                    "ET" -> { require(text); text = false }
                    "BMC", "BDC" -> { marked++; require(marked <= 64) }
                    "EMC" -> { require(marked > 0); marked-- }
                    "BX" -> { compatibility++; require(compatibility <= 64) }
                    "EX" -> { require(compatibility > 0); compatibility-- }
                    "m", "re" -> path = true
                    "l", "c", "v", "y", "h" -> require(path)
                    "W", "W*" -> { require(path); clipping = true }
                    "d0", "d1" -> {
                        require(kind == PdfContentKind.GLYPH && instructions == 0 && (operands[1] as COSNumber).floatValue() == 0f)
                        if (name == "d1") uncoloured = true
                    }
                }
                if (name in pathEnd) { path = false; clipping = false }
                operands.clear(); instructions++
            }
            require(parser.parseNextToken() == null && operands.isEmpty())
            require(!text && !path && !clipping && marked == 0 && compatibility == 0 && graphics.isEmpty())
            require(kind != PdfContentKind.GLYPH || instructions > 0)
        } finally { parser.close() }
    }
}
