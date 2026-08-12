package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException

/**
 * Rentang port publik pada hub yang dipetakan (DNAT) ke port layanan perangkat overlay (Winbox,
 * API, SSH, …). Kolamnya per-HUB dan dihitung dari SEMUA penerusan ([VpnPortForward]), bukan satu
 * per akun: satu perangkat boleh punya beberapa pintu, dan tiap pintu wajib berport publik unik
 * agar operator bisa meremote lewat `IP_HUB:port` tanpa ikut men-dial tunnel.
 *
 * Murni & deterministik (tanpa framework), cermin pola alokasi [TunnelSubnet]: alamat/port
 * terendah yang belum terpakai dipilih lebih dulu.
 */
class RemotePortRange(private val min: Int, private val max: Int) {

    init {
        if (min !in 1..65535 || max !in 1..65535) {
            throw ValidationException("Port remote hub harus 1-65535")
        }
        if (min > max) throw ValidationException("Batas bawah port remote hub melebihi batas atas")
    }

    /**
     * Port terendah pada [min]..[max] yang belum ada di [used]. Melempar [ConflictException]
     * bila rentang habis.
     */
    fun allocate(used: Set<Int>): Int {
        var candidate = min
        while (candidate <= max) {
            if (candidate !in used) return candidate
            candidate++
        }
        throw ConflictException("Rentang port remote hub habis")
    }
}
