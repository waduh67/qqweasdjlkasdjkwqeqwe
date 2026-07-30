package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.bng.domain.model.TrafficSample
import java.time.Instant
import java.util.UUID

/**
 * Port persistence module bng. Semua tabel tenant-aware (@TenantId + RLS), jadi
 * semua pencarian ter-scope tenant aktif secara otomatis — tak ada parameter
 * tenantId yang dibawa-bawa.
 */
interface NasRepository {

    fun save(nas: Nas): Nas

    fun findById(id: UUID): Nas?

    fun findAll(): List<Nas>

    fun existsByName(name: String): Boolean

    fun deleteById(id: UUID)
}

interface SubscriberAccessRepository {

    fun save(access: SubscriberAccess): SubscriberAccess

    fun findById(id: UUID): SubscriberAccess?

    fun findByCustomerId(customerId: UUID): List<SubscriberAccess>

    fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess>

    fun findByUsername(username: String): SubscriberAccess?

    /** Semua akun yang dinaungi sebuah BRAS — dipakai jalur baca untuk tahu siapa yang diharapkan online. */
    fun findByNasId(nasId: UUID): List<SubscriberAccess>

    /** Semua akun yang memakai sebuah paket — dipakai re-sync grup RADIUS saat paket berubah. */
    fun findByPlanId(planId: UUID): List<SubscriberAccess>

    /**
     * Akun ACTIVE yang sudah ditugaskan ke BRAS — kandidat penegakan FUP. Hanya akun
     * aktif & ber-BRAS yang relevan: terisolir/terhenti sudah terputus, tak-ber-BRAS tak
     * punya sesi untuk di-throttle.
     */
    fun findActiveOnNas(): List<SubscriberAccess>

    fun existsBySubscriptionId(subscriptionId: UUID): Boolean

    /** Berapa akun yang masih dinaungi sebuah BRAS — untuk mencegah hapus BRAS terpakai. */
    fun countByNasId(nasId: UUID): Long

    fun deleteById(id: UUID)
}

/**
 * Sesi PPPoE terkini per akun — di-upsert tiap poll BRAS. Bukan deret waktu:
 * satu baris per akun, hanya keadaan terakhir. Tenant-aware (RLS), jadi pencarian
 * ter-scope tenant aktif otomatis.
 */
interface RadiusSessionRepository {

    fun save(session: RadiusSession): RadiusSession

    fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSession?
}

/**
 * Deret waktu akunting (hypertable Timescale). Penulisan lewat JDBC batch agar GUC
 * `app.tenant_id` ikut (RLS), pembacaan menghitung laju di SQL dari selisih penghitung
 * kumulatif antar cuplikan berurutan.
 */
interface AccountingRecordRepository {

    /** Menyimpan cuplikan; duplikat pada (akun, waktu) diabaikan diam-diam. */
    fun saveAll(points: List<AccountingRecordPoint>)

    /**
     * Tren trafik satu akun sejak [since], sudah dihitung jadi Mbps per titik.
     * Titik pertama tiap rentang tak punya laju (belum ada pembanding) → null.
     */
    fun trafficSince(subscriberAccessId: UUID, since: Instant): List<TrafficSample>

    /**
     * Total octet (unggah+unduh) terpakai tiap akun sejak [since] — dasar penegakan FUP.
     * Sadar-reset: penghitung kumulatif yang MUNDUR (sesi baru menyetel ulang counter)
     * dihitung dari nol, bukan jadi selisih negatif; titik pertama tiap akun jadi baseline
     * (kontribusi 0) agar byte sebelum [since] tak ikut terhitung. Akun tanpa cuplikan pada
     * rentang tak muncul di peta (dianggap 0 oleh pemanggil).
     */
    fun usageSince(subscriberAccessIds: Collection<UUID>, since: Instant): Map<UUID, Long>
}

/**
 * Antrean sekaligus jejak audit perintah BRAS (jalur turun, Phase 7c). Tenant-aware
 * (RLS), jadi pencarian ter-scope tenant aktif otomatis.
 */
interface BngActionRepository {

    fun save(action: BngAction): BngAction

    fun findById(id: UUID): BngAction?

    /**
     * Perintah KONTROL SESI ([BngActionType.SESSION_CONTROL]: DISCONNECT/COA) yang belum
     * tuntas (PENDING atau DISPATCHED) untuk sekumpulan BRAS — dasar dispatch ke collector.
     * Aksi jalur-data (PROVISION/DEPROVISION/SYNC_GROUP) SENGAJA tak ikut: sejak
     * RADIUS-as-a-service, server yang mengeksekusinya langsung ke radius-db (lihat
     * [findServerProvisioningPending]) — collector on-prem tak punya rute ke radius-db
     * internal. DISPATCHED ikut agar perintah yang belum di-ACK dikirim ulang
     * (at-least-once). Terurut waktu minta agar urutannya stabil.
     */
    fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction>

    /**
     * Aksi jalur-DATA RADIUS ([BngActionType.PROVISIONING]) yang masih PENDING untuk tenant
     * aktif (RLS) — diklaim worker server-side untuk ditulis ke radius-db platform. Tak
     * pakai status DISPATCHED (worker mengeksekusi sinkron lalu menuntaskan sendiri).
     * Dibatasi [limit] agar satu tenant tak memonopoli satu putaran; terurut waktu minta.
     */
    fun findServerProvisioningPending(limit: Int): List<BngAction>
}
