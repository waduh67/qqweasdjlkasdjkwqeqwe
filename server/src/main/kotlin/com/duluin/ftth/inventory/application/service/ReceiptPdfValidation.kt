package com.duluin.ftth.inventory.application.service

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSObject
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdfparser.PDFParser
import org.apache.pdfbox.pdfparser.PDFStreamParser
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
                        is COSDictionary -> value.keySet().forEach { key ->
                            require(key.name !in activeNames)
                            value.getItem(key)?.let(pending::add)
                        }
                        is COSArray -> value.forEach(pending::add)
                        is COSName -> require(value.name !in activeNames)
                    }
                }
                var tokens = 0
                var decoded = 0L
                for (page in document.pages) {
                    val content = page.contents.use { it.readNBytes(16777217) }
                    decoded += content.size
                    require(content.size <= 16777216 && decoded <= 67108864)
                    val parser = PDFStreamParser(content)
                    try { while (parser.parseNextToken() != null) { tokens++; require(tokens <= 100000) } }
                    finally { parser.close() }
                }
            }
        }
    }
}
