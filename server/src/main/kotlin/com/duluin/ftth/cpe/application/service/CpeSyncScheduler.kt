package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.AcsGateway
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.domain.model.CpeDevice
import com.duluin.ftth.tenancy.TenantApi
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Menyinkronkan proyeksi CPE dari GenieACS secara berkala.
 *
 * ACS itu SATU instance untuk semua tenant — device dipetakan ke pelanggan lewat
 * serial ONU, bukan lewat sumbu tenant. Maka daftar device ditarik SEKALI (panggilan
 * NBI global, di luar konteks tenant) lalu disebar ke tiap tenant: masing-masing
 * hanya mengklaim device yang serialnya cocok dengan ONU miliknya.
 *
 * Berjalan lintas tenant, jadi tenant context dipasang per tenant lewat
 * [TenantContext.runAs] — sama seperti scheduler di module monitoring. Kegagalan
 * satu tenant tidak menghentikan sinkronisasi tenant lain.
 */
@Component
class CpeSyncScheduler(
    private val acsGateway: AcsGateway,
    private val tenantApi: TenantApi,
    private val syncService: CpeSyncService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Kapan ronde sinkron terakhir SELESAI dan apakah daftar device berhasil ditarik —
     * bahan tile "Last Sync" di konsol ACS.
     *
     * Sengaja di memori, BUKAN diturunkan dari `max(cpe_device.updated_at)`: [CpeSyncService.sync]
     * memanggil `save()` tanpa syarat, tapi dirty-checking Hibernate membuat baris yang tak
     * berubah tak menerbitkan UPDATE sama sekali. Pada armada yang tenang `updated_at` akan
     * membeku dan "Last Sync" jadi berbohong — melaporkan sinkron macet padahal ia berjalan
     * mulus tiap 5 menit.
     *
     * Konsekuensinya: per-JVM dan hilang saat restart. Sebelum ronde pertama nilainya null →
     * UI menampilkan "—", bukan "tak pernah sinkron".
     */
    @Volatile
    private var lastRunAtValue: Instant? = null

    @Volatile
    private var lastRunOkValue: Boolean? = null

    /** Kapan ronde terakhir selesai; `null` sebelum ronde pertama (UI menampilkan "—"). */
    fun lastRunAt(): Instant? = lastRunAtValue

    /** Apakah ronde terakhir berhasil menarik daftar dari ACS; `null` bila belum pernah jalan. */
    fun lastRunOk(): Boolean? = lastRunOkValue

    @Scheduled(fixedDelayString = "\${ftth.cpe.sync-interval:PT5M}")
    fun syncAll() {
        val snapshots = runCatching { acsGateway.listDevices() }
            .onFailure { log.warn("Tak bisa menarik daftar device dari ACS: {}", it.message) }
            .getOrNull()
        if (snapshots == null) {
            lastRunAtValue = Instant.now()
            lastRunOkValue = false
            return
        }
        if (snapshots.isEmpty()) {
            // ACS menjawab, cuma belum punya device — itu ronde yang SUKSES, bukan gagal.
            lastRunAtValue = Instant.now()
            lastRunOkValue = true
            return
        }

        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching {
                TenantContext.runAs(tenantId) { syncService.sync(snapshots) }
            }.onFailure {
                log.warn("Sinkronisasi CPE tenant {} gagal: {}", tenantId, it.message)
            }
        }
        lastRunAtValue = Instant.now()
        lastRunOkValue = true
    }
}

/**
 * Sinkronisasi satu tenant dalam transaksinya sendiri.
 *
 * Komponen terpisah dari [CpeSyncScheduler], bukan method privat: `@Transactional`
 * Spring berlaku lewat proxy, jadi pemanggilan dari dalam kelas yang sama tak akan
 * pernah dibungkus transaksi. REQUIRES_NEW mengurung kegagalan ke satu tenant.
 *
 * Penautan memakai [CustomerApi.findOnusBySerialNumbers]: module cpe tidak menyentuh
 * tabel ONU, ia bertanya lewat kontrak lintas-module dan menerima hanya ONU milik
 * tenant aktif (ter-scope RLS). Serial yang tak cocok ONU mana pun berarti perangkat
 * di luar jangkauan kita — diabaikan.
 */
@Component
class CpeSyncService(
    private val deviceRepository: CpeDeviceRepository,
    private val customerApi: CustomerApi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sync(snapshots: List<AcsDevice>) {
        val bySerial = snapshots.associateBy { it.serialNumber }
        val matchedOnus = customerApi.findOnusBySerialNumbers(bySerial.keys)

        val existing = deviceRepository.findAllForCurrentTenant().associateByTo(HashMap()) { it.genieacsId }
        val keptGenieacsIds = HashSet<String>()

        matchedOnus.forEach { onu ->
            val snapshot = bySerial[onu.serialNumber] ?: return@forEach
            keptGenieacsIds += snapshot.genieacsId
            val row = existing[snapshot.genieacsId]
            if (row != null) {
                row.applySnapshot(
                    oui = snapshot.oui,
                    productClass = snapshot.productClass,
                    manufacturer = snapshot.manufacturer,
                    model = snapshot.model,
                    softwareVersion = snapshot.softwareVersion,
                    ipAddress = snapshot.ipAddress,
                    lastInformAt = snapshot.lastInformAt,
                    ssid = snapshot.ssid,
                    temperatureC = snapshot.temperatureC,
                )
                row.linkTo(onu.customerId, onu.id)
                deviceRepository.save(row)
            } else {
                deviceRepository.save(
                    CpeDevice.link(
                        genieacsId = snapshot.genieacsId,
                        serialNumber = snapshot.serialNumber,
                        oui = snapshot.oui,
                        productClass = snapshot.productClass,
                        manufacturer = snapshot.manufacturer,
                        model = snapshot.model,
                        softwareVersion = snapshot.softwareVersion,
                        ipAddress = snapshot.ipAddress,
                        lastInformAt = snapshot.lastInformAt,
                        ssid = snapshot.ssid,
                        temperatureC = snapshot.temperatureC,
                        customerId = onu.customerId,
                        onuId = onu.id,
                    ),
                )
            }
        }

        // Proyeksi yang serialnya tak lagi cocok ONU tenant ini (ONU dilepas/dipindah,
        // atau device lenyap dari ACS) dipangkas — proyeksi tak boleh menyimpan hantu.
        val stale: List<UUID> = existing.values.filterNot { it.genieacsId in keptGenieacsIds }.map { it.id }
        if (stale.isNotEmpty()) {
            deviceRepository.deleteByIds(stale)
            log.debug("{} proyeksi CPE basi dipangkas", stale.size)
        }
    }
}
