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
}
