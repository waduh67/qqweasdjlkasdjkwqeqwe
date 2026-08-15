package com.duluin.ftth.bng.domain.model

import java.util.UUID

/**
 * Konvensi nama grup RADIUS per paket — inti "RADIUS jadi pusat": tiap paket menjadi
 * SATU grup (`radusergroup`/`radgroupreply`), akun cukup diikutkan ke grupnya. Membuat/
 * mengubah paket = menyentuh satu baris grup, bukan tiap akun apalagi tiap router.
 *
 * Grup kedua bersufiks `:fup` menampung kecepatan throttle yang di-swap saat kuota FUP
 * terlampaui. Nama diturunkan deterministik dari `planId` agar bisa dihitung ulang di
 * mana pun tanpa menyimpan pemetaan terpisah.
 */
object RadiusGroups {
    /**
     * Grup pelanggan terisolir — SATU untuk seluruh platform, bukan per paket maupun per
     * tenant. Isinya cuma sisa kecepatan untuk membuka halaman tagihan dan nama address-list
     * yang dipakai router melempar ke halaman itu; dua-duanya setelan platform yang sama
     * bagi semua orang, jadi tak ada apa pun milik tenant yang bisa bocor lewat grup ini.
     *
     * Namanya sengaja BUKAN turunan UUID seperti grup paket: operator perlu bisa mengetiknya
     * di router (`src-address-list=isolir`) dan mengenalinya lagi saat membaca konfigurasi
     * sebulan kemudian.
     */
    const val ISOLIR: String = "isolir"

    /** Grup normal paket — kecepatan penuh. */
    fun normal(planId: UUID): String = "plan:$planId"

    /** Grup throttle FUP paket — kecepatan turun setelah kuota habis. */
    fun fup(planId: UUID): String = "plan:$planId:fup"
}
