package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.application.port.outbound.BngActionRepository
import com.duluin.ftth.bng.application.port.outbound.NasRepository
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.BngAction
import com.duluin.ftth.bng.domain.model.BngActionStatus
import com.duluin.ftth.bng.domain.model.BngActionType
import com.duluin.ftth.bng.domain.model.Nas
import com.duluin.ftth.bng.domain.model.SubscriberAccess
import com.duluin.ftth.common.security.SecretCipher
import com.duluin.ftth.common.tenant.TenantContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.util.UUID

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
        val encryptedApiSecret = nas.apiSecret?.let(cipher::encrypt)
        val entity = jpa.findById(nas.id).orElse(null)?.apply {
            name = nas.name
            vendor = nas.vendor
            address = nas.address
            nasIdentifier = nas.nasIdentifier
            coaSecret = encryptedCoaSecret
            collectorId = nas.collectorId
            enabled = nas.enabled
            apiUsername = nas.apiUsername
            apiSecret = encryptedApiSecret
            apiPort = nas.apiPort
            apiUseTls = nas.apiUseTls
            apiDatabase = nas.apiDatabase
            reachability = nas.reachability
        } ?: NasJpaEntity(
            id = nas.id,
            name = nas.name,
            vendor = nas.vendor,
            address = nas.address,
            nasIdentifier = nas.nasIdentifier,
            coaSecret = encryptedCoaSecret,
            collectorId = nas.collectorId,
            enabled = nas.enabled,
            apiUsername = nas.apiUsername,
            apiSecret = encryptedApiSecret,
            apiPort = nas.apiPort,
            apiUseTls = nas.apiUseTls,
            apiDatabase = nas.apiDatabase,
            reachability = nas.reachability,
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
        apiUsername = apiUsername,
        apiSecret = cipher.decryptQuietly(apiSecret, name, log),
        apiPort = apiPort,
        apiUseTls = apiUseTls,
        apiDatabase = apiDatabase,
        reachability = reachability,
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
            planId = access.planId
            nasId = access.nasId
            status = access.status
            fupThrottled = access.fupThrottled
            if (encryptedSecret != null) secret = encryptedSecret
        } ?: SubscriberAccessJpaEntity(
            id = access.id,
            subscriptionId = access.subscriptionId,
            customerId = access.customerId,
            username = access.username,
            authType = access.authType,
            secret = encryptedSecret ?: error("Password akun jaringan wajib diisi"),
            planId = access.planId,
            nasId = access.nasId,
            status = access.status,
            fupThrottled = access.fupThrottled,
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

    override fun findByPlanId(planId: UUID): List<SubscriberAccess> =
        jpa.findByPlanId(planId).map { it.toDomain() }

    override fun findActiveOnNas(): List<SubscriberAccess> =
        jpa.findByStatusAndNasIdIsNotNull(AccessStatus.ACTIVE).map { it.toDomain() }

    override fun existsBySubscriptionId(subscriptionId: UUID): Boolean =
        jpa.existsBySubscriptionId(subscriptionId)

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
        planId = planId,
        nasId = nasId,
        status = status,
        fupThrottled = fupThrottled,
    )
}

/**
 * Adapter antrean/audit perintah BRAS. Tanpa rahasia untuk dienkripsi — perintah
 * hanya menaut akun & BRAS lewat UUID. Saat memperbarui, hanya kolom daur-hidup
 * (status/detail/waktu) yang ditulis ulang; identitasnya `updatable = false` di entity.
 */
@Component
class BngActionPersistenceAdapter(
    private val jpa: BngActionJpaRepository,
) : BngActionRepository {

    override fun save(action: BngAction): BngAction {
        val entity = jpa.findById(action.id).orElse(null)?.apply {
            status = action.status
            detail = action.detail
            dispatchedAt = action.dispatchedAt
            completedAt = action.completedAt
        } ?: BngActionJpaEntity(
            id = action.id,
            subscriberAccessId = action.subscriberAccessId,
            nasId = action.nasId,
            username = action.username,
            action = action.action,
            downMbps = action.downMbps,
            upMbps = action.upMbps,
            groupname = action.groupname,
            rateLimit = action.rateLimit,
            simultaneousUse = action.simultaneousUse,
            fupGroupname = action.fupGroupname,
            fupRateLimit = action.fupRateLimit,
            status = action.status,
            detail = action.detail,
            requestedBy = action.requestedBy,
            requestedByEmail = action.requestedByEmail,
            requestedAt = action.requestedAt,
            dispatchedAt = action.dispatchedAt,
            completedAt = action.completedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): BngAction? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findDispatchableByNasIds(nasIds: Collection<UUID>): List<BngAction> {
        if (nasIds.isEmpty()) return emptyList()
        return jpa.findByNasIdInAndActionInAndStatusInOrderByRequestedAtAsc(
            nasIds,
            BngActionType.SESSION_CONTROL,
            listOf(BngActionStatus.PENDING, BngActionStatus.DISPATCHED),
        ).map { it.toDomain() }
    }

    override fun findServerProvisioningPending(limit: Int): List<BngAction> =
        jpa.findByActionInAndStatusInOrderByRequestedAtAsc(
            BngActionType.PROVISIONING,
            listOf(BngActionStatus.PENDING),
            PageRequest.of(0, limit),
        ).map { it.toDomain() }

    override fun findServerSessionControlPending(nasIds: Collection<UUID>, limit: Int): List<BngAction> {
        if (nasIds.isEmpty()) return emptyList()
        return jpa.findByNasIdInAndActionInAndStatusInOrderByRequestedAtAsc(
            nasIds,
            BngActionType.SESSION_CONTROL,
            listOf(BngActionStatus.PENDING),
            PageRequest.of(0, limit),
        ).map { it.toDomain() }
    }

    private fun BngActionJpaEntity.toDomain(): BngAction = BngAction.rehydrate(
        id = id,
        tenantId = tenantId ?: TenantContext.tenantId(),
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
        status = status,
        detail = detail,
        requestedBy = requestedBy,
        requestedByEmail = requestedByEmail,
        requestedAt = requestedAt,
        dispatchedAt = dispatchedAt,
        completedAt = completedAt,
    )
}

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
