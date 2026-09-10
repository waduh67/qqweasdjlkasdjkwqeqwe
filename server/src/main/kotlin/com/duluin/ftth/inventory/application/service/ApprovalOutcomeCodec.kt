package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

internal object ApprovalOutcomeCodec {
    private val mapper = jacksonObjectMapper()
    fun view(record: WarehouseApprovalRecord): WarehouseApprovalView = record.terminalBody?.let {
        mapper.readValue(it, WarehouseApprovalView::class.java)
    } ?: WarehouseApprovalView(record.id, record.snapshot.evaluation.sourceDocumentId, record.snapshot.evaluation.sourceRevision,
        record.status, record.revision, record.expiresAt, when (record.status) {
            WarehouseApprovalStatus.STALE -> "STALE_REVISION"
            WarehouseApprovalStatus.EXPIRED -> "APPROVAL_EXPIRED"
            else -> record.status.name
        })
    fun body(record: WarehouseApprovalRecord, operation: UUID? = null): String = mapper.writeValueAsString(view(record).copy(effectOperationId = operation))
}
