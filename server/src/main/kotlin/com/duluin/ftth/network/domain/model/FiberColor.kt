package com.duluin.ftth.network.domain.model

/**
 * Dua belas warna serat baku TIA-598 — urutannya universal, dipakai semua
 * pabrikan kabel. Core ke-1 biru, ke-2 jingga, dan seterusnya; lewat dua belas,
 * urutannya berulang di TUBE berikutnya. Urutan yang sama juga dipakai untuk
 * mewarnai tube-nya.
 *
 * [hex] ikut di domain, bukan cuma di web, karena ini WARNA FISIK selubung serat
 * yang dipegang teknisi — bukan token tema aplikasi. Satu sumber kebenaran
 * menjamin chip di layar sama dengan yang dilihat orang di lapangan; kalau
 * tiap klien mengarang sendiri, "hijau" di layar bisa jadi serat yang salah.
 */
enum class FiberColor(val label: String, val hex: String) {
    BIRU("Biru", "#1d4ed8"),
    JINGGA("Jingga", "#ea580c"),
    HIJAU("Hijau", "#16a34a"),
    COKLAT("Coklat", "#78350f"),
    ABU_ABU("Abu-abu", "#94a3b8"),
    PUTIH("Putih", "#f1f5f9"),
    MERAH("Merah", "#dc2626"),
    HITAM("Hitam", "#1e293b"),
    KUNING("Kuning", "#eab308"),
    UNGU("Ungu", "#7c3aed"),
    ROSE("Rose", "#f472b6"),
    AQUA("Aqua", "#06b6d4"),
    ;

    companion object {
        /** Panjang siklus warna: lewat ini urutan mengulang dari Biru. */
        val CYCLE: Int = entries.size

        /** Warna untuk posisi ke-[position] (1-based) dalam satu siklus. */
        fun ofPosition(position: Int): FiberColor = entries[(position - 1).mod(CYCLE)]
    }
}
