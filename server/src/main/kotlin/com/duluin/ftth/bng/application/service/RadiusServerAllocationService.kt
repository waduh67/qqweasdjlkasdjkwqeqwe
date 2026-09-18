package com.duluin.ftth.bng.application.service

import com.duluin.ftth.bng.application.port.outbound.RadiusServerRepository
import com.duluin.ftth.bng.domain.model.RadiusServer
import com.duluin.ftth.bng.domain.model.RadiusServerStatus
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Mengelola alokasi node RADIUS untuk tenant.
 *
 * Algoritma Auto-Distribution:
 * 1. Jika tenant sudah memiliki server teralokasi, gunakan server tersebut.
 * 2. Jika belum dan tidak ada server multi-cluster di database, return null (fallback ke .env default).
 * 3. Jika ada server multi-cluster, cari server dengan status [RadiusServerStatus.ACTIVE] yang
 *    jumlah tenant-nya masih di bawah [RadiusServer.maxTenants].
 * 4. Urutkan berdasarkan beban terendah (least-loaded: tenant aktif paling sedikit).
 * 5. Jika semua server ACTIVE sudah penuh, lempar [ConflictException] ("Semua cluster RADIUS penuh").
 */
@Service
@Transactional
class RadiusServerAllocationService(
    private val serverRepository: RadiusServerRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getServerForTenant(tenantId: UUID): RadiusServer? =
        serverRepository.findAssignedServerForTenant(tenantId)

    /**
     * Dapatkan server yang sudah dialokasikan untuk tenant, atau alokasikan server baru secara otomatis.
     */
    fun getOrAllocateServerForTenant(tenantId: UUID): RadiusServer? {
        val existing = serverRepository.findAssignedServerForTenant(tenantId)
        if (existing != null) {
            return existing
        }

        val allServers = serverRepository.findAll()
        if (allServers.isEmpty()) {
            // Belum ada node RADIUS multi-server yang didaftarkan platform admin — fallback ke konfigurasi lokal .env
            return null
        }

        val activeServers = allServers.filter { it.status == RadiusServerStatus.ACTIVE }
        if (activeServers.isEmpty()) {
            throw ConflictException("Tidak ada server RADIUS berstatus AKTIF yang tersedia")
        }

        val counts = serverRepository.countAllAssignedTenants()
        val eligible = activeServers
            .filter { (counts[it.id] ?: 0) < it.maxTenants }
            .sortedWith(
                compareBy<RadiusServer> { counts[it.id] ?: 0 }
                    .thenBy { it.name }
            )

        if (eligible.isEmpty()) {
            val maxCapacities = activeServers.joinToString(", ") { "${it.name} (${counts[it.id] ?: 0}/${it.maxTenants})" }
            throw ConflictException(
                "Semua server RADIUS telah mencapai kapasitas maksimum ($maxCapacities). " +
                    "Silakan daftarkan node RADIUS baru pada dashboard platform.",
            )
        }

        val chosen = eligible.first()
        serverRepository.assignTenant(tenantId, chosen.id)
        val newCount = (counts[chosen.id] ?: 0) + 1
        log.info(
            "Auto-distribute: Tenant {} dialokasikan ke server RADIUS '{}' (kuota: {}/{})",
            tenantId,
            chosen.name,
            newCount,
            chosen.maxTenants,
        )
        return chosen
    }

    fun reassignTenant(tenantId: UUID, targetServerId: UUID): RadiusServer {
        val targetServer = serverRepository.findById(targetServerId)
            ?: throw NotFoundException("Server RADIUS $targetServerId tidak ditemukan")
        val count = serverRepository.countAssignedTenants(targetServerId)
        if (count >= targetServer.maxTenants) {
            throw ConflictException("Server RADIUS '${targetServer.name}' sudah penuh ($count/${targetServer.maxTenants} tenant)")
        }
        serverRepository.assignTenant(tenantId, targetServerId)
        log.info("Tenant {} dipindahkan secara manual ke server RADIUS '{}'", tenantId, targetServer.name)
        return targetServer
    }

    fun unassignTenant(tenantId: UUID) {
        serverRepository.unassignTenant(tenantId)
        log.info("Alokasi server RADIUS untuk tenant {} dicabut", tenantId)
    }
}
