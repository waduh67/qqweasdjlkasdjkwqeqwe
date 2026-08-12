package com.duluin.ftth.common.security

import java.util.UUID

/**
 * Apakah tenant yang sedang berjalan sedang dikunci BACA-SAJA karena langganan SaaS-nya
 * menunggak melewati masa tenggang.
 *
 * Antarmukanya tinggal di `common`, implementasinya di `platformbilling` — inversi yang
 * disengaja. Penegaknya adalah
 * [com.duluin.ftth.common.infrastructure.security.AccessChecker], satu-satunya tempat yang
 * dilewati hampir setiap endpoint tulis di aplikasi ini; sementara `common → platformbilling`
 * menutup siklus modul (platformbilling sudah bergantung pada common).
 *
 * Dipakai lewat `ObjectProvider` agar konteks yang tak memuat platformbilling — test unit,
 * misalnya — tetap berjalan dengan perilaku lama: tanpa implementasi, tak ada yang terkunci.
 */
interface ReadOnlyLockGuard {

    /** True bila tenant konteks berjalan sedang baca-saja. Dipanggil di SETIAP cek izin. */
    fun isReadOnly(): Boolean

    /**
     * Buang jawaban yang disimpan untuk [tenantId]. Dipanggil saat status langganan berubah
     * (pelunasan, penegakan scheduler) supaya konsol hidup kembali seketika, bukan setelah
     * cache-nya kedaluwarsa sendiri.
     */
    fun invalidate(tenantId: UUID)
}
