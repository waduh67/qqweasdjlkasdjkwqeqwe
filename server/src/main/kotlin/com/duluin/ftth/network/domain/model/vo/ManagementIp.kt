package com.duluin.ftth.network.domain.model.vo

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Alamat IP manajemen perangkat. Divalidasi sebagai literal IP — bukan hostname —
 * karena collector harus bisa menghubunginya tanpa bergantung pada resolusi DNS
 * di jaringan ISP.
 */
@JvmInline
value class ManagementIp private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$""")
        private val IPV6 = Regex("""^[0-9A-Fa-f:]{2,45}$""")

        fun of(value: String): ManagementIp {
            val trimmed = value.trim()
            val valid = IPV4.matches(trimmed) || (trimmed.contains(':') && IPV6.matches(trimmed))
            if (!valid) throw ValidationException("IP manajemen '$value' bukan alamat IP yang valid")
            return ManagementIp(trimmed)
        }

        fun ofNullable(value: String?): ManagementIp? =
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(::of)
    }
}
