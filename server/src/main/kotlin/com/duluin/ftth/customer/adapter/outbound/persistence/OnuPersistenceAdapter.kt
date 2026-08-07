package com.duluin.ftth.customer.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.customer.domain.model.Onu
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OnuPersistenceAdapter(
    private val jpa: OnuJpaRepository,
) : OnuRepository {

    override fun save(onu: Onu): Onu {
        val entity = jpa.findById(onu.id).orElse(null)?.apply {
            odpId = onu.odpId
            odpPortNumber = onu.odpPortNumber
            model = onu.model
            installRxPowerDbm = onu.installRxPowerDbm
            status = onu.status
            installedAt = onu.installedAt
        } ?: OnuJpaEntity(
            id = onu.id,
            customerId = onu.customerId,
            serialNumber = onu.serialNumber,
            odpId = onu.odpId,
            odpPortNumber = onu.odpPortNumber,
            model = onu.model,
            installRxPowerDbm = onu.installRxPowerDbm,
            status = onu.status,
            installedAt = onu.installedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Onu? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByCustomerId(customerId: UUID): List<Onu> =
        jpa.findByCustomerIdOrderBySerialNumber(customerId).map { it.toDomain() }

    override fun findByCustomerIds(customerIds: Set<UUID>): List<Onu> =
        if (customerIds.isEmpty()) emptyList() else jpa.findByCustomerIdIn(customerIds).map { it.toDomain() }

    override fun findAllByIds(ids: Set<UUID>): List<Onu> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids).map { it.toDomain() }

    override fun findBySerialNumbers(serialNumbers: Set<String>): List<Onu> =
        if (serialNumbers.isEmpty()) emptyList()
        else jpa.findBySerialNumberIn(serialNumbers).map { it.toDomain() }

    override fun findByOdpId(odpId: UUID): List<Onu> =
        jpa.findByOdpIdOrderByOdpPortNumber(odpId).map { it.toDomain() }

    override fun findByOdpIds(odpIds: Set<UUID>): List<Onu> =
        if (odpIds.isEmpty()) emptyList()
        else jpa.findByOdpIdInOrderByOdpPortNumber(odpIds).map { it.toDomain() }

    override fun existsBySerialNumber(serialNumber: String): Boolean =
        jpa.existsBySerialNumber(serialNumber.trim().uppercase())

    override fun countByOdpIds(odpIds: Set<UUID>): Map<UUID, Long> =
        if (odpIds.isEmpty()) emptyMap()
        else jpa.countGroupedByOdp(odpIds).associate { it.odpId to it.total }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

internal fun OnuJpaEntity.toDomain(): Onu = Onu.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    customerId = customerId,
    serialNumber = serialNumber,
    odpId = odpId,
    odpPortNumber = odpPortNumber,
    model = model,
    installRxPowerDbm = installRxPowerDbm,
    status = status,
    installedAt = installedAt,
)
