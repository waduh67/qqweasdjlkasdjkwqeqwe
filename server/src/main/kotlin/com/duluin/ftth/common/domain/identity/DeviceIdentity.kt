package com.duluin.ftth.common.domain.identity

import com.duluin.ftth.common.domain.error.ValidationException
import java.util.Locale

class SerialIdentity private constructor(val raw: String, val canonical: String) {
    override fun equals(other: Any?): Boolean = other is SerialIdentity && canonical == other.canonical
    override fun hashCode(): Int = canonical.hashCode()
    override fun toString(): String = canonical

    companion object {
        fun parse(raw: String): SerialIdentity {
            val canonical = raw.trim().uppercase(Locale.ROOT)
            if (canonical.isEmpty()) throw ValidationException("Serial perangkat wajib diisi")
            return SerialIdentity(raw, canonical)
        }
    }
}

class MacIdentity private constructor(val raw: String, val canonical: String) {
    override fun equals(other: Any?): Boolean = other is MacIdentity && canonical == other.canonical
    override fun hashCode(): Int = canonical.hashCode()
    override fun toString(): String = canonical

    companion object {
        private val MAC = Regex("(?:[0-9A-Fa-f]{12}|(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|(?:[0-9A-Fa-f]{4}\\.){2}[0-9A-Fa-f]{4})")

        fun parse(raw: String): MacIdentity {
            val input = raw.trim()
            if (!MAC.matches(input)) throw ValidationException("MAC perangkat harus berisi tepat 12 digit hex")
            val digits = input.filter { it != ':' && it != '-' && it != '.' }.uppercase(Locale.ROOT)
            return MacIdentity(raw, digits.chunked(2).joinToString(":"))
        }
    }
}
