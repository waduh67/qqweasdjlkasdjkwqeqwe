package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDResources

internal object PdfGraphicsOperators {
    fun apply(name: String, operands: List<COSBase>, state: PdfGraphicsSyntax, resources: PDResources?): PdfGraphicsSyntax {
        fun number(index: Int = 0) = (operands[index] as COSNumber).floatValue().toDouble()
        return when (name) {
            "cm" -> state.copy(transform = state.transform.concatenate(PdfTransform.from(operands)))
            "w" -> state.copy(line = state.line.copy(width = number()))
            "J" -> state.copy(line = state.line.copy(cap = number().toInt()))
            "j" -> state.copy(line = state.line.copy(join = number().toInt()))
            "M" -> state.copy(line = state.line.copy(miter = number()))
            "i" -> state.copy(line = state.line.copy(flatness = number()))
            "ri" -> state.copy(line = state.line.copy(intent = (operands[0] as COSName).name))
            "d" -> state.copy(line = state.line.copy(dash = (operands[0] as COSArray).map { (it as COSNumber).floatValue().toDouble() }, dashPhase = number(1)))
            "Tf" -> state.copy(text = state.text.copy(font = requireNotNull(resources).cosObject.getCOSDictionary(COSName.FONT)
                ?.getDictionaryObject(operands[0] as COSName) as COSDictionary, fontSize = number(1)))
            "Tc" -> state.copy(text = state.text.copy(characterSpacing = number()))
            "Tw" -> state.copy(text = state.text.copy(wordSpacing = number()))
            "Tz" -> state.copy(text = state.text.copy(horizontalScale = number()))
            "TL" -> state.copy(text = state.text.copy(leading = number()))
            "TD" -> state.copy(text = state.text.copy(leading = -number(1)))
            "Ts" -> state.copy(text = state.text.copy(rise = number()))
            "Tr" -> state.copy(text = state.text.copy(mode = number().toInt()))
            "\"" -> state.copy(text = state.text.copy(wordSpacing = number(), characterSpacing = number(1)))
            "gs" -> {
                val dictionary = requireNotNull(resources).cosObject.getCOSDictionary(COSName.EXT_G_STATE)
                    ?.getDictionaryObject(operands[0] as COSName) as COSDictionary
                var changed = state
                for ((key, operator) in listOf("LW" to "w", "LC" to "J", "LJ" to "j", "ML" to "M", "FL" to "i", "RI" to "ri")) {
                    dictionary.getDictionaryObject(COSName.getPDFName(key))?.let { changed = PdfOperatorSyntax.validate(operator, listOf(it), changed, resources) }
                }
                dictionary.getDictionaryObject(COSName.D)?.let {
                    require(it is COSArray)
                    changed = PdfOperatorSyntax.validate("d", it.toList(), changed, resources)
                }
                dictionary.getDictionaryObject(COSName.FONT)?.let {
                    require(it is COSArray && it.size() == 2)
                    val font = it.getObject(0) as? COSDictionary ?: error("Extended text font required")
                    val size = it.getObject(1) as? COSNumber ?: error("Extended font size required")
                    require(size.floatValue().isFinite())
                    changed = changed.copy(text = changed.text.copy(font = font, fontSize = size.floatValue().toDouble()))
                }
                changed.copy(extended = PdfExtendedSyntax(state.extended, dictionary))
            }
            else -> state
        }
    }
}
