package com.duluin.ftth.vpn.domain.model

import com.duluin.ftth.common.domain.UuidV7
import com.duluin.ftth.common.domain.error.ValidationException
import java.util.UUID

/**
 * Satu blok alamat yang diakui berada di belakang sebuah akun VPN — pasangan `iroute` di hub
 * plus rute kernel yang mengarahkannya ke tunnel.
 *
 * Entitas tersendiri (bukan sekadar String di [VpnPeer]) karena operator perlu mencabut satu
 * blok tanpa menyentuh yang lain, dan perlu menamainya: satu BRAS lazim membawa lebih dari satu
 * kolam ("PPPoE pelanggan", "hotspot", "VLAN manajemen"). Enam bulan kemudian, deretan CIDR
 * telanjang tak lagi bisa dibedakan mana yang masih dipakai.
 *
 * [subnet] tak pernah berubah setelah dibuat: mengubahnya sama saja mencabut rute lama dan
 * memasang rute baru, dan memperlakukannya sebagai baris baru membuat rekonsiliasi di hub
 * (yang membandingkan daftar CIDR) tak perlu mengingat riwayat apa pun.
 */
class VpnPeerRoute private constructor(
    val id: UUID,
    val subnet: RoutedSubnet,
    label: String,
) {
    /** Nama blok untuk manusia (mis. "Kolam PPPoE") — hiasan, tak dipakai routing. */
    var label: String = label
        private set

    /** Bentuk kanonik yang disimpan & dikirim ke hub (mis. `10.20.0.0/16`). */
    val cidr: String get() = subnet.cidr

    /** Ganti nama saja; bloknya sengaja tak bisa diubah (lihat KDoc kelas). */
    fun rename(label: String?) {
        this.label = resolveLabel(label)
    }

    companion object {
        /**
         * Batas blok per akun. Bukan batas teknis OpenVPN, melainkan penjaga kewarasan: satu
         * BRAS yang butuh lebih dari ini hampir pasti sedang salah memodelkan jaringannya
         * (mis. memasukkan tiap /24 pelanggan satu per satu alih-alih satu blok induk).
         */
        const val MAX_PER_PEER = 8

        private const val MAX_LABEL = 40

        fun create(cidr: String, label: String?): VpnPeerRoute =
            VpnPeerRoute(UuidV7.generate(), RoutedSubnet.parse(cidr), resolveLabel(label))

        fun rehydrate(id: UUID, cidr: String, label: String): VpnPeerRoute =
            VpnPeerRoute(id, RoutedSubnet.parse(cidr), label)

        private fun resolveLabel(label: String?): String {
            val trimmed = label?.trim()?.takeIf { it.isNotEmpty() } ?: "Blok pelanggan"
            if (trimmed.length > MAX_LABEL) throw ValidationException("Nama blok maksimal $MAX_LABEL karakter")
            return trimmed
        }
    }
}
