package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.AccountingRecordPoint
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RadiusSession
import com.duluin.ftth.bng.domain.model.RateProfile
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.bng.domain.model.TrafficSample
import java.time.Instant
import java.util.UUID

/**
 * Port persistence module bng. Ketiga tabel tenant-aware (@TenantId + RLS), jadi
 * semua pencarian ter-scope tenant aktif secara otomatis — tak ada parameter
 * tenantId yang dibawa-bawa.
 */
interface RateProfileRepository {

    fun save(profile: RateProfile): RateProfile

    fun findById(id: UUID): RateProfile?

    /** Semua paket tenant aktif, terurut nama. */
    fun findAll(): List<RateProfile>

    fun existsByName(name: String): Boolean

    fun deleteById(id: UUID)
}

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

    fun existsBySubscriptionId(subscriptionId: UUID): Boolean

    /** Berapa akun yang masih memakai sebuah paket — untuk mencegah hapus paket terpakai. */
    fun countByRateProfileId(rateProfileId: UUID): Long

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
}
