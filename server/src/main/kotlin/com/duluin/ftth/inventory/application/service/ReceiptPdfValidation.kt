package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdfparser.PDFParser
import org.apache.pdfbox.pdmodel.PDResources
import java.util.Collections
import java.util.IdentityHashMap

internal object ReceiptPdfValidation {
    private val activeNames = setOf("JavaScript", "JS", "OpenAction", "AA", "AcroForm", "XFA", "EmbeddedFiles", "EF", "RichMedia",
        "Launch", "SubmitForm", "ImportData", "GoToR", "GoToE", "Rendition", "Movie", "Sound", "URI")

    fun validate(bytes: ByteArray) {
        require(Regex("^%PDF-(1\\.[0-7]|2\\.0)[\\r\\n]").containsMatchIn(bytes.take(10).toByteArray().toString(Charsets.US_ASCII)))
        val ending = bytes.takeLast(2048).toByteArray().toString(Charsets.ISO_8859_1)
        require(Regex("startxref[\\r\\n ]+[0-9]+[\\r\\n ]+%%EOF[\\r\\n\\t ]*$").containsMatchIn(ending))
        RandomAccessReadBuffer(bytes).use { source ->
            PDFParser(source).parse(false).use { document ->
                require(!document.isEncrypted && document.numberOfPages in 1..100)
                val budget = PdfSyntaxBudget()
                val forms = PdfFormInvocations(budget)
                PdfFileTopology.validate(bytes, document, budget)
                val contentStreams = mutableListOf<Triple<COSStream, PDResources?, PdfContentKind>>()
                val resourcesByStream = PdfContentResources(document.pages.mapNotNull { it.resources })
                val pending = ArrayDeque<COSBase>()
                pending.add(document.document.trailer)
                require(document.document.xrefTable.size <= 50000)
                document.document.xrefTable.keys.forEach { pending.add(document.document.getObjectFromPool(it)) }
                val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                while (pending.isNotEmpty()) {
                    val value = pending.removeFirst()
                    if (!visited.add(value)) continue
                    require(visited.size <= 100000)
                    when (value) {
                        is COSObject -> value.`object`?.let(pending::add)
                        is COSDictionary -> {
                            val resources = value.getCOSDictionary(COSName.RESOURCES)?.let(::PDResources)
                            if (value is COSStream) {
                                require(!value.containsKey(COSName.F) && !value.containsKey(COSName.F_FILTER) && !value.containsKey(COSName.F_DECODE_PARMS))
                                if (value.getNameAsString(COSName.SUBTYPE) == "Form") contentStreams += Triple(value, resources, PdfContentKind.FORM)
                                if (value.getInt(COSName.PATTERN_TYPE) == 1) contentStreams += Triple(value, resources,
                                    if (value.getInt(COSName.PAINT_TYPE) == 2) PdfContentKind.UNCOLOURED_PATTERN else PdfContentKind.FORM)
                                if (value.getNameAsString(COSName.SUBTYPE) == "Image") {
                                    val width = value.getInt(COSName.WIDTH); val height = value.getInt(COSName.HEIGHT)
                                    require(width in 1..10000 && height in 1..10000 && width.toLong() * height <= 25000000)
                                }
                            }
                            if (value.getNameAsString(COSName.SUBTYPE) == "Type3") value.getCOSDictionary(COSName.CHAR_PROCS)?.let { glyphs ->
                                glyphs.keySet().forEach { key -> contentStreams += Triple(glyphs.getDictionaryObject(key) as? COSStream ?: error("Glyph stream required"), resources, PdfContentKind.GLYPH) }
                            }
                            value.keySet().forEach { key ->
                                require(key.name !in activeNames)
                                value.getItem(key)?.let(pending::add)
                            }
                        }
                        is COSArray -> value.forEach(pending::add)
                        is COSName -> require(value.name !in activeNames)
                    }
                }
                for (page in document.pages) {
                    val content = page.contents.use { it.readNBytes(16777217) }
                    budget.decoded(content.size)
                    PdfContentSyntax.validate(content, PdfContentContext(page.resources, PdfGraphicsSyntax(), PdfContentKind.PAGE, forms), budget)
                }
                for ((stream, resources, kind) in contentStreams) {
                    if (stream.getNameAsString(COSName.SUBTYPE) == "Form" && forms.wasInvoked(stream)) continue
                    resourcesByStream.contexts(stream, resources).forEach { effective -> forms.detached(stream, effective, kind) }
                }
            }
        }
    }
}
