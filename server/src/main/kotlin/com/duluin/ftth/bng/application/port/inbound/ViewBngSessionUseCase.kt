package com.duluin.ftth.bng.application.port.inbound

import java.util.UUID

/**
 * Jalur baca BNG untuk UI: keadaan sesi PPPoE terkini sebuah akun ("B-ras Check")
 * dan tren trafiknya. Murni baca — tak menyentuh BRAS, hanya proyeksi data yang
 * sudah dilaporkan collector.
 */
interface ViewBngSessionUseCase {

    /**
     * Sesi terkini sebuah akun. Selalu mengembalikan view walau akun belum pernah
     * terpantau (online=false, waktu null) agar UI bisa membedakan "offline" dari
     * "tak dikenal" (yang justru melempar not-found).
     */
    fun session(subscriberAccessId: UUID): BrasSessionView

    /** Tren trafik [hours] jam terakhir; [hours] di-clamp ke rentang wajar oleh implementasi. */
    fun traffic(subscriberAccessId: UUID, hours: Int): TrafficHistoryView
}
