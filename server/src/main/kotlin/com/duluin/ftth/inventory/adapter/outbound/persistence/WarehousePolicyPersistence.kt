package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

data class SettingsReplay(val actor: UUID, val hash: String, val cutoverEpoch: Long, val locations: Set<UUID>, val status: Int, val body: String)

@Repository
class WarehousePolicyPersistence(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()
    fun now(): Instant = jdbc.execute { sql -> sql.query("SELECT clock_timestamp()") { it.getTimestamp(1).toInstant() }.single() }
    fun current(): WarehousePolicyVersion? = history(0, 1).firstOrNull()
    fun history(page: Int, size: Int): List<WarehousePolicyVersion> = jdbc.execute { sql ->
        sql.query("SELECT snapshot FROM inventory_approval_policy_version WHERE tenant_id=? ORDER BY revision DESC LIMIT ? OFFSET ?",
            sql.tenant, size, page.toLong() * size) { mapper.readValue(it.getString(1), WarehousePolicyVersion::class.java) }
    }
    fun save(version: WarehousePolicyVersion, hash: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_approval_policy_version(id,tenant_id,revision,currency,expiry_hours,actor_id,authority_epoch,snapshot,snapshot_hash,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?)""", version.id, sql.tenant, version.revision, version.currency, version.expiryHours,
            version.actorId, version.authorityEpoch, mapper.writeValueAsString(version), hash, version.createdAt)
        version.warehouseIds.forEach { location -> sql.update("INSERT INTO inventory_approval_policy_warehouse(id,tenant_id,policy_id,location_id) VALUES (?,?,?,?)",
            UUID.randomUUID(), sql.tenant, version.id, location) }
        version.rules.forEach { rule -> rule.tiers.forEachIndexed { index, tier ->
            val tierId = UUID.randomUUID()
            sql.update("INSERT INTO inventory_approval_policy_tier(id,tenant_id,policy_id,operation,tier,minimum_minor) VALUES (?,?,?,?,?,?)",
                tierId, sql.tenant, version.id, rule.operation, index + 1, tier.minimumMinor.toBigDecimal())
            tier.userIds.forEach { user -> sql.update("INSERT INTO inventory_approval_policy_approver(id,tenant_id,policy_id,tier_id,user_id) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), sql.tenant, version.id, tierId, user) }
            tier.roleIds.forEach { role -> sql.update("INSERT INTO inventory_approval_policy_approver(id,tenant_id,policy_id,tier_id,role_id) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), sql.tenant, version.id, tierId, role) }
        } }
    }
    fun replay(namespace: String, key: String): SettingsReplay? = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_settings_operation WHERE tenant_id=? AND namespace=? AND operation_key=?", sql.tenant, namespace, key) {
            SettingsReplay(it.uuid("actor_id"), it.getString("payload_hash"), it.getLong("cutover_epoch"),
                (it.getArray("location_ids").array as Array<*>).map { value -> UUID.fromString(value.toString()) }.toSet(),
                it.getInt("original_status"), it.getString("original_body"))
        }.singleOrNull()
    }
    fun record(namespace: String, key: String, actor: UUID, resource: UUID, locations: Collection<UUID>, revision: Long,
        hash: String, epoch: Long, cutover: Long, body: String, bootstrap: Boolean = false) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_settings_operation(id,tenant_id,namespace,operation_key,actor_id,resource_id,location_ids,revision,
            payload_hash,original_status,original_body,authority_epoch,cutover_epoch,bootstrap) VALUES (?,?,?,?,?,?,?,?,?,200,?,?,?,?)""",
            UUID.randomUUID(), sql.tenant, namespace, key, actor, resource, sql.connection.createArrayOf("uuid", locations.toTypedArray()),
            revision, hash, body, epoch, cutover, bootstrap)
    }
    fun scopes(user: UUID): List<WarehouseScopeGrant> = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_warehouse_scope WHERE tenant_id=? AND user_id=? ORDER BY location_id", sql.tenant, user) {
            WarehouseScopeGrant(it.uuid("id"), user, it.uuid("location_id"), it.getString("state") == "ACTIVE", it.getLong("revision"))
        }
    }
    fun scope(user: UUID, location: UUID, active: Boolean, actor: UUID, epoch: Long): WarehouseScopeGrant = jdbc.execute { sql ->
        sql.query("""INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,state,granted_by,authority_epoch,revision)
            VALUES (?,?,?,?,?,?,?,1) ON CONFLICT(tenant_id,user_id,location_id) DO UPDATE SET state=excluded.state,
            granted_by=excluded.granted_by,authority_epoch=excluded.authority_epoch,revision=inventory_warehouse_scope.revision+1,updated_at=clock_timestamp() RETURNING *""",
            UUID.randomUUID(), sql.tenant, user, location, if (active) "ACTIVE" else "REVOKED", actor, epoch) {
            WarehouseScopeGrant(it.uuid("id"), user, location, active, it.getLong("revision"))
        }.single()
    }
    fun locationsFor(user: UUID): Set<UUID> = jdbc.execute { sql ->
        sql.query("""WITH RECURSIVE scoped(id) AS (SELECT location_id FROM inventory_warehouse_scope WHERE tenant_id=? AND user_id=? AND state='ACTIVE'
            UNION SELECT location.id FROM inventory_location location JOIN scoped ON location.parent_location_id=scoped.id
            WHERE location.tenant_id=? AND location.state='ACTIVE') SELECT id FROM scoped""", sql.tenant, user, sql.tenant) { it.uuid("id") }.toSet()
    }
    fun covered(location: UUID, roots: Collection<UUID>): Boolean = jdbc.execute { sql ->
        sql.query("""WITH RECURSIVE ancestry AS (SELECT id,parent_location_id FROM inventory_location WHERE tenant_id=? AND id=? AND state='ACTIVE'
            UNION SELECT parent.id,parent.parent_location_id FROM inventory_location parent JOIN ancestry ON ancestry.parent_location_id=parent.id
            WHERE parent.tenant_id=? AND parent.state='ACTIVE') SELECT id FROM ancestry""", sql.tenant, location, sql.tenant) { it.uuid("id") }.any { it in roots }
    }
    fun delegations(): List<WarehouseDelegation> = jdbc.execute { sql ->
        sql.query("SELECT * FROM inventory_approval_delegation WHERE tenant_id=? AND location_id IS NOT NULL ORDER BY created_at,id", sql.tenant) {
            WarehouseDelegation(it.uuid("id"), it.uuid("approver_id"), it.uuid("delegate_id"), it.optionalUuid("source_role_id"),
                it.uuid("location_id"), PolicyOperation.valueOf(it.getString("operation")), it.getTimestamp("valid_from").toInstant(),
                it.getTimestamp("valid_until").toInstant(), it.getTimestamp("revoked_at")?.toInstant(), it.getLong("revision"))
        }
    }
    fun delegate(value: WarehouseDelegation, actor: UUID, epoch: Long) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_approval_delegation(id,tenant_id,approver_id,delegate_id,source_role_id,location_id,operation,
            valid_from,valid_until,granted_by,authority_epoch,revision) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", value.id, sql.tenant,
            value.approverId, value.delegateId, value.sourceRoleId, value.locationId, value.operation, value.validFrom, value.validUntil,
            actor, epoch, value.revision)
    }
    fun revoke(id: UUID, actor: UUID, epoch: Long) = jdbc.execute { sql ->
        sql.update("""UPDATE inventory_approval_delegation SET revoked_at=clock_timestamp(),revoked_by=?,authority_epoch=?,revision=revision+1,
            updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revoked_at IS NULL""", actor, epoch, sql.tenant, id)
    }
}
