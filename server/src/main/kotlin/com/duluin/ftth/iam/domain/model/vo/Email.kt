package com.duluin.ftth.iam.domain.model.vo

import com.duluin.ftth.common.domain.error.ValidationException

/** Value object email — dinormalisasi lowercase & divalidasi format saat dibuat. */
@JvmInline
value class Email private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun of(raw: String): Email {
            val normalized = raw.trim().lowercase()
            if (!PATTERN.matches(normalized)) throw ValidationException("Format email tidak valid: $raw")
            return Email(normalized)
        }
    }
}
