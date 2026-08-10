package com.duluin.ftth.iam.application.port.outbound

import com.duluin.ftth.iam.domain.model.RecoveryCode
import java.util.UUID

/** Port persistence kode pemulihan 2FA. Ter-scope tenant otomatis (Hibernate + RLS). */
interface RecoveryCodeRepository {

    /**
     * Ganti seluruh kode milik seorang pengguna dengan kumpulan baru. Sengaja
     * mengganti, bukan menambah: kumpulan baru diterbitkan justru ketika yang lama
     * dianggap tak lagi tepercaya (didaftarkan ulang, atau sudah terpakai sebagian).
     */
    fun replaceAll(userId: UUID, codes: List<RecoveryCode>)

    fun findByHash(userId: UUID, codeHash: String): RecoveryCode?

    fun save(code: RecoveryCode): RecoveryCode

    /** Jumlah kode yang belum terpakai — angka yang ditampilkan di halaman keamanan. */
    fun countUnused(userId: UUID): Int

    fun deleteAllForUser(userId: UUID)
}
