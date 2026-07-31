package com.duluin.ftth.bng.adapter.outbound.persistence

import com.duluin.ftth.bng.domain.model.AccessStatus
import com.duluin.ftth.bng.domain.model.BngActionStatus
import com.duluin.ftth.bng.domain.model.BngActionType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NasJpaRepository : JpaRepository<NasJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<NasJpaEntity>
    fun existsByName(name: String): Boolean
}

interface NasAreaJpaRepository : JpaRepository<NasAreaJpaEntity, UUID> {
    fun findByNasId(nasId: UUID): List<NasAreaJpaEntity>
    fun findByNasIdIn(nasIds: Collection<UUID>): List<NasAreaJpaEntity>
    fun findByAreaId(areaId: UUID): NasAreaJpaEntity?
    fun deleteByNasId(nasId: UUID)
}

interface SubscriberAccessJpaRepository : JpaRepository<SubscriberAccessJpaEntity, UUID> {
    fun findByCustomerIdOrderByUsernameAsc(customerId: UUID): List<SubscriberAccessJpaEntity>
    fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccessJpaEntity>
    fun findByUsername(username: String): SubscriberAccessJpaEntity?
    fun findByNasIdOrderByUsernameAsc(nasId: UUID): List<SubscriberAccessJpaEntity>
    fun findByPlanId(planId: UUID): List<SubscriberAccessJpaEntity>
    fun findByStatusAndNasIdIsNotNull(status: AccessStatus): List<SubscriberAccessJpaEntity>
    fun existsBySubscriptionId(subscriptionId: UUID): Boolean
    fun countByNasId(nasId: UUID): Long
}

interface RadiusSessionJpaRepository : JpaRepository<RadiusSessionJpaEntity, UUID> {
    fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSessionJpaEntity?
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
