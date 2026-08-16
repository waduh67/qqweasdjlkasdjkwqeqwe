package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Aritmetika IPv4 bersama untuk value object modul `vpn` ([TunnelSubnet], [RoutedSubnet]).
 *
 * Dipisah ke sini karena keduanya butuh perhitungan yang sama persis tapi punya ATURAN yang
 * berbeda — blok tunnel dibatasi /8..30 (harus muat hub + peer), sedangkan blok di belakang
 * peer boleh sampai /32 (satu perangkat). Menyalin aritmetikanya dua kali berarti suatu hari
 * salah satunya diperbaiki dan yang lain tidak.
 *
 * [subject] hanya memengaruhi kalimat galat: operator yang salah mengetik blok pelanggan tak
 * perlu membaca kata "tunnel" yang tak ada hubungannya dengan yang sedang ia isi.
 */
internal fun ipv4ToInt(ip: String, subject: String): Int {
    val parts = ip.trim().split(".")
    if (parts.size != 4) throw ValidationException("Alamat IPv4 $subject tidak valid: '$ip'")
    var result = 0
    for (part in parts) {
        val octet = part.toIntOrNull() ?: throw ValidationException("Oktet IPv4 $subject tidak valid: '$ip'")
        if (octet !in 0..255) throw ValidationException("Oktet IPv4 $subject di luar 0-255: '$ip'")
        result = (result shl 8) or octet
    }
    return result
}

/** Bongkar Int 32-bit menjadi dotted-quad IPv4 (oktet diperlakukan tak bertanda). */
internal fun intToIpv4(value: Int): String =
    "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
