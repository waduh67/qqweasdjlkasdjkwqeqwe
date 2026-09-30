package com.duluin.ftth.inventory.application.service

internal sealed interface PdfLexicalValue
internal data class PdfNumber(val raw: String) : PdfLexicalValue {
    fun integer(): Long = requireNotNull(raw.toLongOrNull())
}
internal data class PdfName(val value: String) : PdfLexicalValue
internal data object PdfText : PdfLexicalValue
internal data class PdfBoolean(val value: Boolean) : PdfLexicalValue
internal data object PdfNull : PdfLexicalValue
internal data class PdfArray(val values: List<PdfLexicalValue>) : PdfLexicalValue
internal data class PdfDictionary(val values: Map<String, PdfLexicalValue>) : PdfLexicalValue {
    fun integer(name: String): Long = (values[name] as? PdfNumber)?.integer() ?: error("PDF integer required")
    fun name(name: String): String? = (values[name] as? PdfName)?.value
}
internal data class PdfReference(val number: Long, val generation: Int) : PdfLexicalValue

internal fun pdfWhitespace(value: Int) = when (value) { 0, 9, 10, 12, 13, 32 -> true; else -> false }
internal fun pdfDelimiter(value: Int) = when (value) { 40, 41, 60, 62, 91, 93, 123, 125, 47, 37 -> true; else -> false }
