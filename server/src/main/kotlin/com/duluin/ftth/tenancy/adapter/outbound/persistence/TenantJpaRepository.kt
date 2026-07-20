package com.duluin.ftth.tenancy.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TenantJpaRepository : JpaRepository<TenantJpaEntity, UUID> {
    fun findBySlug(slug: String): TenantJpaEntity?
}
