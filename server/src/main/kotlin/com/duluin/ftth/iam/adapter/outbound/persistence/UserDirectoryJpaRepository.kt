package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserDirectoryJpaRepository : JpaRepository<UserDirectoryJpaEntity, UUID> {

    fun findByEmailLower(emailLower: String): UserDirectoryJpaEntity?

    /**
     * User terlama sebuah tenant (dibuat pertama = admin onboarding). Non-RLS (entity extends
     * [com.duluin.ftth.common.infrastructure.persistence.BaseJpaEntity]) → aman dipanggil tanpa
     * tenant context. Urut `createdAt` (bukan id: kolom PK bernama `primaryKey` di metamodel).
     */
    fun findFirstByTenantIdOrderByCreatedAtAsc(tenantId: UUID): UserDirectoryJpaEntity?
}
