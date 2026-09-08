package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.*
import org.apache.pdfbox.pdmodel.PDDocument

internal data class PdfPhysicalObject(val key: PdfReference, val start: Int, val end: Int, val value: PdfLexicalValue, val data: IntRange?)
internal class PdfSyntaxBudget {
    private var decoded = 0L
    private var tokens = 0
    fun decoded(size: Int) { decoded += size; require(size <= 16777216 && decoded <= 67108864) }
    fun token() { require(++tokens <= 100000) }
}

internal class PdfTopologyStreams(private val bytes: ByteArray, private val document: PDDocument, private val budget: PdfSyntaxBudget) {
    fun length(value: PdfLexicalValue?): Int {
        val number = when (value) {
            is PdfNumber -> value.integer()
            is PdfReference -> (document.document.getObjectFromPool(COSObjectKey(value.number, value.generation)).`object` as? COSInteger)?.longValue()
                ?: error("PDF stream length reference is not an integer")
            else -> error("Missing PDF stream length")
        }
        require(number in 0..bytes.size.toLong())
        return number.toInt()
    }

    fun decode(record: PdfPhysicalObject): ByteArray {
        val dictionary = record.value as PdfDictionary
        val data = requireNotNull(record.data)
        return COSStream().use { temporary ->
            for (name in listOf("Filter", "DecodeParms")) dictionary.values[name]?.let { temporary.setItem(COSName.getPDFName(name), cos(it)) }
            temporary.createRawOutputStream().use { it.write(bytes, data.first, data.count()) }
            temporary.createInputStream().use { it.readNBytes(16777217) }.also { budget.decoded(it.size) }
        }
    }

    fun members(record: PdfPhysicalObject): List<Long> {
        val dictionary = record.value as PdfDictionary
        val count = dictionary.integer("N").also { require(it in 1..50000) }.toInt()
        val data = decode(record)
        val first = dictionary.integer("First").also { require(it in 0..data.size.toLong()) }.toInt()
        val header = PdfLexicalCursor(data, limit = first)
        val members = (0 until count).map { header.unsigned() to header.unsigned().also { require(it <= data.size - first) }.toInt() }
        require(header.end() && members.map { it.first }.distinct().size == count && members.all { it.first > 0 })
        require(PdfLexicalCursor(data, first, first + members.first().second).end())
        members.forEachIndexed { index, member ->
            val end = if (index + 1 < members.size) first + members[index + 1].second else data.size
            val start = first + member.second
            require(start < end)
            val cursor = PdfLexicalCursor(data, start, end)
            cursor.value()
            require(cursor.end())
        }
        return members.map { it.first }
    }

    private fun cos(value: PdfLexicalValue): COSBase = when (value) {
        is PdfNumber -> value.raw.toLongOrNull()?.let(COSInteger::get) ?: COSFloat(value.raw)
        is PdfName -> COSName.getPDFName(value.value)
        is PdfArray -> COSArray().apply { value.values.forEach { add(cos(it)) } }
        is PdfDictionary -> COSDictionary().apply { value.values.forEach { (key, item) -> setItem(COSName.getPDFName(key), cos(item)) } }
        is PdfBoolean -> COSBoolean.getBoolean(value.value)
        PdfNull -> COSNull.NULL
        is PdfReference -> requireNotNull(document.document.getObjectFromPool(COSObjectKey(value.number, value.generation)).`object`)
        PdfText -> error("String is not a stream decoding parameter")
    }
}
