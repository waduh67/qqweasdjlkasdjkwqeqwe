package com.duluin.ftth.bng.domain.model

import com.duluin.ftth.common.domain.UuidV7
import java.time.Instant
import java.util.UUID

/** Jenis perintah BRAS/RADIUS yang bisa diantrekan ke collector. */
enum class BngActionType {
    /** Putuskan sesi PPPoE — dasar pemotongan isolir & Reset Login. */
    DISCONNECT,

    /** Change-of-Authorization: ubah kecepatan sesi hidup tanpa memutusnya. */
    COA,

    /** Tulis otorisasi akun ke RADIUS (kredensial + keanggotaan grup paket). */
    PROVISION,

    /** Hapus otorisasi akun dari RADIUS (per username). */
    DEPROVISION,

    /** Sinkronkan atribut grup paket di RADIUS (rate-limit + batas sesi + grup FUP). */
    SYNC_GROUP,
    ;

    companion object {
        /**
         * Aksi jalur-DATA RADIUS — dieksekusi SERVER langsung ke radius-db platform
         * (RADIUS-as-a-service), TIDAK dititip ke collector on-prem yang tak punya rute
         * ke radius-db internal.
         */
        val PROVISIONING: Set<BngActionType> = setOf(PROVISION, DEPROVISION, SYNC_GROUP)

        /** Aksi kontrol sesi (DAE RFC 5176) — masih lewat collector on-prem (jalur turun). */
        val SESSION_CONTROL: Set<BngActionType> = setOf(DISCONNECT, COA)
    }
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
 * [subscriberAccessId] menaut ke akun (intra-module, FK CASCADE) untuk perintah per-akun
 * (DISCONNECT/COA/PROVISION); NULL untuk perintah tingkat-grup ([BngActionType.SYNC_GROUP])
 * dan penghapusan ([BngActionType.DEPROVISION]) — yang sengaja LEPAS dari akun agar tak
 * ikut ter-CASCADE saat akun dihapus, sehingga penghapusan RADIUS tetap terkirim. [nasId]
 * BRAS penyasar — wajib ada: tanpa BRAS tak ada tempat mengirim perintah. [requestedBy]
 * boleh null saat perintah dipicu sistem (mis. isolir otomatis dari event langganan),
 * bukan operator.
 *
 * Payload menyesuaikan jenis: [downMbps]/[upMbps] untuk COA; [groupname] untuk PROVISION
 * (grup yang diikuti) & SYNC_GROUP (grup yang disetel); [rateLimit]/[simultaneousUse]/
 * [fupGroupname]/[fupRateLimit] untuk SYNC_GROUP. Password akun SENGAJA tak disimpan di
 * sini — diresolusi+dekripsi dari akun saat klaim dispatch.
 */
class BngAction private constructor(
    val id: UUID,
    val tenantId: UUID,
    val subscriberAccessId: UUID?,
    val nasId: UUID,
    val username: String,
    val action: BngActionType,
    /** Hanya terisi untuk [BngActionType.COA]. */
    val downMbps: Int?,
    val upMbps: Int?,
    /** Nama grup paket — [BngActionType.PROVISION] & [BngActionType.SYNC_GROUP]. */
    val groupname: String?,
    /** Atribut Mikrotik-Rate-Limit grup — [BngActionType.SYNC_GROUP]. */
    val rateLimit: String?,
    /** Batas sesi simultan grup — [BngActionType.SYNC_GROUP]; null = tanpa batas. */
    val simultaneousUse: Int?,
    /** Nama grup throttle FUP — [BngActionType.SYNC_GROUP] bila FUP aktif. */
    val fupGroupname: String?,
    /** Atribut rate-limit grup FUP — [BngActionType.SYNC_GROUP] bila FUP aktif. */
    val fupRateLimit: String?,
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

    /**
     * Gagal TRANSIEN pada eksekusi server-side (mis. radius-db sesaat mati): rekam sebab
     * tapi TETAP PENDING agar diklaim ulang putaran berikutnya (degradasi anggun,
     * at-least-once). Beda dari [fail] yang terminal — dipakai worker provisioning selama
     * aksi belum melewati batas usia retry.
     */
    fun retryLater(detail: String?) {
        if (isTerminal) return
        this.detail = detail
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
        ): BngAction = create(
            tenantId, subscriberAccessId, nasId, username, BngActionType.DISCONNECT,
            requestedBy = requestedBy, requestedByEmail = requestedByEmail, at = at,
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
        ): BngAction = create(
            tenantId, subscriberAccessId, nasId, username, BngActionType.COA,
            downMbps = downMbps, upMbps = upMbps,
            requestedBy = requestedBy, requestedByEmail = requestedByEmail, at = at,
        )

        /** Tulis kredensial + keanggotaan grup akun ke RADIUS. Password diresolusi saat klaim, bukan disimpan. */
        @Suppress("LongParameterList")
        fun provision(
            tenantId: UUID,
            subscriberAccessId: UUID,
            nasId: UUID,
            username: String,
            groupname: String,
            requestedBy: UUID?,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): BngAction = create(
            tenantId, subscriberAccessId, nasId, username, BngActionType.PROVISION,
            groupname = groupname,
            requestedBy = requestedBy, requestedByEmail = requestedByEmail, at = at,
        )

        /**
         * Hapus otorisasi akun dari RADIUS (per username). Tanpa [subscriberAccessId]
         * agar tak ikut ter-CASCADE saat akunnya dihapus — penghapusan RADIUS tetap terkirim.
         */
        fun deprovision(
            tenantId: UUID,
            nasId: UUID,
            username: String,
            requestedBy: UUID?,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): BngAction = create(
            tenantId, subscriberAccessId = null, nasId, username, BngActionType.DEPROVISION,
            requestedBy = requestedBy, requestedByEmail = requestedByEmail, at = at,
        )

        /**
         * Sinkronkan atribut grup paket di RADIUS. Tingkat-grup, bukan per-akun → tanpa
         * [subscriberAccessId], username kosong (adapter mengabaikannya untuk SYNC_GROUP).
         */
        @Suppress("LongParameterList")
        fun syncGroup(
            tenantId: UUID,
            nasId: UUID,
            groupname: String,
            rateLimit: String,
            simultaneousUse: Int?,
            fupGroupname: String?,
            fupRateLimit: String?,
            requestedBy: UUID?,
            requestedByEmail: String?,
            at: Instant = Instant.now(),
        ): BngAction = create(
            tenantId, subscriberAccessId = null, nasId, username = "", BngActionType.SYNC_GROUP,
            groupname = groupname, rateLimit = rateLimit, simultaneousUse = simultaneousUse,
            fupGroupname = fupGroupname, fupRateLimit = fupRateLimit,
            requestedBy = requestedBy, requestedByEmail = requestedByEmail, at = at,
        )

        @Suppress("LongParameterList")
        private fun create(
            tenantId: UUID,
            subscriberAccessId: UUID?,
            nasId: UUID,
            username: String,
            action: BngActionType,
            downMbps: Int? = null,
            upMbps: Int? = null,
            groupname: String? = null,
            rateLimit: String? = null,
            simultaneousUse: Int? = null,
            fupGroupname: String? = null,
            fupRateLimit: String? = null,
            requestedBy: UUID?,
            requestedByEmail: String?,
            at: Instant,
        ): BngAction = BngAction(
            id = UuidV7.generate(),
            tenantId = tenantId,
            subscriberAccessId = subscriberAccessId,
            nasId = nasId,
            username = username,
            action = action,
            downMbps = downMbps,
            upMbps = upMbps,
            groupname = groupname,
            rateLimit = rateLimit,
            simultaneousUse = simultaneousUse,
            fupGroupname = fupGroupname,
            fupRateLimit = fupRateLimit,
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
            subscriberAccessId: UUID?,
            nasId: UUID,
            username: String,
            action: BngActionType,
            downMbps: Int?,
            upMbps: Int?,
            groupname: String?,
            rateLimit: String?,
            simultaneousUse: Int?,
            fupGroupname: String?,
            fupRateLimit: String?,
            status: BngActionStatus,
            detail: String?,
            requestedBy: UUID?,
            requestedByEmail: String?,
            requestedAt: Instant,
            dispatchedAt: Instant?,
            completedAt: Instant?,
        ): BngAction = BngAction(
            id, tenantId, subscriberAccessId, nasId, username, action, downMbps, upMbps,
            groupname, rateLimit, simultaneousUse, fupGroupname, fupRateLimit,
            status, detail, requestedBy, requestedByEmail, requestedAt, dispatchedAt, completedAt,
        )
    }
}
