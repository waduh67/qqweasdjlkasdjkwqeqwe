package com.duluin.ftth.inventory.application.service

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

internal object ReceiptImageValidation {
    fun validate(bytes: ByteArray, format: String) {
        if (format == "png") png(bytes) else jpeg(bytes)
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { source ->
            val readers = ImageIO.getImageReaders(source)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                require(reader.formatName.equals(format, ignoreCase = true))
                reader.input = source
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width in 1..10000 && height in 1..10000 && width.toLong() * height <= 25000000)
                var warned = false
                reader.addIIOReadWarningListener { _, _ -> warned = true }
                val image = reader.read(0)
                require(!warned && image.width == width && image.height == height)
            } finally { reader.dispose() }
        }
    }

    private fun png(bytes: ByteArray) {
        require(bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        val source = ByteBuffer.wrap(bytes).position(8)
        var first = true
        var data = false
        while (source.hasRemaining()) {
            require(source.remaining() >= 12)
            val length = source.int
            require(length >= 0 && length <= source.remaining() - 8)
            val start = source.position()
            val name = ByteArray(4).also(source::get).toString(Charsets.US_ASCII)
            require(name.all { it in 'A'..'Z' || it in 'a'..'z' })
            require(!first || name == "IHDR")
            require(name != "IHDR" || first && length == 13)
            require(name[0] !in 'A'..'Z' || name in setOf("IHDR", "PLTE", "IDAT", "IEND"))
            val checksum = CRC32().apply { update(bytes, start, length + 4) }.value
            source.position(source.position() + length)
            require(checksum == source.int.toLong().and(0xffffffffL))
            if (name == "IDAT") data = true
            if (name == "IEND") { require(data && length == 0 && !source.hasRemaining()); return }
            first = false
        }
        throw IllegalArgumentException("Incomplete image")
    }

    private fun jpeg(bytes: ByteArray) {
        require(bytes.size >= 4 && bytes[0] == (-1).toByte() && bytes[1] == (-40).toByte())
        var position = 2
        var entropy = false
        var scan = false
        fun octet(index: Int) = bytes[index].toInt().and(255)
        while (position < bytes.size) {
            if (entropy) {
                while (position < bytes.size && octet(position) != 255) position++
                require(position + 1 < bytes.size)
                if (octet(position + 1) == 0 || octet(position + 1) in 208..215) { position += 2; continue }
                entropy = false
            }
            require(octet(position++) == 255)
            while (position < bytes.size && octet(position) == 255) position++
            require(position < bytes.size)
            val marker = octet(position++)
            if (marker == 217) { require(scan && position == bytes.size); return }
            require(marker != 0 && marker != 216 && marker !in 208..215 && position + 1 < bytes.size)
            val length = octet(position) * 256 + octet(position + 1)
            require(length >= 2 && length <= bytes.size - position)
            position += length
            if (marker == 218) { entropy = true; scan = true }
        }
        throw IllegalArgumentException("Incomplete image")
    }
}
