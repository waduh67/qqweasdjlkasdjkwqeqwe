package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.CableCoreRepository
import com.duluin.ftth.network.domain.model.CableCore
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CableCorePersistenceAdapter(
    private val jpa: CableCoreJpaRepository,
) : CableCoreRepository {

    override fun findById(id: UUID): CableCore? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByCableId(cableId: UUID): List<CableCore> =
        jpa.findByCableIdOrderByCoreNumber(cableId).map { it.toDomain() }

    override fun findByCableIds(cableIds: Collection<UUID>): List<CableCore> =
        if (cableIds.isEmpty()) emptyList()
        else jpa.findByCableIdInOrderByCableIdAscCoreNumberAsc(cableIds).map { it.toDomain() }

    override fun findByIds(ids: Collection<UUID>): List<CableCore> =
        if (ids.isEmpty()) emptyList() else jpa.findAllById(ids).map { it.toDomain() }

    /**
     * Nomor & tube tak pernah berubah setelah core dibuat (kolomnya
     * `updatable = false`), jadi penyimpanan ulang cuma menyentuh status &
     * catatan — sisanya dibiarkan apa adanya.
     */
    override fun saveAll(cores: List<CableCore>): List<CableCore> {
        if (cores.isEmpty()) return emptyList()
        val existing = jpa.findAllById(cores.map { it.id }).associateBy { it.id }
        val entities = cores.map { core ->
            existing[core.id]?.apply {
                status = core.status
                note = core.note
            } ?: CableCoreJpaEntity(
                id = core.id,
                cableId = core.cableId,
                tubeNumber = core.tubeNumber,
                coreNumber = core.coreNumber,
                status = core.status,
                note = core.note,
            )
        }
        return jpa.saveAll(entities).map { it.toDomain() }
    }

    override fun deleteAboveCoreNumber(cableId: UUID, coreNumber: Int) =
        jpa.deleteByCableIdAndCoreNumberGreaterThan(cableId, coreNumber)
}

internal fun CableCoreJpaEntity.toDomain(): CableCore = CableCore.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    cableId = cableId,
    tubeNumber = tubeNumber,
    coreNumber = coreNumber,
    status = status,
    note = note,
)
