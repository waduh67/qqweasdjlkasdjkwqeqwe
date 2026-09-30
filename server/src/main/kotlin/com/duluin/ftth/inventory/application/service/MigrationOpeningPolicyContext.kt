package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationEvidenceStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MigrationOpeningStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.OpeningPolicyContext
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MigrationOpeningPolicyContext(private val openings: MigrationOpeningStore, private val batches: MigrationEvidenceStore,
    private val access: WarehouseProvenanceAccess) {
    /** Policy evaluation already holds cutover/current-authority fences. Take batch before source-owner locks. */
    fun lock(sourceId: UUID, current: CurrentAuthority): OpeningPolicyContext? {
        val opening = openings.find(sourceId)?.view ?: return null
        batches.lockBatch(opening.batchId, opening.cutoverEpoch)
        val sources = access.sources(current)
        val review = openings.review(opening.batchId)
        if (review.reviewHash != opening.reviewHash || review.issues.isNotEmpty()) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val participants = buildSet {
            add(opening.requestedBy)
            add(openings.batchRequester(opening.batchId))
            opening.manifest.cases.forEach { source -> source.resolution?.let { resolution ->
                add(resolution.resolvedBy)
                addAll(resolution.evidence.map { it.uploadedBy })
            } }
        }
        return OpeningPolicyContext(opening.id, opening.reviewLocation.id, opening.reviewLocation.revision,
            participants, (sources.customers.values.mapNotNull { it.areaId } + sources.workOrders.values.mapNotNull { it.areaId }).toSet())
    }
}
