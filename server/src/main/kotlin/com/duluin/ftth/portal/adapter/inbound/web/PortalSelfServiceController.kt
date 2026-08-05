package com.duluin.ftth.portal.adapter.inbound.web

import com.duluin.ftth.portal.application.port.inbound.PortalAccountView
import com.duluin.ftth.portal.application.port.inbound.PortalBillingView
import com.duluin.ftth.portal.application.port.inbound.PortalConnectionView
import com.duluin.ftth.portal.application.port.inbound.PortalSelfServiceUseCase
import com.duluin.ftth.portal.security.CurrentPortalCustomer
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Query self-service pelanggan (path berawalan `/api/portal/me/`). Semua endpoint MEMBACA
 * data pelanggan yang SEDANG login — id diambil dari principal portal, tak pernah dari path
 * atau query, sehingga pelanggan mustahil membaca data pelanggan lain. Rantai keamanan +
 * dekoder token: [com.duluin.ftth.portal.adapter.inbound.security.PortalSecurityConfig].
 */
@RestController
@RequestMapping("/api/portal/me")
@Tag(name = "Portal")
class PortalSelfServiceController(
    private val selfService: PortalSelfServiceUseCase,
    private val currentPortalCustomer: CurrentPortalCustomer,
) {

    /** Profil + langganan beserta detail paket (Profil & paket). */
    @GetMapping("/profile")
    fun profile(): PortalAccountView = selfService.profile(currentCustomerId())

    /** Ringkasan rekening + tagihan (tautan bayar) + riwayat pembayaran (Tagihan & Bayar online). */
    @GetMapping("/billing")
    fun billing(): PortalBillingView = selfService.billing(currentCustomerId())

    /** Sesi PPPoE terkini + perangkat CPE (Status koneksi). */
    @GetMapping("/connection")
    fun connection(): PortalConnectionView = selfService.connection(currentCustomerId())

    private fun currentCustomerId() = currentPortalCustomer.current().customerId
}
