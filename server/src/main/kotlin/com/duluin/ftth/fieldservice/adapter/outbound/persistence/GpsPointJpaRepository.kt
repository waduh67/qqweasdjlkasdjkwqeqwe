package com.duluin.ftth.fieldservice.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface GpsPointJpaRepository : JpaRepository<GpsPointJpaEntity, UUID> {
    fun findByTenantIdAndOperationNamespaceAndOperationKey(tenantId: UUID, namespace: String, key: String): GpsPointJpaEntity?
    fun findAllByServerReceivedAtBefore(cutoff: Instant): List<GpsPointJpaEntity>
}
