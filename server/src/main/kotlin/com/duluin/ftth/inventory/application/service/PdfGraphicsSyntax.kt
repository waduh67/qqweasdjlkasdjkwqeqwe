package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.*

internal data class PdfColourSpace(val components: Int, val pattern: Boolean = false, private val inherited: PdfInheritedColour? = null) {
    fun constrain(components: Int, pattern: Boolean): PdfColourSpace = inherited?.constrain(PdfColourSpace(components, pattern)) ?: this
    companion object { fun inherited() = PdfColourSpace(0, inherited = PdfInheritedColour()) }
}

internal class PdfInheritedColour {
    private var requirement: PdfColourSpace? = null
    fun constrain(value: PdfColourSpace): PdfColourSpace {
        require(requirement == null || requirement == value)
        requirement = value
        return value
    }
}

internal data class PdfPoint(val x: Double, val y: Double) { init { require(x.isFinite() && y.isFinite()) } }
internal data class PdfTransform(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
    val d: Double = 1.0, val e: Double = 0.0, val f: Double = 0.0) {
    init { require(listOf(a, b, c, d, e, f).all(Double::isFinite)) }
    fun concatenate(next: PdfTransform) = PdfTransform(a * next.a + c * next.b, b * next.a + d * next.b,
        a * next.c + c * next.d, b * next.c + d * next.d, a * next.e + c * next.f + e, b * next.e + d * next.f + f)
    fun point(x: Double, y: Double) = PdfPoint(a * x + c * y + e, b * x + d * y + f)
    companion object {
        fun from(values: List<COSBase>): PdfTransform {
            require(values.size == 6 && values.all { it is COSNumber && it.floatValue().isFinite() })
            val numbers = values.map { (it as COSNumber).floatValue().toDouble() }
            return PdfTransform(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5])
        }
    }
}

internal data class PdfPathCommand(val operator: String, val operands: List<Double>, val transform: PdfTransform)
internal sealed interface PdfClipBoundary {
    data class Box(val corners: List<PdfPoint>) : PdfClipBoundary
    data class Path(val commands: List<PdfPathCommand>, val evenOdd: Boolean) : PdfClipBoundary
}
internal data class PdfClipSyntax(val previous: PdfClipSyntax?, val boundary: PdfClipBoundary)
internal data class PdfExtendedSyntax(val previous: PdfExtendedSyntax?, val dictionary: COSDictionary)
internal data class PdfTextSyntax(val font: COSDictionary? = null, val fontSize: Double = 0.0, val characterSpacing: Double = 0.0,
    val wordSpacing: Double = 0.0, val horizontalScale: Double = 100.0, val leading: Double = 0.0, val rise: Double = 0.0, val mode: Int = 0)
internal data class PdfLineSyntax(val width: Double = 1.0, val cap: Int = 0, val join: Int = 0, val miter: Double = 10.0,
    val dash: List<Double> = emptyList(), val dashPhase: Double = 0.0, val flatness: Double = 1.0, val intent: String = "RelativeColorimetric")

internal data class PdfGraphicsSyntax(val stroke: PdfColourSpace = PdfColourSpace(1), val fill: PdfColourSpace = PdfColourSpace(1),
    val strokeValues: List<COSBase>? = null, val fillValues: List<COSBase>? = null, val transform: PdfTransform = PdfTransform(),
    val clip: PdfClipSyntax? = null, val text: PdfTextSyntax = PdfTextSyntax(), val line: PdfLineSyntax = PdfLineSyntax(),
    val extended: PdfExtendedSyntax? = null) {
    fun enterForm(form: COSStream): PdfGraphicsSyntax {
        val type = form.getDictionaryObject(COSName.FORMTYPE)
        require(type == null || type is COSInteger && type.intValue() == 1)
        val matrix = form.getDictionaryObject(COSName.MATRIX)
        require(matrix == null || matrix is COSArray)
        val next = transform.concatenate(if (matrix == null) PdfTransform() else PdfTransform.from(matrix.toList()))
        val bounds = form.getDictionaryObject(COSName.BBOX) as? COSArray ?: error("Form bounding box required")
        require(bounds.size() == 4 && bounds.all { it is COSNumber && it.floatValue().isFinite() })
        val numbers = bounds.map { (it as COSNumber).floatValue().toDouble() }
        val box = PdfClipBoundary.Box(listOf(next.point(numbers[0], numbers[1]), next.point(numbers[2], numbers[1]),
            next.point(numbers[2], numbers[3]), next.point(numbers[0], numbers[3])))
        return copy(transform = next, clip = PdfClipSyntax(clip, box))
    }
    companion object { fun detached() = PdfGraphicsSyntax(stroke = PdfColourSpace.inherited(), fill = PdfColourSpace.inherited()) }
}
