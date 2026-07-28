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
    /** Grup normal paket — kecepatan penuh. */
    fun normal(planId: UUID): String = "plan:$planId"

    /** Grup throttle FUP paket — kecepatan turun setelah kuota habis. */
    fun fup(planId: UUID): String = "plan:$planId:fup"
}
