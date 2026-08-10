package com.duluin.ftth.iam.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/**
 * Satu kode pemulihan 2FA — jalan masuk cadangan ketika ponsel penghasil kode hilang,
 * rusak, atau ter-reset.
 *
 * Yang disimpan HANYA hash-nya; kode terbacanya cuma pernah ada sekali, di layar orang
 * yang mendaftarkan 2FA. Baris yang sudah terpakai tidak dihapus melainkan ditandai
 * [usedAt], supaya pertanyaan audit "pernahkah akun ini dipulihkan, dan kapan" bisa
 * dijawab dengan data, bukan dugaan.
 */
class RecoveryCode private constructor(
    val id: UUID,
    val tenantId: UUID,
    val userId: UUID,
    val codeHash: String,
    usedAt: Instant?,
    val createdAt: Instant,
) {
    var usedAt: Instant? = usedAt
        private set

    val used: Boolean get() = usedAt != null

    /** Sekali pakai: pemanggil wajib menyimpan hasilnya agar kode tak bisa diulang. */
    fun markUsed(now: Instant = Instant.now()) {
        if (usedAt == null) usedAt = now
    }

    companion object {
        fun issue(tenantId: UUID, userId: UUID, codeHash: String): RecoveryCode = RecoveryCode(
            id = UuidV7.generate(),
            tenantId = tenantId,
            userId = userId,
            codeHash = codeHash,
            usedAt = null,
            createdAt = Instant.now(),
        )

        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            userId: UUID,
            codeHash: String,
            usedAt: Instant?,
            createdAt: Instant,
        ): RecoveryCode = RecoveryCode(id, tenantId, userId, codeHash, usedAt, createdAt)
    }
}
