package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.MaterialUsageDeltaRequest
import com.duluin.ftth.inventory.application.service.MaterialUsageSnapshot
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper

@Repository
class MaterialUsageDeltaStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun record(snapshot: MaterialUsageSnapshot, input: MaterialUsageDeltaRequest) = jdbc.execute { sql ->
        val line = snapshot.lines.single()
        sql.update("""INSERT INTO inventory_material_usage_delta(id,tenant_id,previous_usage_id,receipt_id,issue_line_id,source_identity_id,
            source_quantity_base,source_dimension,actor_id) VALUES (?,?,?,?,?,?,?,?,?)""", snapshot.usageId, sql.tenant, input.previousUsageId,
            input.receiptId, input.issueLineId, input.stockIdentityId, line.acknowledgedBase.toLong(), mapper.writeValueAsString(line.source), snapshot.actorId)
    }
}
