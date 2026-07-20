package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RoleJpaRepository : JpaRepository<RoleJpaEntity, UUID> {
    fun findByName(name: String): RoleJpaEntity?
    fun existsByName(name: String): Boolean
}
