package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.MaterialUsageDeltaRequest
import com.duluin.ftth.inventory.application.service.MaterialUsageSnapshot
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class MaterialUsageDeltaStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun record(snapshot: MaterialUsageSnapshot, input: MaterialUsageDeltaRequest, sourceUsageId: UUID?) = jdbc.execute { sql ->
        val line = snapshot.lines.single()
        sql.update("""INSERT INTO inventory_material_usage_delta(id,tenant_id,previous_usage_id,receipt_id,issue_line_id,source_identity_id,
            source_quantity_base,source_dimension,actor_id,rework_id,source_usage_id,evidence_revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", snapshot.usageId, sql.tenant, input.previousUsageId,
            input.receiptId, input.issueLineId, input.stockIdentityId, line.acknowledgedBase.toLong(), mapper.writeValueAsString(line.source), snapshot.actorId,
            input.reworkId, if (input.reworkId == null) null else sourceUsageId, input.evidenceRevision)
    }

    fun latestSource(receiptId: UUID, issueLineId: UUID): UUID? = jdbc.execute { sql ->
        sql.query("""SELECT usage.id FROM inventory_material_usage_line line JOIN inventory_usage_snapshot usage
            ON usage.tenant_id=line.tenant_id AND usage.id=line.usage_id WHERE line.tenant_id=? AND line.receipt_id=? AND line.issue_line_id=?
            ORDER BY usage.use_revision DESC LIMIT 1""", sql.tenant, receiptId, issueLineId) { it.uuid("id") }.singleOrNull()
    }
}
