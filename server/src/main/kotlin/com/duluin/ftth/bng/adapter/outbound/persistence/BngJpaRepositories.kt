package com.duluin.ftth.bng.adapter.outbound.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RateProfileJpaRepository : JpaRepository<RateProfileJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<RateProfileJpaEntity>
    fun existsByName(name: String): Boolean
}

interface NasJpaRepository : JpaRepository<NasJpaEntity, UUID> {
    fun findAllByOrderByNameAsc(): List<NasJpaEntity>
    fun existsByName(name: String): Boolean
}

interface SubscriberAccessJpaRepository : JpaRepository<SubscriberAccessJpaEntity, UUID> {
    fun findByCustomerIdOrderByUsernameAsc(customerId: UUID): List<SubscriberAccessJpaEntity>
    fun findBySubscriptionId(subscriptionId: UUID): List<SubscriberAccessJpaEntity>
    fun findByUsername(username: String): SubscriberAccessJpaEntity?
    fun findByNasIdOrderByUsernameAsc(nasId: UUID): List<SubscriberAccessJpaEntity>
    fun existsBySubscriptionId(subscriptionId: UUID): Boolean
    fun countByRateProfileId(rateProfileId: UUID): Long
    fun countByNasId(nasId: UUID): Long
}

interface RadiusSessionJpaRepository : JpaRepository<RadiusSessionJpaEntity, UUID> {
    fun findBySubscriberAccessId(subscriberAccessId: UUID): RadiusSessionJpaEntity?
}
