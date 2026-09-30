package com.duluin.ftth.inventory.application.service

internal data class PdfXrefEntry(val type: Int, val location: Long, val generationOrIndex: Int)
internal data class PdfXrefSection(val offset: Int, val end: Int, val dictionary: PdfDictionary, val entries: Map<Long, PdfXrefEntry>) {
    val previous: Int? get() = pointer("Prev")
    val hybrid: Int? get() = pointer("XRefStm")
    private fun pointer(name: String): Int? = dictionary.values[name]?.let {
        require(it is PdfNumber)
        it.integer().also { address -> require(address in 0 until offset.toLong()) }.toInt()
    }
}

internal object PdfXrefSyntax {
    fun table(cursor: PdfLexicalCursor, start: Int): PdfXrefSection {
        val entries = linkedMapOf<Long, PdfXrefEntry>()
        while (true) {
            cursor.trivia()
            if (cursor.matches("trailer")) break
            val first = cursor.unsigned()
            val count = cursor.unsigned().also { require(it <= 50000 - entries.size) }.toInt()
            repeat(count) { index ->
                val offset = cursor.unsigned()
                val generation = cursor.unsigned().also { require(it <= 65535) }.toInt()
                val type = when (cursor.word()) { "n" -> 1; "f" -> 0; else -> error("Invalid xref entry") }
                require(entries.put(first + index, PdfXrefEntry(type, offset, generation)) == null)
            }
        }
        cursor.expect("trailer")
        val dictionary = cursor.value() as? PdfDictionary ?: error("Missing PDF trailer dictionary")
        val size = dictionary.integer("Size")
        require(size > 0 && entries.isNotEmpty() && entries.keys.all { it in 0 until size })
        return PdfXrefSection(start, cursor.position, dictionary, entries)
    }

    fun stream(record: PdfPhysicalObject, bytes: ByteArray): PdfXrefSection {
        val dictionary = record.value as PdfDictionary
        val widths = (dictionary.values["W"] as? PdfArray)?.values?.map { (it as? PdfNumber)?.integer() ?: error("Invalid xref width") }
            ?: error("Missing xref widths")
        require(widths.size == 3 && widths.all { it in 0..8 } && widths.sum() > 0)
        val size = dictionary.integer("Size").also { require(it > 0) }
        val indices = dictionary.values["Index"]?.let { index ->
            require(index is PdfArray)
            index.values.map { (it as? PdfNumber)?.integer() ?: error("Invalid xref index") }
        } ?: listOf(0L, size)
        require(indices.size % 2 == 0)
        var position = 0
        fun field(width: Long): Long {
            var value = 0L
            repeat(width.toInt()) {
                require(position < bytes.size && value <= Long.MAX_VALUE / 256)
                value = Math.addExact(value * 256, bytes[position++].toLong().and(255))
            }
            return value
        }
        val entries = linkedMapOf<Long, PdfXrefEntry>()
        indices.chunked(2).forEach { (first, count) ->
            require(first >= 0 && count >= 0 && count <= 50000 - entries.size && first <= size - count)
            repeat(count.toInt()) { index ->
                val type = if (widths[0] == 0L) 1L else field(widths[0])
                val offset = field(widths[1])
                val generation = field(widths[2])
                require(type in 0..2 && generation in 0..Int.MAX_VALUE.toLong())
                require(type == 2L || generation <= 65535)
                require(entries.put(first + index, PdfXrefEntry(type.toInt(), offset, generation.toInt())) == null)
            }
        }
        require(position == bytes.size)
        return PdfXrefSection(record.start, record.end, dictionary, entries)
    }
}
