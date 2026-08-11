package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.SplitterRepository
import com.duluin.ftth.network.domain.model.Splitter
import com.duluin.ftth.network.domain.model.vo.SplitterRatio
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SplitterPersistenceAdapter(
    private val jpa: SplitterJpaRepository,
) : SplitterRepository {

    /** Pemilik & kode tak pernah berubah — memindah modul berarti mencabut lalu memasang. */
    override fun save(splitter: Splitter): Splitter {
        val entity = jpa.findById(splitter.id).orElse(null)?.apply {
            ratio = splitter.ratio.label
            note = splitter.note
        } ?: SplitterJpaEntity(
            id = splitter.id,
            ownerKind = splitter.ownerKind,
            ownerId = splitter.ownerId,
            code = splitter.code,
            ratio = splitter.ratio.label,
            note = splitter.note,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: UUID): Splitter? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByOwnerId(ownerId: UUID): List<Splitter> =
        jpa.findByOwnerIdOrderByCode(ownerId).map { it.toDomain() }

    override fun findByOwnerIds(ownerIds: Set<UUID>): Map<UUID, List<Splitter>> =
        if (ownerIds.isEmpty()) emptyMap()
        else jpa.findByOwnerIdInOrderByCode(ownerIds).map { it.toDomain() }.groupBy { it.ownerId }

    override fun existsByOwnerIdAndCode(ownerId: UUID, code: String): Boolean =
        jpa.existsByOwnerIdAndCode(ownerId, code)

    override fun deleteById(id: UUID) = jpa.deleteById(id)

    override fun deleteAll(splitters: List<Splitter>) {
        if (splitters.isEmpty()) return
        jpa.deleteAllById(splitters.map { it.id })
    }
}

internal fun SplitterJpaEntity.toDomain(): Splitter = Splitter.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    ownerKind = ownerKind,
    ownerId = ownerId,
    code = code,
    ratio = SplitterRatio.of(ratio),
    note = note,
)
