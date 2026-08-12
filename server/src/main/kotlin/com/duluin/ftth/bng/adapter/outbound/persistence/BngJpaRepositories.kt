package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.AuthType
import com.duluin.ftth.bng.domain.model.BngActionStatus
import com.duluin.ftth.bng.domain.model.BngActionType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface NasJpaRepository : JpaRepository<NasJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<NasJpaEntity>
    fun existsByName(name: String): Boolean
    fun findFirstByNameIgnoreCase(name: String): NasJpaEntity?
}

interface NasAreaJpaRepository : JpaRepository<NasAreaJpaEntity, UUID> {
    fun findByNasId(nasId: UUID): List<NasAreaJpaEntity>
    fun findByNasIdIn(nasIds: Collection<UUID>): List<NasAreaJpaEntity>
    fun findByAreaId(areaId: UUID): NasAreaJpaEntity?
    fun deleteByNasId(nasId: UUID)
}

interface SubscriberAccessJpaRepository : JpaRepository<SubscriberAccessJpaEntity, UUID> {
    fun findAllByOrderByUsernameAsc(): List<SubscriberAccessJpaEntity>
    fun findByCustomerIdOrderByUsernameAsc(customerId: UUID): List<SubscriberAccessJpaEntity>
    fun findByCustomerIdInOrderByUsernameAsc(customerIds: Collection<UUID>): List<SubscriberAccessJpaEntity>
    fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccessJpaEntity>
    fun findByUsername(username: String): SubscriberAccessJpaEntity?
    fun findByNasIdOrderByUsernameAsc(nasId: UUID): List<SubscriberAccessJpaEntity>
    fun findByPlanId(planId: UUID): List<SubscriberAccessJpaEntity>
    fun findByStatusAndNasIdIsNotNull(status: AccessStatus): List<SubscriberAccessJpaEntity>

    /** Username akun berstatus [status] & bertipe salah satu [authTypes] — proyeksi ringan (hanya username). */
    @Query(
        "SELECT a.username FROM SubscriberAccessJpaEntity a " +
            "WHERE a.status = :status AND a.authType IN :authTypes",
    )
    fun findUsernamesByStatusAndAuthTypeIn(
        @Param("status") status: AccessStatus,
        @Param("authTypes") authTypes: Collection<AuthType>,
    ): List<String>

    fun existsBySubscriptionId(subscriptionId: UUID): Boolean
    fun countByNasId(nasId: UUID): Long
}

interface RadiusSessionJpaRepository : JpaRepository<RadiusSessionJpaEntity, UUID> {
    fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSessionJpaEntity?

    fun findBySubscriberAccessIdIn(subscriberAccessIds: Collection<UUID>): List<RadiusSessionJpaEntity>

    /**
     * Sesi milik akun berstatus [status], disaring lewat subquery ke subscriber_access
     * (taut UUID polos, tanpa relasi JPA lintas-agregat). RLS memfilter kedua tabel ke
     * tenant aktif, jadi hasilnya ter-scope otomatis.
     */
    @Query(
        """
        SELECT s FROM RadiusSessionJpaEntity s
        WHERE s.subscriberAccessId IN (
            SELECT a.id FROM SubscriberAccessJpaEntity a WHERE a.status = :status
        )
        """,
    )
    fun findAllByAccountStatus(@Param("status") status: AccessStatus): List<RadiusSessionJpaEntity>
}

interface BngActionJpaRepository : JpaRepository<BngActionJpaEntity, UUID> {
    fun findByNasIdInAndActionInAndStatusInOrderByRequestedAtAsc(
        nasIds: Collection<UUID>,
        actions: Collection<BngActionType>,
        statuses: Collection<BngActionStatus>,
    ): List<BngActionJpaEntity>

    fun findByNasIdInAndActionInAndStatusInOrderByRequestedAtAsc(
        nasIds: Collection<UUID>,
        actions: Collection<BngActionType>,
        statuses: Collection<BngActionStatus>,
        pageable: Pageable,
    ): List<BngActionJpaEntity>

    fun findByActionInAndStatusInOrderByRequestedAtAsc(
        actions: Collection<BngActionType>,
        statuses: Collection<BngActionStatus>,
        pageable: Pageable,
    ): List<BngActionJpaEntity>
}
