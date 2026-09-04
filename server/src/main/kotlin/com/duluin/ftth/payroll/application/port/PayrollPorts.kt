package com.duluin.ftth.payroll.application.port

import com.duluin.ftth.payroll.domain.PayrollRun
import java.util.UUID
import java.time.Instant

interface PayrollRunStore {
    fun findByOperation(tenantId: UUID, operationKey: String, payloadHash: String): PayrollRun?
    fun find(tenantId: UUID, runId: UUID): PayrollRun?
    fun save(run: PayrollRun): PayrollRun
}

interface PayrollEventStore {
    fun append(tenantId: UUID, aggregateId: UUID, type: String, at: Instant, detail: Map<String, Any?> = emptyMap())
}

class InMemoryPayrollRunStore : PayrollRunStore {
    private val values = mutableMapOf<Triple<UUID, String, String>, PayrollRun>()
    @Synchronized override fun findByOperation(tenantId: UUID, operationKey: String, payloadHash: String): PayrollRun? {
        val run = values.entries.firstOrNull { it.key.first == tenantId && it.key.second == operationKey }?.value
        if (run != null && run.payloadHash != payloadHash) throw IllegalArgumentException("operation key was used with another payload")
        return run
    }
    @Synchronized override fun find(tenantId: UUID, runId: UUID): PayrollRun? = values.values.firstOrNull { it.tenantId == tenantId && it.id == runId }
    @Synchronized override fun save(run: PayrollRun): PayrollRun {
        val key = Triple(run.tenantId, run.operationKey, run.payloadHash)
        val existing = values.entries.firstOrNull { it.key.first == run.tenantId && it.key.second == run.operationKey }?.value
        if (existing != null && existing.payloadHash != run.payloadHash) throw IllegalArgumentException("operation key was used with another payload")
        values[key] = existing ?: run
        return existing ?: run
    }
}
