package com.duluin.ftth.fieldservice.application.port.outbound

import com.duluin.ftth.fieldservice.domain.model.Visit
import com.duluin.ftth.fieldservice.domain.model.WorkSession
import java.util.UUID

interface VisitRepository {
    fun save(visit: Visit): Visit
    fun findById(tenantId: UUID, visitId: UUID): Visit?
    fun findAll(tenantId: UUID): List<Visit> = emptyList()
    fun findAllByTechnician(tenantId: UUID, technicianId: UUID): List<Visit> = emptyList()
    fun findByWorkOrderId(tenantId: UUID, workOrderId: UUID): List<Visit> = emptyList()
    fun findWorkSession(tenantId: UUID, visitId: UUID): WorkSession? = null
}
