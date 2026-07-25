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

    @Scheduled(fixedDelayString = "\${ftth.cpe.sync-interval:PT5M}")
    fun syncAll() {
        val snapshots = runCatching { acsGateway.listDevices() }
            .onFailure { log.warn("Tak bisa menarik daftar device dari ACS: {}", it.message) }
            .getOrNull() ?: return
        if (snapshots.isEmpty()) return

        tenantApi.findActiveTenantIds().forEach { tenantId ->
            runCatching {
                TenantContext.runAs(tenantId) { syncService.sync(snapshots) }
            }.onFailure {
                log.warn("Sinkronisasi CPE tenant {} gagal: {}", tenantId, it.message)
            }
        }
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
