package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.RadiusAccountingReadPort
import com.duluin.ftth.bng.application.port.outbound.SubscriberAccessRepository
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
    private val subscriberAccessRepository: SubscriberAccessRepository,
    private val ingest: BngSessionIngestService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(tenantId: UUID) = execute(tenantId, Instant.now())

    /**
     * Baca sesi hidup tenant [tenantId] pada waktu [now] lalu serap. Dipisah dari [run] agar
     * bisa diuji dengan jam tetap. Kode tenant (`slug`) diresolusi untuk memfilter+mengupas
     * prefiks `radacct` — kunci isolasi multi-tenant radius-db (S0). Username MAC akun aktif
     * (DHCP/Static) disertakan agar sesinya — yang ditulis polos tanpa prefiks — ikut terbaca.
     * NAS asal = null: baca `radacct` global cuma menyimpan `nasipaddress` string, bukan UUID
     * NAS kita (tetap terekam via `nasIp`).
     *
     * Hasil bacaan diserap sebagai POTRET UTUH tenant, bukan tambahan: apa yang tak ada di
     * `radacct` berarti sesinya sudah berakhir. Karena itu daftar kosong pun tetap diserap —
     * "semua pelanggan turun" justru keadaan yang paling perlu terbaca. Bacaan yang GAGAL
     * melempar exception (ditangkap [RadiusAccountingPoller]), jadi radius-db yang sesaat mati
     * tak pernah tersalahartikan sebagai "semua pelanggan putus".
     */
    fun execute(tenantId: UUID, now: Instant) {
        val slug = tenantApi.findById(tenantId)?.slug ?: run {
            log.warn("Tenant {} tak punya slug — poll sesi RADIUS dilewati", tenantId)
            return
        }
        val macUsernames = subscriberAccessRepository.findActiveMacUsernames()
        val observations = radacct.activeSessions(tenantId, slug, macUsernames)
        ingest.ingestTenantSnapshot(tenantId, now, observations)
    }
}
