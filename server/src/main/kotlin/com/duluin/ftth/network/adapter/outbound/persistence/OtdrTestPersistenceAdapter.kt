package com.duluin.ftth.network.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.network.application.port.outbound.OtdrTestRepository
import com.duluin.ftth.network.domain.model.OtdrTest
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OtdrTestPersistenceAdapter(
    private val jpa: OtdrTestJpaRepository,
) : OtdrTestRepository {

    override fun save(test: OtdrTest): OtdrTest =
        jpa.save(
            OtdrTestJpaEntity(
                id = test.id,
                cableId = test.cableId,
                distanceMeters = test.distanceMeters,
                measuredFrom = test.measuredFrom,
                eventType = test.eventType,
                lossDb = test.lossDb,
                note = test.note,
                recordedBy = test.recordedBy,
                recordedAt = test.recordedAt,
            ),
        ).toDomain()

    override fun findById(id: UUID): OtdrTest? = jpa.findById(id).orElse(null)?.toDomain()

    override fun listByCable(cableId: UUID): List<OtdrTest> =
        jpa.findByCableIdOrderByRecordedAtDesc(cableId).map { it.toDomain() }

    override fun deleteById(id: UUID) = jpa.deleteById(id)
}

private fun OtdrTestJpaEntity.toDomain(): OtdrTest = OtdrTest.rehydrate(
    id = id,
    tenantId = tenantId ?: TenantContext.tenantId(),
    cableId = cableId,
    distanceMeters = distanceMeters,
    measuredFrom = measuredFrom,
    eventType = eventType,
    lossDb = lossDb,
    note = note,
    recordedBy = recordedBy,
    recordedAt = recordedAt,
)
