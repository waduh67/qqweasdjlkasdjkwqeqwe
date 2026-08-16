package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Blok alamat yang HIDUP DI BELAKANG sebuah peer — bukan alamat peer-nya sendiri.
 *
 * Kenapa ini ada: perangkat pelanggan (ONT/router) tak pernah punya IP publik. Yang ia punya
 * adalah alamat dari kolam BRAS — mis. `10.20.255.254` di dalam `10.20.0.0/16`. Dari sudut
 * pandang BRAS itu bukan "di balik NAT", melainkan peer PPPoE yang menempel langsung; BRAS
 * bisa menghubunginya kapan saja. Yang tak bisa adalah SERVER KAMI: ia berdiri di luar
 * jaringan ISP dan tak tahu blok itu ada di mana, jadi paketnya lari ke gateway bawaan dan
 * hilang.
 *
 * Padahal jalannya sudah ada — peer yang bersangkutan ADALAH router pemilik blok itu, dan
 * tunnelnya sudah terpasang. Yang kurang cuma satu keterangan: "blok ini ada di belakang peer
 * itu". Begitu keterangan itu ada, connection request TR-069 (ACS → ONT port 7547) berubah dari
 * "Not Connect, diantre sampai inform berikutnya" menjadi eksekusi 1-3 detik.
 *
 * Prefix 8..32: /32 sah (satu perangkat), tapi lebih longgar dari /8 ditolak. Alasannya bukan
 * kerapian melainkan keselamatan VPS — blok yang terlalu lebar (apalagi `0.0.0.0/0`) akan
 * dipasang sebagai rute kernel di hub dan menelan trafik hub itu sendiri, termasuk jalur SSH
 * operator yang sedang memasangnya.
 */
class RoutedSubnet private constructor(
    private val network: Int,
    val prefix: Int,
) {
    private val mask: Int = -1 shl (32 - prefix)

    /** Representasi kanonik CIDR (network yang sudah dinormalisasi, mis. `10.20.0.0/16`). */
    val cidr: String get() = "${intToIpv4(network)}/$prefix"

    /** Alamat network (mis. `10.20.0.0`). */
    fun networkAddress(): String = intToIpv4(network)

    /** Netmask bertitik — bentuk yang dipakai OpenVPN `iroute`/`route`. */
    fun netmask(): String = intToIpv4(mask)

    /** Apakah [ip] penghuni blok ini. */
    fun contains(ip: String): Boolean = (ipv4ToInt(ip, SUBJECT) and mask) == network

    /**
     * Apakah blok ini dan [other] beririsan — termasuk saat salah satunya sekadar memuat yang
     * lain (`10.20.0.0/16` vs `10.20.5.0/24`).
     *
     * Dipakai sebagai penjaga, bukan kerapian: dua peer pada hub yang sama mengklaim blok
     * beririsan membuat tabel `iroute` OpenVPN ambigu. OpenVPN tak mengeluh — ia hanya memilih
     * salah satu, dan separuh perangkat jadi tak terjangkau tanpa satu baris log pun.
     */
    fun overlaps(other: RoutedSubnet): Boolean =
        (network and other.mask) == other.network || (other.network and mask) == network

    override fun equals(other: Any?): Boolean =
        this === other || (other is RoutedSubnet && network == other.network && prefix == other.prefix)

    override fun hashCode(): Int = 31 * network + prefix

    override fun toString(): String = cidr

    companion object {
        /** Kata yang muncul di kalimat galat aritmetika IPv4 milik value object ini. */
        private const val SUBJECT = "blok"

        /** Prefix terlonggar yang masih boleh — lihat KDoc kelas soal kenapa ada batas bawah. */
        private const val MIN_PREFIX = 8

        /**
         * Oktet pertama yang tak pernah masuk akal sebagai blok pelanggan dan justru berbahaya
         * bila dipasang sebagai rute di hub: `0.x` (rute bawaan terselubung), `127.x` (loopback —
         * hub akan berhenti bicara pada dirinya sendiri), dan `224+` (multicast/reserved).
         */
        private val FORBIDDEN_FIRST_OCTETS = setOf(0, 127)

        /**
         * Uraikan `a.b.c.d/prefix`. Alamat host apa pun dinormalisasi ke alamat network-nya,
         * jadi `10.20.255.254/16` dan `10.20.0.0/16` menghasilkan blok yang sama — operator
         * yang menempel alamat pelanggan alih-alih blok tetap mendapat hasil yang benar.
         */
        fun parse(cidr: String): RoutedSubnet {
            val trimmed = cidr.trim()
            val slash = trimmed.indexOf('/')
            if (slash < 0) throw ValidationException("Blok harus berformat a.b.c.d/prefix, mis. 10.20.0.0/16")
            val prefix = trimmed.substring(slash + 1).toIntOrNull()
                ?: throw ValidationException("Prefix blok tidak valid: '$trimmed'")
            if (prefix !in MIN_PREFIX..32) {
                throw ValidationException(
                    "Prefix blok harus $MIN_PREFIX-32. Blok yang lebih lebar dari /$MIN_PREFIX akan " +
                        "menelan trafik server VPN itu sendiri.",
                )
            }
            val ipInt = ipv4ToInt(trimmed.substring(0, slash), SUBJECT)
            val first = (ipInt ushr 24) and 0xFF
            if (first in FORBIDDEN_FIRST_OCTETS || first >= 224) {
                throw ValidationException("Blok '$trimmed' tak boleh dirutekan lewat tunnel (loopback/multicast/reserved)")
            }
            val mask = -1 shl (32 - prefix)
            return RoutedSubnet(ipInt and mask, prefix)
        }
    }
}
