package com.duluin.ftth.common.domain

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Generator UUIDv7 (time-ordered) — dipakai domain untuk membuat identitas agregat.
 *
 * Nilainya menaik secara kronologis sehingga insert bersifat append-friendly di
 * index B-tree Postgres dan bisa di-sort berdasarkan waktu pembuatan. Murni Kotlin,
 * tanpa dependency framework, sehingga aman berada di lapisan domain.
 */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(): UUID {
        val now = Instant.now().toEpochMilli()
        val rand = ByteArray(10).also(random::nextBytes)

        // unix_ts_ms(48) | version(4)=0111 | rand_a(12)
        val msb = (now shl 16) or
            0x7000L or
            ((rand[0].toLong() and 0x0f) shl 8) or
            (rand[1].toLong() and 0xff)

        // variant(2)=10 | rand_b(62)
        var lsb = 0L
        for (i in 2 until 10) lsb = (lsb shl 8) or (rand[i].toLong() and 0xff)
        lsb = (lsb and 0x3fffffffffffffffL) or Long.MIN_VALUE

        return UUID(msb, lsb)
    }
}
