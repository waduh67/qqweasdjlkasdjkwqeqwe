package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.contract.OltTarget
import com.duluin.ftth.contract.OnuReading
import com.duluin.ftth.monitoring.AlarmsChangedEvent
import com.duluin.ftth.monitoring.domain.model.AlarmKind
import com.duluin.ftth.network.NetworkApi
import com.duluin.ftth.network.OltPollingTarget
import com.duluin.ftth.snmp.AdapterRegistry
import com.duluin.ftth.snmp.GponSnmpAdapter
import com.duluin.ftth.snmp.HsgqEponSnmpAdapter
import com.duluin.ftth.snmp.MibProfiles
import com.duluin.ftth.snmp.OltAdapter
import com.duluin.ftth.snmp.ProbeResult
import com.duluin.ftth.snmp.SnmpReaderFactory
import com.duluin.ftth.snmp.SnmpSession
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Polling SNMP OLT langsung dari server, tanpa agent on-prem.
 *
 * Ini jalur bawaan untuk ISP yang mengekspos OLT-nya ke server — lewat IP publik
 * atau terowongan VPN kita. Adapter SNMP-nya (modul `:snmp`) sama persis dengan yang
 * dipakai agent [com.duluin.ftth.collector], jadi tidak ada logika penafsiran MIB yang
 * digandakan; yang berbeda hanya SIAPA yang menjalankan walk-nya dan dari mana.
 *
 * Berjalan lintas tenant lewat [TenantApi] — bukan lewat baris collector, karena mode
 * ini justru untuk tenant yang TIDAK punya collector. Tenant context dipasang per
 * tenant agar RLS menyaring OLT & metrik ke tenant yang benar; kegagalan satu tenant
 * tak menghentikan yang lain.
 *
 * Catatan operasi: bila sebuah tenant KEBETULAN juga menjalankan collector on-prem,
 * OLT-nya akan ter-polling dua kali (ganda). Untuk sekarang mode ini menyapu semua
 * tenant aktif; pemilahan per-tenant (server-side vs collector) menyusul lewat flag
 * saat kebutuhan itu nyata. Kill-switch sementara: `ftth.monitoring.server-poll-enabled`.
 */
@Component
class OltPollingScheduler(
    private val tenantApi: TenantApi,
    private val poller: ServerSideOltPoller,
    @Value("\${ftth.monitoring.server-poll-enabled:true}") private val enabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.monitoring.poll-interval:PT5M}")
    fun pollAll() {
        if (!enabled) return
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching {
                TenantContext.runAs(tenantId) { poller.pollTenant(tenantId) }
            }.onFailure {
                log.warn("Polling OLT server-side tenant {} gagal: {}", tenantId, it.message)
            }
        }
    }
}

/**
 * Memoll seluruh OLT satu tenant.
 *
 * SENGAJA tidak transaksional: walk SNMP bisa memakan detik per OLT, dan memegang
 * koneksi database seselama itu akan menguras pool. Jadi I/O jaringan dikerjakan di
 * sini tanpa transaksi, lalu hasil tiap OLT dititipkan ke [OltReadingPersister] yang
 * membukanya di transaksi tersendiri. RLS tetap aktif karena tenant context sudah
 * dipasang pemanggil ([OltPollingScheduler]) — tiap panggilan [networkApi]/persister
 * membuka koneksinya sendiri di bawah tenant itu.
 */
@Component
class ServerSideOltPoller(
    private val networkApi: NetworkApi,
    private val adapterRegistry: AdapterRegistry,
    private val persister: OltReadingPersister,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun pollTenant(tenantId: UUID) {
        val targets = networkApi.findPollingTargets(networkApi.listAllOltIds())
            // OLT tanpa alamat manajemen tak punya apa pun untuk dihubungi.
            .filter { it.pollable }
        if (targets.isEmpty()) return

        for (target in targets) {
            val adapter = adapterRegistry.forVendor(target.vendor)
            if (adapter == null) {
                // Vendor tak dikenal: perangkat boleh tetap ada di inventory, hanya
                // belum bisa dimonitor otomatis. Ini BUKAN "tak terjangkau" — jangan alarm.
                log.debug("OLT {} vendor {} belum didukung, dilewati", target.code, target.vendor)
                continue
            }
            val outcome = poll(adapter, target.toWire())
            persister.persist(
                tenantId = tenantId,
                target = target,
                reachable = outcome.reachable,
                readings = outcome.readings,
                failureReason = outcome.failureReason,
            )
        }
    }

    /**
     * Probe dulu agar OLT mati dilaporkan cepat, baru walk penuh. Walk yang putus di
     * tengah atau firmware yang tak cocok diperlakukan seperti tak terjangkau: alarmnya
     * terangkat dan polling OLT lain tetap jalan, alih-alih melempar ke atas.
     */
    private fun poll(adapter: OltAdapter, wire: OltTarget): PollOutcome =
        try {
            when (val probe = adapter.probe(wire)) {
                is ProbeResult.Unreachable -> PollOutcome(reachable = false, readings = emptyList(), failureReason = probe.reason)
                is ProbeResult.Reachable -> PollOutcome(reachable = true, readings = adapter.pollOnus(wire), failureReason = null)
            }
        } catch (ex: Exception) {
            log.warn("Polling OLT {} gagal: {}", wire.oltCode, ex.message)
            PollOutcome(reachable = false, readings = emptyList(), failureReason = ex.message)
        }

    private fun OltPollingTarget.toWire() = OltTarget(
        oltId = id.toString(),
        oltCode = code,
        vendor = vendor,
        // Aman: sudah disaring [OltPollingTarget.pollable] (host tak kosong).
        host = host!!,
        snmpPort = snmpPort,
        snmpCommunity = snmpCommunity,
    )
}

/** Hasil satu putaran polling satu OLT, menyeberang dari jalur I/O ke jalur tulis. */
private class PollOutcome(
    val reachable: Boolean,
    val readings: List<OnuReading>,
    val failureReason: String?,
)

/**
 * Menuliskan hasil polling satu OLT dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [ServerSideOltPoller] — bukan method privat — karena
 * `@Transactional` Spring berlaku lewat proxy: memanggilnya dari dalam kelas yang sama
 * tak akan pernah dibungkus transaksi. REQUIRES_NEW mengisolasi kegagalan tulis satu
 * OLT dari OLT lain di siklus yang sama.
 */
@Component
class OltReadingPersister(
    private val ingestion: MetricIngestionService,
    private val alarmEngine: AlarmEngine,
    private val events: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persist(
        tenantId: UUID,
        target: OltPollingTarget,
        reachable: Boolean,
        readings: List<OnuReading>,
        failureReason: String?,
    ) {
        // Dinilai tiap siklus: OLT yang kini terjangkau menutup alarmnya sendiri.
        alarmEngine.evaluate(
            tenantId = tenantId,
            kind = AlarmKind.OLT_UNREACHABLE,
            entityId = target.id,
            entityLabel = target.code,
            conditionPresent = !reachable,
            messageBuilder = {
                "OLT ${target.code} tidak bisa dihubungi server — ${failureReason ?: "gangguan jaringan"}"
            },
        )
        if (reachable && readings.isNotEmpty()) {
            ingestion.ingestReadings(tenantId, readings)
        }
        // Reachability OLT / alarm ONU mungkin berubah → picu korelasi ulang insiden.
        events.publishEvent(AlarmsChangedEvent(tenantId))
    }
}

/**
 * Merangkai [AdapterRegistry] server-side dari adapter modul `:snmp`: GPON data-driven
 * per profil MIB (ZTE/Huawei/FiberHome) plus EPON HSGQ. Adapter memakai UDP nyata —
 * pengujian menyuplai pembaca tiruannya sendiri, jadi bean ini tak dipakai di test.
 */
@Configuration
class ServerSnmpPollingConfig {

    @Bean
    fun oltAdapterRegistry(): AdapterRegistry =
        AdapterRegistry(MibProfiles.all().map { GponSnmpAdapter(it) } + HsgqEponSnmpAdapter())

    /**
     * Pembuka sesi SNMP untuk jalur DIAGNOSTIK (lihat
     * [com.duluin.ftth.monitoring.adapter.outbound.snmp.OltSnmpProbeAdapter]). Adapter
     * polling di atas membuat sesinya sendiri lewat default konstruktor yang sama, jadi
     * kedua jalur tetap berbicara dengan perangkat memakai timeout & retry yang identik.
     */
    @Bean
    fun snmpReaderFactory(): SnmpReaderFactory =
        SnmpReaderFactory { host, port, community -> SnmpSession.open(host, port, community) }
}
