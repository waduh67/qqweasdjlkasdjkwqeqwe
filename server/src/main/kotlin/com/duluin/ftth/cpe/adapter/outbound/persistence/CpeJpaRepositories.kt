package com.duluin.ftth.cpe.adapter.outbound.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CpeDeviceJpaRepository : JpaRepository<CpeDeviceJpaEntity, UUID> {
    fun findByGenieacsId(genieacsId: String): CpeDeviceJpaEntity?
    fun findByCustomerId(customerId: UUID): List<CpeDeviceJpaEntity>
}

interface CpeActionLogJpaRepository : JpaRepository<CpeActionLogJpaEntity, UUID> {
    fun findByDeviceIdOrderByRequestedAtDesc(deviceId: UUID): List<CpeActionLogJpaEntity>

    /**
     * Plafon baris dikirim sebagai [Pageable] agar LIMIT-nya dikerjakan Postgres —
     * tabel jejak audit tumbuh tanpa batas, memuat semuanya lalu memotong di JVM
     * akan menarik riwayat berbulan-bulan demi 100 baris.
     */
    fun findAllByOrderByRequestedAtDesc(pageable: Pageable): List<CpeActionLogJpaEntity>
}
