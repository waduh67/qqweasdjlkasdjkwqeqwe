package com.duluin.ftth.billing.application.port.outbound

import com.duluin.ftth.billing.domain.model.TenantPaymentGateway

/**
 * Penyimpanan setelan payment gateway per-tenant. Satu baris per tenant; [find] mengambil
 * baris tunggal hasil saring RLS untuk tenant aktif (null bila belum pernah disetel).
 */
interface TenantPaymentGatewayRepository {
    fun find(): TenantPaymentGateway?
    fun save(settings: TenantPaymentGateway): TenantPaymentGateway
}
