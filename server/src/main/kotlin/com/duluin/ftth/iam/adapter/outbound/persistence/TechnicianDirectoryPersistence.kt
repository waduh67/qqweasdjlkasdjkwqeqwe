package com.duluin.ftth.iam.adapter.outbound.persistence

import com.duluin.ftth.common.domain.Page
import com.duluin.ftth.common.domain.PageRequest
import com.duluin.ftth.common.infrastructure.persistence.toDomainPage
import com.duluin.ftth.common.infrastructure.persistence.toPageable
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.iam.application.port.outbound.TechnicianChoice
import com.duluin.ftth.iam.application.port.outbound.TechnicianDirectoryStore
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import java.util.UUID

interface TechnicianDirectoryJpaRepository : Repository<UserJpaEntity, UUID> {
    @Query(
        """
        select new com.duluin.ftth.iam.application.port.outbound.TechnicianChoice(u.id, u.name, u.email)
        from UserJpaEntity u
        where u.tenantId = :tenant and u.status = com.duluin.ftth.iam.domain.model.UserStatus.ACTIVE
          and u.platformAdmin = false and (:ownerId is null or u.id <> :ownerId)
          and (lower(u.name) like concat('%', :q, '%') escape '!'
            or lower(u.email) like concat('%', :q, '%') escape '!')
          and exists (select r.id from RoleJpaEntity r
            where r.tenantId = u.tenantId and r.id member of u.roleIds
              and (r.defaultKey in ('TECHNICIAN_NE', 'TECHNICIAN_FO')
                or (:pureOnly = false and (r.defaultKey = 'TECHNICIAN_LEGACY' or r.name = 'Teknisi'))))
          and (:pureOnly = false or not exists (select other.id from RoleJpaEntity other
            where other.tenantId = u.tenantId and other.id member of u.roleIds
              and (other.defaultKey is null or other.defaultKey not in ('TECHNICIAN_NE', 'TECHNICIAN_FO'))))
        order by lower(u.name), u.id
        """,
    )
    fun search(
        @Param("tenant") tenant: UUID,
        @Param("q") query: String,
        @Param("pureOnly") pureOnly: Boolean,
        @Param("ownerId") ownerId: UUID?,
        pageable: Pageable,
    ): org.springframework.data.domain.Page<TechnicianChoice>
}

@Component
class TechnicianDirectoryPersistence(private val jpa: TechnicianDirectoryJpaRepository) : TechnicianDirectoryStore {
    override fun search(query: String, page: PageRequest, pureOnly: Boolean, ownerId: UUID?): Page<TechnicianChoice> =
        jpa.search(TenantContext.tenantId(), query.replace("!", "!!").replace("%", "!%").replace("_", "!_"),
            pureOnly, ownerId, page.toPageable()).toDomainPage()
}
