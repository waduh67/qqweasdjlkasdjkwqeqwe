package com.duluin.ftth.workorder.application.port.outbound

import com.duluin.ftth.workorder.domain.model.WorkOrderEvidence
import com.duluin.ftth.workorder.domain.model.WorkOrderSignature
import java.util.UUID

/** Persistence metadata bukti foto. Ter-scope tenant otomatis (Hibernate + RLS). */
interface WorkOrderEvidenceRepository {

    fun save(evidence: WorkOrderEvidence): WorkOrderEvidence

    fun findById(id: UUID): WorkOrderEvidence?

    /** Bukti sebuah work order, terlama lebih dulu. */
    fun listByWorkOrder(workOrderId: UUID): List<WorkOrderEvidence>

    fun deleteById(id: UUID)
}

/** Persistence metadata tanda tangan (paling banyak satu per work order). */
interface WorkOrderSignatureRepository {

    fun save(signature: WorkOrderSignature): WorkOrderSignature

    fun findByWorkOrder(workOrderId: UUID): WorkOrderSignature?

    fun deleteById(id: UUID)
}
