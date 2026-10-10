package com.duluin.ftth.iam.adapter.outbound.persistence

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserJpaRepository : JpaRepository<UserJpaEntity, UUID> {

    fun findByEmail(email: String): UserJpaEntity?

    fun existsByEmail(email: String): Boolean

    /**
     * Pencarian nama/email; tenant otomatis difilter Hibernate + RLS.
     * [q] selalu berupa string (kosong = cocokkan semua) agar Postgres bisa
     * meng-infer tipe parameter (null tak-bertipe → error `lower(bytea)`).
     */
    @Query(
        """
        select u from UserJpaEntity u
        where lower(u.name) like concat('%', :q, '%')
           or u.email like concat('%', :q, '%')
        """,
    )
    fun search(@Param("q") q: String, pageable: Pageable): Page<UserJpaEntity>

    @Query(value = """
        SELECT u.* FROM app_user u WHERE u.tenant_id=:tenantId AND u.status='ACTIVE' AND NOT u.platform_admin
          AND (lower(u.name) LIKE concat('%',:q,'%') OR lower(u.email) LIKE concat('%',:q,'%'))
          AND EXISTS(SELECT FROM user_role ur JOIN role r ON r.id=ur.role_id AND r.tenant_id=u.tenant_id
                     WHERE ur.user_id=u.id AND r.default_key='TECHNICIAN_NE')
          AND NOT EXISTS(SELECT FROM user_role ur JOIN role r ON r.id=ur.role_id
                     WHERE ur.user_id=u.id AND r.default_key IS DISTINCT FROM 'TECHNICIAN_NE')
          AND NOT EXISTS(SELECT FROM iam_tenant_owner o WHERE o.tenant_id=u.tenant_id AND o.user_id=u.id)
        ORDER BY lower(u.name),u.id
        """, countQuery = """
        SELECT count(*) FROM app_user u WHERE u.tenant_id=:tenantId AND u.status='ACTIVE' AND NOT u.platform_admin
          AND (lower(u.name) LIKE concat('%',:q,'%') OR lower(u.email) LIKE concat('%',:q,'%'))
          AND EXISTS(SELECT FROM user_role ur JOIN role r ON r.id=ur.role_id AND r.tenant_id=u.tenant_id
                     WHERE ur.user_id=u.id AND r.default_key='TECHNICIAN_NE')
          AND NOT EXISTS(SELECT FROM user_role ur JOIN role r ON r.id=ur.role_id
                     WHERE ur.user_id=u.id AND r.default_key IS DISTINCT FROM 'TECHNICIAN_NE')
          AND NOT EXISTS(SELECT FROM iam_tenant_owner o WHERE o.tenant_id=u.tenant_id AND o.user_id=u.id)
        """, nativeQuery = true)
    fun searchNetworkEngineers(@Param("tenantId") tenantId: UUID, @Param("q") q: String, pageable: Pageable): Page<UserJpaEntity>
}
