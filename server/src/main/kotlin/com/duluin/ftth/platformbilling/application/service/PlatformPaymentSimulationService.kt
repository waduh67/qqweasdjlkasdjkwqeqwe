package com.duluin.ftth.platformbilling.application.service

import com.duluin.ftth.billing.application.service.PaymentGatewayRegistry
import com.duluin.ftth.billing.application.service.PivotMasterConfigProvider
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.platformbilling.application.port.inbound.SimulatePaymentCommand
import com.duluin.ftth.platformbilling.application.port.inbound.SimulatePaymentResult
import com.duluin.ftth.platformbilling.application.port.inbound.SimulatePaymentUseCase
import com.duluin.ftth.platformbilling.application.port.inbound.SimulationAvailability
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Panel simulasi pembayaran super-admin: memanggil endpoint simulasi penyedia atas id sesi bayar
 * yang diketik langsung. Memakai konteks gateway MASTER ([PlatformGatewayResolver]) — akun yang sama
 * yang menerbitkan seluruh sesi bayar aplikasi ini, baik langganan SaaS (langsung di master) maupun
 * tagihan pelanggan tenant (on-behalf sub-account).
 *
 * Penjagaan sandbox ada di adapter penyedia ([com.duluin.ftth.billing.adapter.outbound.gateway.PivotPaymentGateway]);
 * di sini disaring lebih awal lewat [availability] agar UI bisa menonaktifkan form dengan alasan jelas.
 */
@Service
@Transactional(readOnly = true)
class PlatformPaymentSimulationService(
    private val resolver: PlatformGatewayResolver,
    private val gatewayRegistry: PaymentGatewayRegistry,
    private val masterConfig: PivotMasterConfigProvider,
    private val tenantApi: TenantApi,
    private val auditor: AuditRecorder,
) : SimulatePaymentUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun availability(): SimulationAvailability {
        val master = masterConfig.current()
        val sandbox = master?.sandbox == true
        return SimulationAvailability(
            available = master != null && sandbox,
            configured = master != null,
            sandbox = sandbox,
            reason = when {
                master == null -> "Pivot master belum dikonfigurasi/diaktifkan di setelan platform"
                !sandbox -> "Pivot master sedang mode produksi — simulasi hanya tersedia di sandbox"
                else -> null
            },
        )
    }

    override fun simulate(command: SimulatePaymentCommand): SimulatePaymentResult {
        val sessionId = command.paymentSessionId.trim().takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Payment session ID wajib diisi")
        // Konteks master; sub-account opsional agar sesi milik tenant (charge on-behalf) juga bisa
        // disimulasikan dari sini — resolver sendiri selalu mengembalikan subAccountId null.
        val base = resolver.resolveActive()
        val ctx = command.subMerchantId?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { base.copy(subAccountId = it) }
            ?: base
        val gateway = gatewayRegistry.forProvider(ctx.provider)
            ?: error("Adapter gateway '${ctx.provider}' tidak tersedia")
        gateway.simulateCharge(sessionId, command.status, ctx)
        auditor.record(
            action = "platform.payment.simulated",
            entityType = "PaymentSession",
            // Id sesi Pivot bukan UUID; jejak audit butuh UUID → turunkan deterministik dari id sesi
            // (nilai aslinya tetap tercatat apa adanya di `detail`).
            entityId = UUID.nameUUIDFromBytes(sessionId.toByteArray()),
            tenantId = tenantApi.platformTenantId(),
            detail = mapOf(
                "paymentSessionId" to sessionId,
                "chargeStatus" to command.status.name,
                "subMerchantId" to (ctx.subAccountId ?: "-"),
            ),
        )
        log.info("Simulasi pembayaran platform dikirim: sesi={} status={}", sessionId, command.status)
        return SimulatePaymentResult(sessionId, command.status, ctx.provider)
    }
}
