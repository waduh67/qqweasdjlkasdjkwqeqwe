package com.duluin.ftth.monitoring.adapter.outbound.persistence

import com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface DiscoveredOnuJpaRepository : JpaRepository<DiscoveredOnuJpaEntity, UUID> {

    fun findBySerialNumberAndStateNot(serialNumber: String, state: DiscoveredOnuState): DiscoveredOnuJpaEntity?

    fun findByStateOrderByLastSeenAtDesc(state: DiscoveredOnuState): List<DiscoveredOnuJpaEntity>

    fun findByStateAndOltIdOrderByLastSeenAtDesc(
        state: DiscoveredOnuState,
        oltId: UUID,
    ): List<DiscoveredOnuJpaEntity>

    fun findBySerialNumberInAndState(
        serialNumbers: Set<String>,
        state: DiscoveredOnuState,
    ): List<DiscoveredOnuJpaEntity>

    /**
     * Hapus borongan semua baris satu OLT dalam satu perintah. RLS tetap berlaku:
     * hanya baris tenant aktif (koneksi membawa GUC `app.tenant_id`) yang terhapus.
     */
    @Modifying
    @Query("delete from DiscoveredOnuJpaEntity d where d.oltId = :oltId and d.state <> com.duluin.ftth.monitoring.domain.model.DiscoveredOnuState.PROVISIONED")
    fun deleteByOltId(@Param("oltId") oltId: UUID): Int
}
