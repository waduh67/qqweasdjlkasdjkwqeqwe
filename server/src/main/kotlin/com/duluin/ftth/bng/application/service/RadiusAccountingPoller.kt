package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Poller jalur-BACA RADIUS server-side (RADIUS-as-a-service): tiap selang, membaca sesi
 * hidup dari `radacct` platform per tenant lalu menyerapnya ke `radius_session`/
 * `accounting_record` lewat [BngSessionIngestService]. Menggantikan feed sesi collector
 * on-prem — collector tak lagi punya rute ke radius-db internal.
 *
 * Sama pola dengan [RadiusProvisioningDispatcher]: tenant dipasang satu per satu lewat
 * [TenantContext.runAs] (di luar konteks request), kegagalan satu tenant tak menghentikan
 * yang lain, dan bila radius-db belum dikonfigurasi ([RadiusAccountingReadPort.isConfigured]
 * = false) putaran dilewati (dev/test boot tanpa radius-db).
 */
@Component
class RadiusAccountingPoller(
    private val tenantApi: TenantApi,
    private val radacct: RadiusAccountingReadPort,
    private val runner: RadiusAccountingPollRunner,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${ftth.radius.session-poll-interval:PT30S}")
    fun poll() {
        if (!radacct.isConfigured()) return
        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching { TenantContext.runAs(tenantId) { runner.run(tenantId) } }
                .onFailure { log.warn("Poll sesi RADIUS tenant {} gagal: {}", tenantId, it.message) }
        }
    }
}

/**
 * Pekerja poll satu tenant. Komponen terpisah dari [RadiusAccountingPoller] (bukan method
 * privat) karena batas transaksi Spring berlaku lewat proxy: penyerapan harus mengenai
 * proxy [BngSessionIngestService] agar REQUIRES_NEW-nya berjalan.
 *
 * Baca `radacct` (datasource radius-db) terjadi DI LUAR transaksi aplikasi; penyerapan lalu
 * membuka transaksinya sendiri (REQUIRES_NEW di [BngSessionIngestService]) dengan GUC tenant
 * terpasang dari [TenantContext.runAs] pemanggil. Jalur-baca murni — tak menyentuh BRAS.
 */
@Component
class RadiusAccountingPollRunner(
    private val tenantApi: TenantApi,
    private val radacct: RadiusAccountingReadPort,
    private val ingest: BngSessionIngestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(tenantId: UUID) = execute(tenantId, Instant.now())

    /**
     * Baca sesi hidup tenant [tenantId] pada waktu [now] lalu serap. Dipisah dari [run] agar
     * bisa diuji dengan jam tetap. Kode tenant (`slug`) diresolusi untuk memfilter+mengupas
     * prefiks `radacct` — kunci isolasi multi-tenant radius-db (S0). NAS asal = null: baca
     * `radacct` global cuma menyimpan `nasipaddress` string, bukan UUID NAS kita (tetap
     * terekam via `nasIp`).
     */
    fun execute(tenantId: UUID, now: Instant) {
        val slug = tenantApi.findById(tenantId)?.slug ?: run {
            log.warn("Tenant {} tak punya slug — poll sesi RADIUS dilewati", tenantId)
            return
        }
        val observations = radacct.activeSessions(tenantId, slug)
        if (observations.isEmpty()) return
        ingest.ingest(tenantId, now, nasId = null, observations = observations)
    }
}
