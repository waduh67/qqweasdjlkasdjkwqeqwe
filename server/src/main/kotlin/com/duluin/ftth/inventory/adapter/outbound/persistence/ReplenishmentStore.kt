package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet
import java.util.UUID

data class ReplenishmentReplay(val actorId: UUID, val ruleId: UUID, val hash: String, val epoch: Long, val body: String)

@Repository
class ReplenishmentStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun deadline() = jdbc.execute { sql ->
        sql.query("SELECT set_config('lock_timeout','2s',true),set_config('statement_timeout','20s',true)") { Unit }.single()
    }

    fun rule(id: UUID): ReplenishmentRule = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_replenishment_rule WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id, map = ::ruleRow)
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun save(rule: ReplenishmentRule, creating: Boolean) = jdbc.execute { sql ->
        if (creating) sql.update("""INSERT INTO inventory_replenishment_rule(id,tenant_id,sku_id,location_id,base_unit,
            minimum_base,maximum_base,target_base,package_multiple_base,lead_time_days) VALUES (?,?,?,?,?,?,?,?,?,?)""",
            rule.id, sql.tenant, rule.skuId, rule.locationId, rule.baseUnit, rule.minimumBase.toLong(), rule.maximumBase.toLong(),
            rule.targetBase.toLong(), rule.packageMultipleBase.toLong(), rule.leadTimeDays)
        else sql.update("""UPDATE inventory_replenishment_rule SET minimum_base=?,maximum_base=?,target_base=?,package_multiple_base=?,
            lead_time_days=?,active=?,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revision=?""",
            rule.minimumBase.toLong(), rule.maximumBase.toLong(), rule.targetBase.toLong(), rule.packageMultipleBase.toLong(),
            rule.leadTimeDays, rule.active, sql.tenant, rule.id, rule.revision - 1).also {
            if (it != 1) sql.fail(WarehouseErrorCode.STALE_REVISION)
        }
    }

    fun pending(ruleId: UUID): ReplenishmentRequest? = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_replenishment_request WHERE tenant_id=? AND rule_id=? AND state='PENDING' FOR UPDATE",
            sql.tenant, ruleId, map = ::requestRow).singleOrNull()
    }

    fun request(id: UUID): ReplenishmentRequest = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_replenishment_request WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id, map = ::requestRow)
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun suggest(rule: ReplenishmentRule, position: ReplenishmentPosition, quantity: Long): ReplenishmentRequest = jdbc.execute { sql ->
        val id = UUID.randomUUID()
        sql.update("""INSERT INTO inventory_replenishment_request(id,tenant_id,rule_id,rule_revision,business_key,quantity_base,state,rule_snapshot,position_snapshot)
            VALUES (?,?,?,?,?,?,'PENDING',?::jsonb,?::jsonb)""", id, sql.tenant, rule.id, rule.revision, "${rule.id}:$id", quantity,
            mapper.writeValueAsString(rule), mapper.writeValueAsString(position))
        request(id)
    }

    fun refresh(request: ReplenishmentRequest, rule: ReplenishmentRule, position: ReplenishmentPosition, quantity: Long) = jdbc.execute { sql ->
        sql.update("""UPDATE inventory_replenishment_request SET quantity_base=?,rule_revision=?,rule_snapshot=?::jsonb,
            position_snapshot=?::jsonb,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND accepted_at IS NULL""",
            quantity, rule.revision, mapper.writeValueAsString(rule), mapper.writeValueAsString(position), sql.tenant, request.id)
    }

    fun terminal(id: UUID, state: ReplenishmentState) = jdbc.execute { sql ->
        sql.update("UPDATE inventory_replenishment_request SET state=?,revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=?",
            state, sql.tenant, id)
    }

    fun accept(id: UUID, actor: UUID) = jdbc.execute { sql ->
        sql.update("""UPDATE inventory_replenishment_request SET accepted_at=clock_timestamp(),accepted_by=?,revision=revision+1,
            updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND state='PENDING' AND accepted_at IS NULL""", actor, sql.tenant, id)
    }

    fun bind(id: UUID, input: ReplenishmentReceivingReference) = jdbc.execute { sql ->
        sql.update("""UPDATE inventory_replenishment_request SET source_document_id=?,receiving_line_id=?,receiving_revision=?,
            revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=?""", input.documentId, input.lineId, input.documentRevision, sql.tenant, id)
    }

    fun replay(action: String, key: String): ReplenishmentReplay? = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_replenishment_operation WHERE tenant_id=? AND namespace=? AND operation_key=?", sql.tenant, action, key) {
            ReplenishmentReplay(it.uuid("actor_id"), it.uuid("rule_id"), it.getString("payload_hash"), it.getLong("cutover_epoch"), it.getString("original_body"))
        }.singleOrNull()
    }

    fun record(action: String, key: String, actor: UUID, rule: UUID, request: UUID?, revision: Long, hash: String, epoch: Long, body: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_replenishment_operation(id,tenant_id,namespace,operation_key,actor_id,rule_id,request_id,
            revision,payload_hash,cutover_epoch,original_body) VALUES (?,?,?,?,?,?,?,?,?,?,?)""", UUID.randomUUID(), sql.tenant,
            action, key, actor, rule, request, revision, hash, epoch, body)
    }

    fun history(rule: UUID, page: Int, size: Int): List<ReplenishmentHistory> = jdbc.execute { sql ->
        sql.query("""SELECT id,namespace,revision,created_at FROM inventory_replenishment_operation WHERE tenant_id=? AND rule_id=?
            ORDER BY created_at DESC,id LIMIT ? OFFSET ?""", sql.tenant, rule, size, page.toLong() * size) {
            ReplenishmentHistory(it.uuid("id"), it.getString("namespace"), it.getLong("revision"), it.getTimestamp("created_at").toInstant())
        }
    }

    internal fun ruleRow(row: ResultSet) = ReplenishmentRule(row.uuid("id"), row.getLong("revision"), row.uuid("sku_id"), row.uuid("location_id"),
        WarehouseBaseUnit.valueOf(row.getString("base_unit")), row.getString("minimum_base"), row.getString("maximum_base"),
        row.getString("target_base") ?: row.getString("maximum_base"), row.getString("package_multiple_base"), row.getInt("lead_time_days"), row.getBoolean("active"))

    internal fun requestRow(row: ResultSet): ReplenishmentRequest {
        val rule = mapper.readValue(row.getString("rule_snapshot"), ReplenishmentRule::class.java)
        val position = mapper.readValue(row.getString("position_snapshot"), ReplenishmentPosition::class.java)
        return ReplenishmentRequest(row.uuid("id"), row.getLong("revision"), row.uuid("rule_id"), row.getLong("rule_revision"),
            ReplenishmentState.valueOf(row.getString("state")), row.getString("quantity_base"), rule.baseUnit,
            position.availableBase, position.reservedBase, position.confirmedInboundBase, rule, row.getTimestamp("accepted_at")?.toInstant(),
            row.optionalUuid("accepted_by"), row.optionalUuid("source_document_id"), row.optionalUuid("receiving_line_id"), row.getTimestamp("created_at").toInstant())
    }
}
