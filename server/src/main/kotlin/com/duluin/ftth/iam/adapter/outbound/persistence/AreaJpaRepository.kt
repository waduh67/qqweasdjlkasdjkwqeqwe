package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import org.springframework.data.repository.query.Param
import java.util.UUID

interface AreaJpaRepository : JpaRepository<AreaJpaEntity, UUID> {
    fun existsByCode(code: String): Boolean

    @Query("""
        select a from AreaJpaEntity a where a.tenantId = :tenant
          and (:unrestricted = true or a.id in :ids)
          and (lower(a.name) like concat('%', :q, '%') escape '!'
            or lower(a.code) like concat('%', :q, '%') escape '!')
        order by lower(a.name), a.id
    """)
    fun search(@Param("tenant") tenant: UUID, @Param("unrestricted") unrestricted: Boolean,
        @Param("ids") ids: Set<UUID>, @Param("q") query: String, pageable: Pageable): Page<AreaJpaEntity>
}
