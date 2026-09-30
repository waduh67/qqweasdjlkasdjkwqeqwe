package com.duluin.ftth.inventory.application.service

internal class PdfLexicalCursor(val bytes: ByteArray, var position: Int = 0, val limit: Int = bytes.size) {
    private var values = 0
    init { require(position in 0..limit && limit <= bytes.size) }
    fun peek(): Int = if (position < limit) bytes[position].toInt().and(255) else -1
    fun matches(text: String): Boolean = position + text.length <= limit && text.indices.all { bytes[position + it].toInt().and(255) == text[it].code }
    fun keyword(text: String): Boolean = matches(text) && (position + text.length == limit ||
        pdfWhitespace(bytes[position + text.length].toInt().and(255)) || pdfDelimiter(bytes[position + text.length].toInt().and(255)))
    fun whitespace() { while (pdfWhitespace(peek())) position++ }
    fun trivia() {
        while (true) {
            whitespace()
            if (peek() != '%'.code) return
            while (peek() !in setOf(-1, 10, 13)) position++
        }
    }
    fun end(): Boolean { trivia(); return position == limit }
    fun word(): String {
        trivia()
        val start = position
        while (peek() >= 0 && !pdfWhitespace(peek()) && !pdfDelimiter(peek())) position++
        require(position > start) { "PDF keyword required" }
        return bytes.copyOfRange(start, position).toString(Charsets.ISO_8859_1)
    }
    fun expect(text: String) { require(word() == text) { "Unexpected PDF keyword" } }
    fun unsigned(): Long = word().let { require(it.matches(Regex("[0-9]{1,10}"))); requireNotNull(it.toLongOrNull()) }
    fun eol() {
        require(peek() in setOf(10, 13))
        if (peek() == 13) { position++; if (peek() == 10) position++ } else position++
    }
    fun value(references: Boolean = true, depth: Int = 0): PdfLexicalValue {
        require(depth < 64 && ++values <= 100000)
        trivia()
        return when (peek()) {
            '/'.code -> name()
            '('.code -> { literal(); PdfText }
            '<'.code -> if (matches("<<")) dictionary(references, depth) else { hexadecimal(); PdfText }
            '['.code -> {
                position++
                val entries = mutableListOf<PdfLexicalValue>()
                while (true) {
                    trivia(); require(peek() >= 0)
                    if (peek() == ']'.code) { position++; break }
                    entries += value(references, depth + 1)
                }
                PdfArray(entries)
            }
            else -> scalar(references)
        }
    }
    private fun dictionary(references: Boolean, depth: Int): PdfDictionary {
        position += 2
        val entries = linkedMapOf<String, PdfLexicalValue>()
        while (true) {
            trivia(); require(peek() >= 0)
            if (matches(">>")) { position += 2; return PdfDictionary(entries) }
            require(peek() == '/'.code)
            val key = name().value
            require(key !in entries)
            entries[key] = value(references, depth + 1)
        }
    }
    private fun scalar(references: Boolean): PdfLexicalValue {
        val token = word()
        if (token in setOf("true", "false")) return PdfBoolean(token == "true")
        if (token == "null") return PdfNull
        require(token.length <= 128 && token.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")))
        require(token.toFloatOrNull()?.isFinite() == true)
        val after = position
        if (references && token.matches(Regex("[0-9]{1,10}"))) {
            trivia()
            if (peek() in '0'.code..'9'.code) {
                val generation = word().takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()
                trivia()
                if (generation != null && generation <= 65535 && matches("R") &&
                    (position + 1 == limit || pdfWhitespace(bytes[position + 1].toInt().and(255)) || pdfDelimiter(bytes[position + 1].toInt().and(255)))) {
                    position++
                    return PdfReference(token.toLong(), generation)
                }
            }
        }
        position = after
        return PdfNumber(token)
    }
    private fun name(): PdfName {
        require(peek() == '/'.code); position++
        val name = StringBuilder()
        while (peek() >= 0 && !pdfWhitespace(peek()) && !pdfDelimiter(peek())) {
            if (peek() == '#'.code) {
                position++
                val high = hexDigit(peek()); position++
                val low = hexDigit(peek()); position++
                name.append((high * 16 + low).toChar())
            } else name.append(peek().toChar()).also { position++ }
        }
        return PdfName(name.toString())
    }
    private fun literal() {
        position++
        var depth = 1
        while (depth > 0) {
            require(peek() >= 0)
            when (peek()) {
                '\\'.code -> {
                    position++; require(peek() >= 0)
                    if (peek() in setOf(10, 13)) eol()
                    else if (peek() in '0'.code..'7'.code) repeat(3) { if (peek() in '0'.code..'7'.code) position++ }
                    else position++
                }
                '('.code -> { depth++; require(depth <= 64); position++ }
                ')'.code -> { depth--; position++ }
                else -> position++
            }
        }
    }
    private fun hexadecimal() {
        position++
        while (true) {
            whitespace(); require(peek() >= 0)
            if (peek() == '>'.code) { position++; return }
            hexDigit(peek()); position++
        }
    }
    private fun hexDigit(value: Int): Int = when (value) {
        in '0'.code..'9'.code -> value - '0'.code
        in 'a'.code..'f'.code -> value - 'a'.code + 10
        in 'A'.code..'F'.code -> value - 'A'.code + 10
        else -> error("Invalid PDF hex digit")
    }
}
