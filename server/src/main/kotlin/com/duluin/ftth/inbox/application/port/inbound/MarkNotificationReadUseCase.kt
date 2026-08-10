package com.duluin.ftth.inbox.application.port.inbound

import java.util.UUID

/**
 * Menandai pemberitahuan sudah dibaca — selalu atas nama pengguna yang sedang login.
 *
 * "Terbaca" disimpan per pengguna, bukan per pemberitahuan: satu pemberitahuan antrean
 * bersama dilihat banyak orang, dan yang satu membacanya tak berarti yang lain sudah.
 */
interface MarkNotificationReadUseCase {

    /**
     * Menandai sebagian. Id yang bukan hak pengguna ini diabaikan diam-diam (bukan 403):
     * membedakan "tak ada" dari "bukan milikmu" berarti membocorkan keberadaan
     * pemberitahuan orang lain kepada penebak id.
     *
     * @return jumlah yang benar-benar berubah jadi terbaca.
     */
    fun markRead(ids: Collection<UUID>): Int

    /** Mengosongkan lencana: seluruh isi kotak masuk yang terlihat ditandai terbaca. */
    fun markAllRead(): Int
}
