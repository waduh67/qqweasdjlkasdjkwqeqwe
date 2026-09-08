package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.pdmodel.PDDocument

internal object PdfFileTopology {
    fun validate(bytes: ByteArray, document: PDDocument, budget: PdfSyntaxBudget) {
        val cursor = PdfLexicalCursor(bytes)
        require(cursor.matches("%PDF-"))
        while (cursor.peek() !in setOf(-1, 10, 13)) cursor.position++
        cursor.eol()
        val streams = PdfTopologyStreams(bytes, document, budget)
        val objects = linkedMapOf<Int, PdfPhysicalObject>()
        val sections = linkedMapOf<Int, PdfXrefSection>()
        val footers = mutableListOf<Pair<Int, Int>>()
        while (!cursor.end()) {
            val start = cursor.position
            when {
                cursor.matches("xref") -> {
                    cursor.expect("xref")
                    require(sections.put(start, PdfXrefSyntax.table(cursor, start)) == null)
                }
                cursor.matches("startxref") -> {
                    cursor.expect("startxref")
                    val target = cursor.unsigned().also { require(it < bytes.size) }.toInt()
                    val section = sections[target] ?: error("startxref has no xref section")
                    require(PdfLexicalCursor(bytes, section.end, start).end())
                    cursor.whitespace()
                    require(cursor.matches("%%EOF")); cursor.position += 5
                    require(cursor.peek() < 0 || pdfWhitespace(cursor.peek()))
                    footers += target to cursor.position
                }
                else -> {
                    val number = cursor.unsigned().also { require(it > 0) }
                    val generation = cursor.unsigned().also { require(it <= 65535) }.toInt()
                    cursor.expect("obj")
                    val value = cursor.value()
                    cursor.trivia()
                    var data: IntRange? = null
                    if (cursor.matches("stream")) {
                        require(value is PdfDictionary)
                        cursor.expect("stream"); cursor.eol()
                        val length = streams.length(value.values["Length"])
                        val dataStart = cursor.position
                        require(length <= bytes.size - dataStart)
                        data = dataStart until dataStart + length
                        cursor.position += length
                        if (cursor.peek() in setOf(10, 13)) cursor.eol()
                        require(cursor.matches("endstream"))
                        cursor.expect("endstream")
                    }
                    cursor.expect("endobj")
                    val record = PdfPhysicalObject(PdfReference(number, generation), start, cursor.position, value, data)
                    require(objects.put(start, record) == null && objects.size <= 50000)
                    if (value is PdfDictionary && value.name("Type") == "XRef") {
                        require(data != null)
                        sections[start] = PdfXrefSyntax.stream(record, streams.decode(record))
                    }
                }
            }
            require(sections.size <= 128 && footers.size <= 128)
        }
        require(footers.isNotEmpty() && bytes.drop(footers.last().second).all { pdfWhitespace(it.toInt().and(255)) })
        require(footers.last().first.toLong() == document.document.startXref)
        validateReferences(objects, sections, footers.map { it.first }, document, streams)
    }

    private fun validateReferences(objects: Map<Int, PdfPhysicalObject>, sections: Map<Int, PdfXrefSection>, footers: List<Int>, document: PDDocument, streams: PdfTopologyStreams) {
        val reachable = mutableSetOf<Int>()
        fun visit(offset: Int) {
            if (!reachable.add(offset)) return
            val section = sections[offset] ?: error("Missing xref revision")
            section.previous?.let(::visit)
            section.hybrid?.let(::visit)
        }
        visit(footers.last())
        require(reachable == sections.keys && footers.all { it in reachable })
        require(sections.values.sumOf { it.entries.size.toLong() } <= 100000)
        fun lookup(offset: Int, number: Long): PdfXrefEntry? {
            val section = sections.getValue(offset)
            return section.hybrid?.let { sections.getValue(it).entries[number] } ?: section.entries[number] ?: section.previous?.let { lookup(it, number) }
        }
        val covered = mutableSetOf<Int>()
        val members = mutableMapOf<Int, List<Long>>()
        sections.values.forEach { section -> section.entries.forEach { (number, entry) ->
            when (entry.type) {
                0 -> Unit
                1 -> {
                    require(entry.location in 0 until section.offset.toLong() || entry.location == section.offset.toLong())
                    val target = objects[entry.location.toInt()] ?: error("Xref does not address an object header")
                    require(target.key == PdfReference(number, entry.generationOrIndex))
                    covered += target.start
                }
                2 -> {
                    val container = lookup(section.offset, entry.location) ?: error("Missing object stream reference")
                    require(container.type == 1 && container.location <= Int.MAX_VALUE)
                    val record = objects[container.location.toInt()] ?: error("Missing physical object stream")
                    require((record.value as? PdfDictionary)?.name("Type") == "ObjStm")
                    val list = members.getOrPut(record.start) { streams.members(record) }
                    require(entry.generationOrIndex in list.indices && list[entry.generationOrIndex] == number)
                }
                else -> error("Invalid xref type")
            }
        } }
        require(covered == objects.keys)
        document.document.xrefTable.forEach { (key, location) ->
            val entry = lookup(footers.last(), key.number) ?: error("Resolved xref was not declared")
            require(entry.type != 0)
            val expected = if (entry.type == 2) -entry.location else entry.location
            require(location == expected && key.generation == if (entry.type == 2) 0 else entry.generationOrIndex)
        }
        objects.values.filter { (it.value as? PdfDictionary)?.name("Type") == "ObjStm" }.forEach { members.getOrPut(it.start) { streams.members(it) } }
    }
}
