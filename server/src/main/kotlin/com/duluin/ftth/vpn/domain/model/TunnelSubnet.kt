package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Value object subnet tunnel IPv4 untuk overlay OpenVPN (mis. `10.8.0.0/24`).
 *
 * Murni & deterministik — tanpa dependency framework — sehingga aman di lapisan
 * domain dan mudah diuji. Semua aritmetika memakai Int 32-bit (empat oktet dikemas
 * jadi satu int); hanya IPv4. Alamat pertama (network+1) dicadangkan untuk hub;
 * peer dialokasikan mulai network+2 hingga broadcast-1.
 */
class TunnelSubnet private constructor(
    private val network: Int,
    val prefix: Int,
) {
    private val mask: Int = -1 shl (32 - prefix)
    private val broadcast: Int = network or mask.inv()

    /** Alamat network (a.b.c.0 untuk /24). */
    fun networkAddress(): String = intToIpv4(network)

    /** Netmask bertitik (mis. `255.255.255.0`). */
    fun netmask(): String = intToIpv4(mask)

    /** Alamat hub OpenVPN — network+1, dicadangkan (mis. `10.8.0.1`). */
    fun serverAddress(): String = intToIpv4(network + 1)

    /** Apakah [ip] berada dalam subnet ini. */
    fun contains(ip: String): Boolean = (ipv4ToInt(ip, SUBJECT) and mask) == network

    /**
     * Alamat host terendah pada rentang [network+2 .. broadcast-1] yang belum dipakai.
     * network+1 (hub) selalu dilewati. Melempar [ConflictException] bila blok habis.
     */
    fun allocate(used: Set<String>): String {
        val start = network + 2
        val end = broadcast - 1
        var candidate = start
        while (candidate <= end) {
            val ip = intToIpv4(candidate)
            if (ip !in used) return ip
            candidate++
        }
        throw ConflictException("Blok alamat tunnel habis")
    }

    /** Representasi kanonik CIDR (network yang sudah dinormalisasi). */
    val cidr: String get() = "${intToIpv4(network)}/$prefix"

    companion object {
        /** Kata yang muncul di kalimat galat aritmetika IPv4 milik value object ini. */
        private const val SUBJECT = "tunnel"

        /**
         * Uraikan CIDR IPv4 `a.b.c.d/prefix` dengan prefix 8..30. Alamat host apa pun
         * dinormalisasi ke alamat network-nya. Melempar [ValidationException] bila bukan
         * IPv4 dotted-quad valid atau prefix di luar 8..30.
         */
        fun parse(cidr: String): TunnelSubnet {
            val trimmed = cidr.trim()
            val slash = trimmed.indexOf('/')
            if (slash < 0) throw ValidationException("CIDR tunnel harus berformat a.b.c.d/prefix")
            val prefix = trimmed.substring(slash + 1).toIntOrNull()
                ?: throw ValidationException("Prefix CIDR tunnel tidak valid")
            if (prefix !in 8..30) throw ValidationException("Prefix CIDR tunnel harus 8-30")
            val ipInt = ipv4ToInt(trimmed.substring(0, slash), SUBJECT)
            val mask = -1 shl (32 - prefix)
            return TunnelSubnet(ipInt and mask, prefix)
        }
    }
}
