package com.duluin.ftth.platformbilling.application.port.outbound

import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentGateway
import com.duluin.ftth.platformbilling.domain.model.PlatformPaymentProvider

/** Akses kredensial gateway pembayaran level platform (satu baris per penyedia). */
interface PlatformPaymentGatewayRepository {
    fun findAll(): List<PlatformPaymentGateway>
    fun findByProvider(provider: PlatformPaymentProvider): PlatformPaymentGateway?
    fun save(gateway: PlatformPaymentGateway): PlatformPaymentGateway
}
