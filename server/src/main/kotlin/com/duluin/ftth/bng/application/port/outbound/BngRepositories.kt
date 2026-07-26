package com.duluin.ftth.bng.application.port.outbound

import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RateProfile
import com.duluin.ftth.bng.domain.model.SubscriberAccess
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

    fun existsBySubscriptionId(subscriptionId: UUID): Boolean

    /** Berapa akun yang masih memakai sebuah paket — untuk mencegah hapus paket terpakai. */
    fun countByRateProfileId(rateProfileId: UUID): Long

    /** Berapa akun yang masih dinaungi sebuah BRAS — untuk mencegah hapus BRAS terpakai. */
    fun countByNasId(nasId: UUID): Long

    fun deleteById(id: UUID)
}
