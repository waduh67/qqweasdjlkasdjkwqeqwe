package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.WarehouseQueryFilter
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class WarehouseSettingsQuery(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun history(page: WarehousePageRequest, access: WarehouseQueryAccess): WarehousePage<WarehousePolicyVersion> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size, direction = "desc"), access)
        decode(query.result(query.page("""SELECT version.id,version.revision,version.snapshot::jsonb body
            FROM inventory_approval_policy_version version,request WHERE version.tenant_id=request.tenant
            AND EXISTS (SELECT FROM inventory_approval_policy_warehouse scope WHERE scope.tenant_id=request.tenant AND scope.policy_id=version.id)
            AND NOT EXISTS (SELECT FROM inventory_approval_policy_warehouse scope WHERE scope.tenant_id=request.tenant
                AND scope.policy_id=version.id AND scope.location_id NOT IN (SELECT id FROM visible_locations))""", "body", "revision")), WarehousePolicyVersion::class.java)
    }

    fun delegations(page: WarehousePageRequest, access: WarehouseQueryAccess, location: UUID?, state: String?): WarehousePage<WarehouseDelegationEntry> = jdbc.execute { sql ->
        val query = WarehouseQuerySql(sql, WarehouseQueryFilter(page = page.page, size = page.size, locationId = location, direction = "desc"), access)
        val status = "CASE WHEN delegation.revoked_at IS NOT NULL THEN 'REVOKED' WHEN delegation.valid_until<=transaction_timestamp() THEN 'EXPIRED' ELSE 'ACTIVE' END"
        decode(query.result(query.page("""SELECT delegation.*,($status) state FROM inventory_approval_delegation delegation,request
            WHERE delegation.tenant_id=request.tenant AND delegation.location_id IN (SELECT id FROM visible_locations)
            AND (request.location IS NULL OR delegation.location_id=request.location)
            ${state?.let { "AND ($status)='$it'" } ?: ""}""",
            """jsonb_build_object('state',state,'delegation',jsonb_build_object('id',id,'approverId',approver_id,'delegateId',delegate_id,
                'sourceRoleId',source_role_id,'locationId',location_id,'operation',operation,'validFrom',${queryTime("valid_from")},
                'validUntil',${queryTime("valid_until")},'revokedAt',${queryTime("revoked_at")},'revision',revision))""", "created_at")), WarehouseDelegationEntry::class.java)
    }
    private fun <T : Any> decode(body: String, type: Class<T>): WarehousePage<T> {
        val value = mapper.readTree(body)
        return WarehousePage(value.path("items").asSequence().map { mapper.readValue(it.toString(), type) }.toList(),
            value.path("page").asInt(), value.path("size").asInt(), value.path("totalElements").asLong())
    }
}
