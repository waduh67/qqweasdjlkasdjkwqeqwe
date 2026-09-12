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

    /**
     * Pemegang sebuah peran. Ditulis sebagai `member of` atas koleksi elemen `roleIds`, BUKAN
     * query native ke `user_role`: lewat JPQL, filter tenant milik Hibernate ikut terpasang
     * otomatis, sementara query native harus mengandalkan RLS saja — dan kalau GUC
     * `app.tenant_id` kebetulan kosong, RLS memulangkan NOL baris TANPA error, yang di sini
     * berarti "tidak ada approver" padahal approvernya ada.
     */
    @Query("select u from UserJpaEntity u where :roleId member of u.roleIds")
    fun findAllByRoleId(@Param("roleId") roleId: UUID): List<UserJpaEntity>
}
