package com.duluin.ftth.platformbilling.adapter.outbound.persistence

import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PlatformPaymentGatewayJpaRepository : JpaRepository<PlatformPaymentGatewayJpaEntity, UUID> {
    fun findByProvider(provider: PlatformPaymentProvider): PlatformPaymentGatewayJpaEntity?
}
