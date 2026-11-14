package com.karuhun.ftth.fieldservice.domain

import com.karuhun.ftth.common.domain.error.ValidationException

/** Arithmetic uses Long so upper IPv4 addresses never overflow signed Int. */
data class Ipv4Block(val start: Long, val prefix: Int) {
    val size: Int get() = (1L shl (32 - prefix)).toInt()
    val end: Long get() = start + size - 1
    val cidr: String get() = address(start) + "/" + prefix
    val subnet: String get() = address((0xffffffffL shl (32 - prefix)) and 0xffffffffL)
    val hosts: Int get() = if (prefix >= 31) size else size - 2
    fun role(value: Long): String = when {
        prefix >= 31 -> "HOST"
        value == start -> "NETWORK"
        value == end -> "BROADCAST"
        else -> "HOST"
    }
    companion object {
        fun parse(raw: String): Ipv4Block {
            val parts = raw.trim().split('/')
            val prefix = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || prefix == null || prefix !in 20..32)
                throw ValidationException("Gunakan CIDR IPv4 /20 sampai /32 (maksimal 4096 alamat)")
            val value = number(parts[0])
            val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            return Ipv4Block(value and mask, prefix)
        }
        fun number(raw: String): Long {
            val octets = raw.split('.')
            if (octets.size != 4 || octets.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() > 255 })
                throw ValidationException("Gunakan alamat IPv4 yang valid")
            return octets.fold(0L) { result, part -> (result shl 8) or part.toLong() }
        }
        fun address(value: Long): String = (3 downTo 0).joinToString(".") { ((value ushr (it * 8)) and 255).toString() }
    }
}
