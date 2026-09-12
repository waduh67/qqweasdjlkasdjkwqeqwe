package com.duluin.ftth.order.application.port.outbound

import java.time.Duration

/**
 * Rem laju untuk permintaan publik, dengan hitungan yang DIBAGI antar-instance.
 *
 * Kontraknya sengaja memakai tipe primitif (bukan objek kuota dari lapisan konfigurasi) supaya
 * lapisan application tetap bisa diuji tanpa Spring: implementasi palsu di test cukup
 * menghitung di peta biasa.
 */
interface PublicRequestThrottle {

    /**
     * Ambil satu jatah dari ember `(scope, subject)`, atau tolak dengan
     * [com.duluin.ftth.common.domain.error.TooManyRequestsException].
     *
     * ATURAN: pencacahan WAJIB bertahan meski permintaan yang memicunya kemudian gagal.
     * Kalau ia ikut ter-rollback, permintaan yang selalu tidak valid (slug ngawur, payload
     * rusak) menjadi GRATIS TANPA BATAS — dan justru itulah bentuk penyalahgunaan yang paling
     * murah dilakukan. Implementasi bertanggung jawab menjamin ini.
     *
     * [subject] adalah alamat IP atau id tenant; pemanggil yang memotong panjangnya.
     */
    fun spend(scope: String, subject: String, limit: Int, window: Duration, message: String)
}
