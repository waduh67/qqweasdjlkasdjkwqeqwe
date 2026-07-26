package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/** Jenis perintah BRAS yang bisa diantrekan ke collector. */
enum class BngActionType {
    /** Putuskan sesi PPPoE — dasar pemotongan isolir & Reset Login. */
    DISCONNECT,

    /** Change-of-Authorization: ubah kecepatan sesi hidup tanpa memutusnya. */
    COA,
}

/**
 * Daur hidup satu perintah dalam antrean:
 * `PENDING` (baru, belum dikirim) → `DISPATCHED` (sudah dititipkan ke collector,
 * menunggu hasil) → `COMPLETED`/`FAILED` (di-ACK collector). Dua status terakhir
 * terminal.
 */
enum class BngActionStatus { PENDING, DISPATCHED, COMPLETED, FAILED }

/**
 * Satu perintah BRAS terhadap sebuah akun PPPoE — sekaligus antrean DAN jejak audit.
 *
 * Satu baris memikul dua peran karena identitasnya (siapa meminta, perintah apa, ke
 * akun mana, kapan) tak pernah berubah — hanya statusnya yang berpindah sepanjang
 * daur hidup. Memisahkan "antrean" dari "log audit" hanya akan menduplikasi baris
 * yang sama.
 *
 * Dikirim ke collector lewat respons denyut (jalur turun) dan dieksekusi di sana,
 * lalu di-ACK pada denyut berikutnya. Karena perintah dikirim ulang tiap denyut
 * sampai di-ACK, eksekusinya harus idempoten (at-least-once).
 *
 * [subscriberAccessId] menaut ke akun (intra-module, FK CASCADE). [nasId] BRAS
 * penyasar — wajib ada: tanpa BRAS tak ada tempat mengirim perintah. [requestedBy]
 * boleh null saat perintah dipicu sistem (mis. isolir otomatis dari event langganan),
 * bukan operator.
 */
class BngAction private constructor(
    val id: UUID,
    val tenantId: UUID,
    val subscriberAccessId: UUID,
    val nasId: UUID,
    val username: String,
    val action: BngActionType,
    /** Hanya terisi untuk [BngActionType.COA]. */
    val downMbps: Int?,
    val upMbps: Int?,
    status: BngActionStatus,
    detail: String?,
    val requestedBy: UUID?,
    /** Email pelaku didenormalisasi (seperti `cpe_action_log`) agar riwayat tetap terbaca meski penggunanya kelak dihapus; null bila dipicu sistem. */
    val requestedByEmail: String?,
    val requestedAt: Instant,
    dispatchedAt: Instant?,
    completedAt: Instant?,
) {
    var status: BngActionStatus = status
        private set

    /** Pesan hasil — kosong saat sukses, sebab-gagal saat [BngActionStatus.FAILED]. */
    var detail: String? = detail
        private set

    var dispatchedAt: Instant? = dispatchedAt
        private set

    var completedAt: Instant? = completedAt
        private set

    val isTerminal: Boolean
        get() = status == BngActionStatus.COMPLETED || status == BngActionStatus.FAILED

    /**
     * Menandai perintah sudah dititipkan ke collector. Idempoten: aman dipanggil ulang
     * tiap denyut (perintah dikirim ulang sampai di-ACK), waktu kirim pertama dijaga.
     * Perintah terminal tak disentuh lagi.
     */
    fun markDispatched(at: Instant = Instant.now()) {
        if (isTerminal) return
        status = BngActionStatus.DISPATCHED
        if (dispatchedAt == null) dispatchedAt = at
    }

    /** ACK sukses dari collector. No-op bila sudah terminal (ACK ganda dari at-least-once). */
    fun complete(at: Instant = Instant.now()) {
        if (isTerminal) return
        status = BngActionStatus.COMPLETED
        completedAt = at
    }

    /** ACK gagal dari collector; [detail] mengangkut sebab. No-op bila sudah terminal. */
    fun fail(detail: String?, at: Instant = Instant.now()) {
        if (isTerminal) return
        status = BngActionStatus.FAILED
        this.detail = detail
        completedAt = at
    }

    companion object {
        fun disconnect(
            tenantId: UUID,
            subscriberAccessId: UUID,
            nasId: UUID,
            username: String,
            requestedBy: UUID?,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): BngAction = BngAction(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subscriberAccessId = subscriberAccessId,
            nasId = nasId,
            username = username,
            action = BngActionType.DISCONNECT,
            downMbps = null,
            upMbps = null,
            status = BngActionStatus.PENDING,
            detail = null,
            requestedBy = requestedBy,
            requestedByEmail = requestedByEmail,
            requestedAt = at,
            dispatchedAt = null,
            completedAt = null,
        )

        @Suppress("LongParameterList")
        fun coa(
            tenantId: UUID,
            subscriberAccessId: UUID,
            nasId: UUID,
            username: String,
            downMbps: Int,
            upMbps: Int,
            requestedBy: UUID?,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): BngAction = BngAction(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subscriberAccessId = subscriberAccessId,
            nasId = nasId,
            username = username,
            action = BngActionType.COA,
            downMbps = downMbps,
            upMbps = upMbps,
            status = BngActionStatus.PENDING,
            detail = null,
            requestedBy = requestedBy,
            requestedByEmail = requestedByEmail,
            requestedAt = at,
            dispatchedAt = null,
            completedAt = null,
        )

        @Suppress("LongParameterList")
        fun rehydrate(
            id: UUID,
            tenantId: UUID,
            subscriberAccessId: UUID,
            nasId: UUID,
            username: String,
            action: BngActionType,
            downMbps: Int?,
            upMbps: Int?,
            status: BngActionStatus,
            detail: String?,
            requestedBy: UUID?,
            requestedByEmail: String?,
            requestedAt: Instant,
            dispatchedAt: Instant?,
            completedAt: Instant?,
        ): BngAction = BngAction(
            id, tenantId, subscriberAccessId, nasId, username, action, downMbps, upMbps,
            status, detail, requestedBy, requestedByEmail, requestedAt, dispatchedAt, completedAt,
        )
    }
}
