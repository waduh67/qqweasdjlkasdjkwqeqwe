package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern

internal data class PdfColourSpace(val components: Int, val pattern: Boolean = false)
internal data class PdfGraphicsSyntax(val stroke: PdfColourSpace = PdfColourSpace(1), val fill: PdfColourSpace = PdfColourSpace(1))

internal object PdfOperatorSyntax {
    private val zero = setOf("q", "Q", "h", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*", "n", "W", "W*", "BT", "ET", "T*", "EMC", "BX", "EX")
    private val numbers = mapOf(1 to setOf("w", "J", "j", "M", "i", "G", "g", "Tc", "Tw", "Tz", "TL", "Tr", "Ts"),
        2 to setOf("m", "l", "Td", "TD", "d0"), 3 to setOf("RG", "rg"), 4 to setOf("v", "y", "re", "K", "k"), 6 to setOf("cm", "c", "Tm", "d1"))
    private val names = setOf("CS", "cs", "ri", "gs", "sh", "Do", "MP", "BMC")
    val operators = zero + numbers.values.flatten() + names + setOf("d", "Tf", "Tj", "'", "TJ", "\"", "DP", "BDC", "SC", "sc", "SCN", "scn", "BI", "ID", "EI")
    val colours = setOf("CS", "cs", "SC", "sc", "SCN", "scn", "G", "g", "RG", "rg", "K", "k", "sh")

    fun validate(name: String, operands: List<COSBase>, graphics: PdfGraphicsSyntax, resources: PDResources?): PdfGraphicsSyntax {
        when {
            name in zero -> require(operands.isEmpty())
            numbers.values.any { name in it } -> {
                val count = numbers.entries.single { name in it.value }.key
                require(operands.size == count && operands.all { it is COSNumber && it.floatValue().isFinite() })
            }
            name in names -> require(operands.size == 1 && operands[0] is COSName)
            name == "d" -> {
                require(operands.size == 2 && operands[0] is COSArray && operands[1] is COSNumber)
                val array = operands[0] as COSArray
                require(array.all { it is COSNumber && it.floatValue().isFinite() && it.floatValue() >= 0 })
                require(array.size() == 0 || array.any { (it as COSNumber).floatValue() > 0 })
            }
            name == "Tf" -> require(operands.size == 2 && operands[0] is COSName && operands[1] is COSNumber)
            name in setOf("Tj", "'") -> require(operands.size == 1 && operands[0] is COSString)
            name == "TJ" -> require(operands.size == 1 && operands[0] is COSArray && (operands[0] as COSArray).all { it is COSString || it is COSNumber })
            name == "\"" -> require(operands.size == 3 && operands[0] is COSNumber && operands[1] is COSNumber && operands[2] is COSString)
            name in setOf("DP", "BDC") -> require(operands.size == 2 && operands[0] is COSName && (operands[1] is COSName || operands[1] is COSDictionary))
            name in setOf("SC", "SCN", "sc", "scn") -> {
                val colour = if (name[0].isUpperCase()) graphics.stroke else graphics.fill
                val pattern = colour.pattern && name.endsWith("N", ignoreCase = true)
                require(!colour.pattern || pattern)
                require(operands.size == colour.components + if (pattern) 1 else 0)
                require(operands.take(colour.components).all { it is COSNumber })
                if (pattern) require(operands.last() is COSName)
            }
            else -> error("Unexpected PDF operator")
        }
        if (name in setOf("J", "j", "Tr")) {
            require(operands[0] is COSInteger)
            require((operands[0] as COSInteger).intValue() in 0..if (name == "Tr") 7 else 2)
        }
        if (name == "w") require((operands[0] as COSNumber).floatValue() >= 0)
        if (name == "M") require((operands[0] as COSNumber).floatValue() >= 1)
        if (name == "i") require((operands[0] as COSNumber).floatValue() in 0f..100f)
        if (name == "ri") require((operands[0] as COSName).name in setOf("AbsoluteColorimetric", "RelativeColorimetric", "Saturation", "Perceptual"))
        PdfContentResources.validateOperator(name, operands, resources)
        return when (name) {
            "CS" -> graphics.copy(stroke = colour((operands[0] as COSName), resources))
            "cs" -> graphics.copy(fill = colour((operands[0] as COSName), resources))
            "G" -> graphics.copy(stroke = PdfColourSpace(1))
            "g" -> graphics.copy(fill = PdfColourSpace(1))
            "RG" -> graphics.copy(stroke = PdfColourSpace(3))
            "rg" -> graphics.copy(fill = PdfColourSpace(3))
            "K" -> graphics.copy(stroke = PdfColourSpace(4))
            "k" -> graphics.copy(fill = PdfColourSpace(4))
            else -> graphics
        }
    }

    private fun colour(name: COSName, resources: PDResources?): PdfColourSpace = when (name.name) {
        "DeviceGray" -> PdfColourSpace(1)
        "DeviceRGB" -> PdfColourSpace(3)
        "DeviceCMYK" -> PdfColourSpace(4)
        "Pattern" -> PdfColourSpace(0, true)
        else -> {
            val colour = requireNotNull(resources).getColorSpace(name)
            if (colour is PDPattern) PdfColourSpace(colour.underlyingColorSpace?.numberOfComponents ?: 0, true)
            else PdfColourSpace(colour.numberOfComponents.also { require(it in 1..32) })
        }
    }
}
