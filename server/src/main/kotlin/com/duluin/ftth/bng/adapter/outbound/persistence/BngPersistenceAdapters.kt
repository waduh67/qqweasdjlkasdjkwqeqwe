package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.RateProfileRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.RateProfile
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RateProfilePersistenceAdapter(
    private val jpa: RateProfileJpaRepository,
) : RateProfileRepository {

    override fun save(profile: RateProfile): RateProfile {
        val entity = jpa.findById(profile.id).orElse(null)?.apply {
            name = profile.name
            description = profile.description
            downMbps = profile.downMbps
            upMbps = profile.upMbps
            radiusProfileName = profile.radiusProfileName
        } ?: RateProfileJpaEntity(
            id = profile.id,
            name = profile.name,
            description = profile.description,
            downMbps = profile.downMbps,
            upMbps = profile.upMbps,
            radiusProfileName = profile.radiusProfileName,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): RateProfile? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<RateProfile> = jpa.findAllByOrderByNameAsc().map { it.toDomain() }

    override fun existsByName(name: String): Boolean = jpa.existsByName(name)

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

/**
 * Adapter NAS sekaligus batas enkripsi: domain memegang secret CoA apa adanya,
 * database hanya pernah melihat ciphertext (sama seperti community SNMP OLT).
 */
@Component
class NasPersistenceAdapter(
    private val jpa: NasJpaRepository,
    private val cipher: SecretCipher,
) : NasRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(nas: Nas): Nas {
        val encryptedCoaSecret = nas.coaSecret?.let(cipher::encrypt)
        val entity = jpa.findById(nas.id).orElse(null)?.apply {
            name = nas.name
            vendor = nas.vendor
            address = nas.address
            nasIdentifier = nas.nasIdentifier
            coaSecret = encryptedCoaSecret
            collectorId = nas.collectorId
            enabled = nas.enabled
        } ?: NasJpaEntity(
            id = nas.id,
            name = nas.name,
            vendor = nas.vendor,
            address = nas.address,
            nasIdentifier = nas.nasIdentifier,
            coaSecret = encryptedCoaSecret,
            collectorId = nas.collectorId,
            enabled = nas.enabled,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Nas? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findAll(): List<Nas> = jpa.findAllByOrderByNameAsc().map { it.toDomain() }

    override fun existsByName(name: String): Boolean = jpa.existsByName(name)

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    private fun NasJpaEntity.toDomain(): Nas = Nas.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        name = name,
        vendor = vendor,
        address = address,
        nasIdentifier = nasIdentifier,
        coaSecret = cipher.decryptQuietly(coaSecret, name, log),
        collectorId = collectorId,
        enabled = enabled,
    )
}

/**
 * Adapter akun PPPoE sekaligus batas enkripsi password. Saat memperbarui, password
 * hanya ditulis ulang bila domain memegang nilai asli — sentinel kosong dari
 * kegagalan dekripsi tidak menimpa ciphertext yang ada, agar penyuntingan field
 * lain tak merusak password.
 */
@Component
class SubscriberAccessPersistenceAdapter(
    private val jpa: SubscriberAccessJpaRepository,
    private val cipher: SecretCipher,
) : SubscriberAccessRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun save(access: SubscriberAccess): SubscriberAccess {
        val encryptedSecret = access.secret.takeIf { it.isNotBlank() }?.let(cipher::encrypt)
        val entity = jpa.findById(access.id).orElse(null)?.apply {
            // Identitas (subscriptionId, customerId, username, authType) tak disentuh.
            rateProfileId = access.rateProfileId
            nasId = access.nasId
            status = access.status
            if (encryptedSecret != null) secret = encryptedSecret
        } ?: SubscriberAccessJpaEntity(
            id = access.id,
            subscriptionId = access.subscriptionId,
            customerId = access.customerId,
            username = access.username,
            authType = access.authType,
            secret = encryptedSecret ?: error("Password akun jaringan wajib diisi"),
            rateProfileId = access.rateProfileId,
            nasId = access.nasId,
            status = access.status,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): SubscriberAccess? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByCustomerId(customerId: UUID): List<SubscriberAccess> =
        jpa.findByCustomerIdOrderByUsernameAsc(customerId).map { it.toDomain() }

    override fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccess> =
        jpa.findBySubscriptionId(subscriptionId).map { it.toDomain() }

    override fun findByUsername(username: String): SubscriberAccess? =
        jpa.findByUsername(username)?.toDomain()

    override fun findByNasId(nasId: UUID): List<SubscriberAccess> =
        jpa.findByNasIdOrderByUsernameAsc(nasId).map { it.toDomain() }

    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean =
        jpa.existsBySubscriptionId(subscriptionId)

    override fun countByRateProfileId(rateProfileId: UUID): Long = jpa.countByRateProfileId(rateProfileId)

    override fun countByNasId(nasId: UUID): Long = jpa.countByNasId(nasId)

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    private fun SubscriberAccessJpaEntity.toDomain(): SubscriberAccess = SubscriberAccess.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
        subscriptionId = subscriptionId,
        customerId = customerId,
        username = username,
        authType = authType,
        // Password tak pernah dibaca balik lewat API; sentinel kosong bila tak
        // terdekripsi tidak masalah untuk baca, dan save menjaganya agar tak menimpa.
        secret = cipher.decryptQuietly(secret, username, log) ?: "",
        rateProfileId = rateProfileId,
        nasId = nasId,
        status = status,
    )
}

private fun RateProfileJpaEntity.toDomain(): RateProfile = RateProfile.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    name = name,
    description = description,
    downMbps = downMbps,
    upMbps = upMbps,
    radiusProfileName = radiusProfileName,
)

/**
 * Rahasia yang tidak bisa didekripsi (mis. kunci dirotasi tanpa migrasi) tidak
 * boleh menggagalkan pemuatan seluruh daftar; barisnya tetap tampil, hanya
 * kehilangan rahasianya dan bisa diisi ulang operator.
 */
private fun SecretCipher.decryptQuietly(ciphertext: String?, label: String, log: Logger): String? {
    if (ciphertext == null) return null
    return runCatching { decrypt(ciphertext) }
        .onFailure { log.warn("Rahasia bng untuk '{}' tidak bisa didekripsi; perlu diisi ulang", label) }
        .getOrNull()
}
