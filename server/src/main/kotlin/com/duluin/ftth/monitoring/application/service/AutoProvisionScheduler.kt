package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.infrastructure.audit.AuditRecorder
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.monitoring.application.port.inbound.ProvisioningSuggestion
import com.duluin.ftth.monitoring.application.port.inbound.SuggestionConfidence
import com.duluin.ftth.monitoring.application.port.outbound.AutoProvisionPolicyRepository
import com.duluin.ftth.monitoring.application.port.outbound.DiscoveredOnuRepository
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnu
import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Auto-provisioning zero-touch: memindai kotak masuk tiap tenant dan menautkan
 * sendiri ONU liar yang tebakannya sudah pasti (keyakinan HIGH) — bila tenant
 * menyalakannya.
 *
 * Dijalankan pemindai terjadwal, bukan disisipkan ke jalur ingestion, justru
 * karena sinyal terkuatnya (WO PSB terbuka) sering baru dibuat SETELAH ONU
 * terlihat pertama kali: sapuan berkala menangkap yang tadinya belum bisa ditebak
 * begitu order pasangnya muncul. Berjalan lintas tenant lewat [TenantApi], tenant
 * context dipasang per tenant; kegagalan satu tenant tak menghentikan yang lain.
 */
@Component
class AutoProvisionScheduler(
    private val tenantApi: TenantApi,
    private val sweeper: AutoProvisionSweeper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.monitoring.auto-provision-interval:PT2M}")
    fun sweepAll() {
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching {
                TenantContext.runAs(tenantId) { sweeper.sweep(tenantId) }
            }.onFailure {
                log.warn("Auto-provisioning tenant {} gagal: {}", tenantId, it.message)
            }
        }
    }
}

/**
 * Sapuan satu tenant: bila kebijakannya menyala, resolusi seluruh baris menunggu
 * dan tautkan yang HIGH lagi lengkap.
 *
 * Komponen terpisah dari [AutoProvisionScheduler] agar `@Transactional` benar-benar
 * berlaku (proxy Spring tak membungkus pemanggilan sesama-kelas). Transaksinya
 * read-only REQUIRES_NEW — hanya membaca kebijakan, kotak masuk, dan menghitung
 * saran; penautan nyata dilakukan [AutoProvisioner] di transaksinya sendiri
 * sehingga kegagalan satu ONU tidak menggulung yang lain.
 */
@Component
class AutoProvisionSweeper(
    private val policyRepository: AutoProvisionPolicyRepository,
    private val discoveredRepository: DiscoveredOnuRepository,
    private val resolver: OnuProvisioningResolver,
    private val provisioner: AutoProvisioner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun sweep(tenantId: UUID) {
        if (policyRepository.find()?.enabled != true) return
        val waiting = discoveredRepository.findByState(DiscoveredOnuState.DISCOVERED)
        if (waiting.isEmpty()) return

        val suggestions = resolver.resolveAll(waiting)
        val ready = waiting.filter { onu ->
            val s = suggestions[onu.id]
            s != null && s.confidence == SuggestionConfidence.HIGH && s.complete
        }
        if (ready.isEmpty()) return

        log.info("{} ONU auto-provisi (HIGH) untuk tenant {}", ready.size, tenantId)
        ready.forEach { onu ->
            runCatching { provisioner.provision(tenantId, onu, suggestions.getValue(onu.id)) }
                .onFailure { log.warn("Auto-provisi ONU {} tenant {} gagal: {}", onu.serialNumber, tenantId, it.message) }
        }
    }
}

/**
 * Menautkan SATU ONU liar di transaksinya sendiri (REQUIRES_NEW): daftarkan +
 * pasang lewat [CustomerApi], tandai barisnya PROVISIONED, dan catat jejak audit.
 *
 * Transaksi terisolasi per ONU supaya satu kegagalan (mis. port keburu terpakai
 * sapuan lain) tidak menggulung penautan ONU lain di sapuan yang sama. Actor audit
 * kosong = "system": penautan ini memang keputusan sistem, bukan operator.
 */
@Component
class AutoProvisioner(
    private val discoveredRepository: DiscoveredOnuRepository,
    private val customerApi: CustomerApi,
    private val auditor: AuditRecorder,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun provision(tenantId: UUID, onu: DiscoveredOnu, suggestion: ProvisioningSuggestion) {
        customerApi.provisionOnu(
            ProvisionOnuCommand(
                serialNumber = onu.serialNumber,
                model = null,
                customerId = suggestion.customerId!!,
                odpId = suggestion.odpId!!,
                portNumber = suggestion.portNumber!!,
                installRxPowerDbm = onu.lastRxPowerDbm,
            ),
        )
        onu.markProvisioned()
        discoveredRepository.save(onu)
        auditor.record(
            action = "monitoring.onu.auto_provisioned",
            entityType = "DiscoveredOnu",
            entityId = onu.id,
            tenantId = tenantId,
            detail = mapOf(
                "serialNumber" to onu.serialNumber,
                "customerId" to suggestion.customerId.toString(),
                "odpId" to suggestion.odpId.toString(),
                "portNumber" to suggestion.portNumber,
                "reason" to suggestion.reason,
            ),
        )
    }
}
