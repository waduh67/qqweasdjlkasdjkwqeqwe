package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDResources
import java.util.Collections
import java.util.IdentityHashMap

internal class PdfContentResources(roots: List<PDResources>) {
    private val inherited = IdentityHashMap<COSStream, MutableList<PDResources>>()
    init {
        val pending = ArrayDeque(roots)
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun register(stream: COSStream, parent: PDResources) {
            val effective = stream.getCOSDictionary(COSName.RESOURCES)?.let(::PDResources) ?: parent
            val contexts = inherited.getOrPut(stream) { mutableListOf() }
            if (contexts.none { it.cosObject === effective.cosObject }) contexts.add(effective)
            pending.add(effective)
        }
        while (pending.isNotEmpty()) {
            val resources = pending.removeFirst()
            if (!visited.add(resources.cosObject)) continue
            require(visited.size <= 100000)
            resources.cosObject.getCOSDictionary(COSName.XOBJECT)?.let { entries -> entries.keySet().forEach { key ->
                val stream = entries.getDictionaryObject(key) as? COSStream ?: error("XObject stream required")
                require(stream.getNameAsString(COSName.SUBTYPE) in setOf("Form", "Image"))
                if (stream.getNameAsString(COSName.SUBTYPE) == "Form") register(stream, resources)
            } }
            resources.cosObject.getCOSDictionary(COSName.PATTERN)?.let { entries -> entries.keySet().forEach { key ->
                val pattern = entries.getDictionaryObject(key) as? COSDictionary ?: error("Pattern dictionary required")
                require(pattern.getInt(COSName.PATTERN_TYPE) in 1..2)
                if (pattern.getInt(COSName.PATTERN_TYPE) == 1) {
                    require(pattern is COSStream && pattern.getInt(COSName.PAINT_TYPE) in 1..2)
                    register(pattern, resources)
                }
            } }
            resources.cosObject.getCOSDictionary(COSName.FONT)?.let { entries -> entries.keySet().forEach { key ->
                val font = entries.getDictionaryObject(key) as? COSDictionary ?: error("Font dictionary required")
                if (font.getNameAsString(COSName.SUBTYPE) == "Type3") {
                    val parent = font.getCOSDictionary(COSName.RESOURCES)?.let(::PDResources) ?: resources
                    val glyphs = font.getCOSDictionary(COSName.CHAR_PROCS) ?: error("Type3 glyphs required")
                    glyphs.keySet().forEach { glyph -> register(glyphs.getDictionaryObject(glyph) as? COSStream ?: error("Glyph stream required"), parent) }
                }
            } }
        }
    }
    fun contexts(stream: COSStream, fallback: PDResources?): List<PDResources?> = inherited[stream] ?: listOf(fallback)

    companion object {
        fun validateOperator(operator: String, operands: List<COSBase>, resources: PDResources?) {
            fun lookup(category: COSName, name: COSName): COSDictionary =
                requireNotNull(resources).cosObject.getCOSDictionary(category)?.getDictionaryObject(name) as? COSDictionary ?: error("PDF resource is missing")
            when (operator) {
                "Tf" -> require(lookup(COSName.FONT, operands[0] as COSName).getNameAsString(COSName.SUBTYPE) in setOf("Type0", "Type1", "MMType1", "TrueType", "Type3"))
                "gs" -> lookup(COSName.EXT_G_STATE, operands[0] as COSName)
                "Do" -> { val resource = lookup(COSName.XOBJECT, operands[0] as COSName); require(resource is COSStream && resource.getNameAsString(COSName.SUBTYPE) in setOf("Form", "Image")) }
                "sh" -> require(lookup(COSName.SHADING, operands[0] as COSName).getInt(COSName.SHADING_TYPE) in 1..7)
                "DP", "BDC" -> if (operands[1] is COSName) lookup(COSName.PROPERTIES, operands[1] as COSName)
                "SCN", "scn" -> if (operands.lastOrNull() is COSName) require(lookup(COSName.PATTERN, operands.last() as COSName).getInt(COSName.PATTERN_TYPE) in 1..2)
            }
        }
    }
}
