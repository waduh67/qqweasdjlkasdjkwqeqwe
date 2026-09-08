package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.LocationKind
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class WarehouseMasterPersistence(private val jdbc: WarehouseCommandJdbc) : WarehouseMasterStore {
    override fun lockTopology() = jdbc.execute { sql ->
        sql.value("SELECT warehouse_lock_location_topology(?)", sql.tenant)
        Unit
    }
    override fun get(kind: MasterKind, id: UUID, lock: Boolean): MasterSnapshot = jdbc.execute { sql ->
        sql.query("SELECT * FROM ${kind.table} WHERE tenant_id=? AND id=?${if (lock) " FOR UPDATE" else ""}", sql.tenant, id) {
            snapshot(kind, it)
        }.singleOrNull() ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
    }

    override fun list(kind: MasterKind, filter: MasterFilter, locations: AuthorityScope, areas: AuthorityScope, sites: Map<UUID, UUID?>): WarehousePage<MasterSnapshot> = jdbc.execute { sql ->
        val predicates = mutableListOf("tenant_id=?")
        val values = mutableListOf<Any?>(sql.tenant)
        fun contains(column: String, value: String?) {
            if (value != null) { predicates += "position(lower(?) in lower($column))>0"; values += value }
        }
        contains("code", filter.code); contains("name", filter.name)
        if (filter.search != null) { predicates += "(position(lower(?) in lower(code))>0 OR position(lower(?) in lower(name))>0)"; values += filter.search; values += filter.search }
        if (filter.state != null) { predicates += "state=?"; values += filter.state.name }
        if (kind == MasterKind.LOCATION) {
            scoped(predicates, values, "id", locations); scoped(predicates, values, "area_id", areas)
            predicates += siteVisibility("master", values, sites)
        }
        val where = predicates.joinToString(" AND ")
        val total = requireNotNull(sql.value("SELECT count(*) FROM ${kind.table} master WHERE $where", *values.toTypedArray())).toLong()
        val rows = sql.query("SELECT * FROM ${kind.table} master WHERE $where ORDER BY ${filter.sort} ${filter.direction},id ASC LIMIT ? OFFSET ?",
            *values.toTypedArray(), filter.size, filter.page.toLong() * filter.size) { snapshot(kind, it) }
        WarehousePage(rows, filter.page, filter.size, total)
    }

    override fun save(kind: MasterKind, id: UUID, input: MasterInput, existing: MasterSnapshot?): MasterSnapshot {
        jdbc.execute { sql ->
            val fields: Map<String, Any?> = when (input) {
                is SkuInput -> linkedMapOf("code" to input.code, "name" to input.name, "tracking" to input.tracking,
                    "base_unit" to input.baseUnit, "category" to input.category, "model" to input.model,
                    "allowed_ownership_modes" to sql.connection.createArrayOf("text", input.allowedOwnershipModes.sortedBy { it.name }.map { it.name }.toTypedArray()),
                    "inspection_required" to input.inspectionRequired, "minimum_quantity_base" to input.minimumQuantityBase.toLong())
                is SupplierInput -> linkedMapOf("code" to input.code, "name" to input.name, "contact_reference" to input.contactReference)
                is LocationInput -> linkedMapOf("code" to input.code, "name" to input.name, "kind" to input.kind,
                    "parent_location_id" to input.parentLocationId, "site_id" to input.siteId, "area_id" to input.areaId,
                    "custodian_id" to input.custodianId, "issue_eligible" to input.issueEligible)
                is ArchiveMasterInput -> mapOf("state" to "ARCHIVED")
            }
            if (existing == null) {
                sql.update("INSERT INTO ${kind.table}(id,tenant_id,${fields.keys.joinToString(",")}) VALUES (?,?,${fields.keys.joinToString(",") { "?" }})",
                    id, sql.tenant, *fields.values.toTypedArray())
            } else {
                val count = sql.update("UPDATE ${kind.table} SET ${fields.keys.joinToString(",") { "$it=?" }},revision=revision+1,updated_at=clock_timestamp() WHERE tenant_id=? AND id=? AND revision=? AND state='ACTIVE'",
                    *fields.values.toTypedArray(), sql.tenant, id, existing.revision)
                if (count != 1) masterFailure(WarehouseErrorCode.STALE_REVISION)
            }
            if (kind == MasterKind.LOCATION) sql.value("SELECT warehouse_assert_location_tree(?,?)", sql.tenant, id)
        }
        return get(kind, id)
    }

    override fun hasReferences(kind: MasterKind, id: UUID): Boolean = jdbc.execute { sql ->
        sql.value("SELECT warehouse_master_references(?,?,?)", kind.table, id, sql.tenant) == "t"
    }

    override fun grantCreator(location: UUID, actor: UUID, epoch: Long) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_warehouse_scope(id,tenant_id,user_id,location_id,granted_by,authority_epoch) VALUES (?,?,?,?,?,?)",
            UUID.randomUUID(), sql.tenant, actor, location, actor, epoch)
        Unit
    }

    override fun lookup(serial: String, mac: String?, locations: AuthorityScope, areas: AuthorityScope, provenance: Boolean, sites: Map<UUID, UUID?>): IdentityLookupSnapshot = jdbc.execute { sql ->
        val predicates = mutableListOf("(SELECT count(*) FROM matches)=1",
            "NOT EXISTS (SELECT FROM claims WHERE state='CONFLICT')",
            "(SELECT count(DISTINCT (source_table,source_id)) FROM candidates)<=1",
            "NOT EXISTS (SELECT FROM candidates WHERE source_table<>'inventory_serialized_asset' OR source_id<>asset.id)",
            """NOT EXISTS (SELECT FROM candidates WHERE canonical_value IS DISTINCT FROM
                CASE identity_type WHEN 'SERIAL' THEN warehouse_canonical_serial(raw_value) ELSE warehouse_canonical_mac(raw_value) END)""",
            """NOT (coalesce(asset.canonical_serial=request.serial OR asset.canonical_serial_candidate=request.serial,false) AND
                warehouse_canonical_serial(asset.serial_number) IS DISTINCT FROM request.serial)""",
            """NOT (coalesce(asset.canonical_mac=request.mac OR asset.canonical_mac_candidate=request.mac,false) AND
                warehouse_canonical_mac(asset.mac_address) IS DISTINCT FROM request.mac)""")
        val values = mutableListOf<Any?>(sql.tenant, serial, mac)
        if (!provenance) predicates += "asset.warehouse_admission='VERIFIED'"
        scoped(predicates, values, "asset.location_id", locations); scoped(predicates, values, "location.area_id", areas)
        predicates += siteVisibility("location", values, sites)
        val found = sql.query("""WITH request AS (SELECT ?::uuid AS tenant,?::text AS serial,?::text AS mac),
            matches AS MATERIALIZED (SELECT asset.* FROM inventory_serialized_asset asset CROSS JOIN request
                WHERE asset.tenant_id=request.tenant AND (asset.canonical_serial=request.serial OR asset.canonical_serial_candidate=request.serial OR
                    asset.canonical_mac=request.mac OR asset.canonical_mac_candidate=request.mac)),
            claims AS MATERIALIZED (SELECT claim.* FROM inventory_identity_claim claim CROSS JOIN request WHERE claim.tenant_id=request.tenant AND
                ((identity_type='SERIAL' AND canonical_value=request.serial) OR (identity_type='MAC' AND canonical_value=request.mac))),
            candidates AS MATERIALIZED (SELECT candidate.* FROM inventory_identity_candidate candidate CROSS JOIN request WHERE candidate.tenant_id=request.tenant AND
                (claim_id IN (SELECT id FROM claims) OR (identity_type='SERIAL' AND canonical_value=request.serial) OR (identity_type='MAC' AND canonical_value=request.mac)))
            SELECT asset.* FROM matches asset CROSS JOIN request JOIN inventory_location location
                ON location.tenant_id=asset.tenant_id AND location.id=asset.location_id
            WHERE ${predicates.joinToString(" AND ")} ORDER BY asset.id LIMIT 1""",
            *values.toTypedArray()) { row -> IdentityLookupSnapshot(row.uuid("id"), row.optionalUuid("warehouse_sku_id"),
                row.getString("serial_number"), row.getString("mac_address"), row.uuid("location_id"), row.getString("warehouse_admission") == "LEGACY_UNRESOLVED") }
        found.singleOrNull() ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
    }

    private fun siteVisibility(alias: String, values: MutableList<Any?>, sites: Map<UUID, UUID?>): String {
        values += tools.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(sites)
        return """NOT EXISTS (WITH RECURSIVE ancestors AS (
            SELECT id,parent_location_id,site_id,area_id,ARRAY[id] AS path,false AS cycle FROM inventory_location WHERE tenant_id=$alias.tenant_id AND id=$alias.id
            UNION ALL SELECT parent.id,parent.parent_location_id,parent.site_id,parent.area_id,child.path||parent.id,parent.id=ANY(child.path)
                FROM inventory_location parent JOIN ancestors child ON parent.id=child.parent_location_id
                WHERE parent.tenant_id=$alias.tenant_id AND NOT child.cycle AND cardinality(child.path)<=32)
            SELECT FROM ancestors WHERE cycle OR cardinality(path)>32 OR area_id IS DISTINCT FROM $alias.area_id OR
                (SELECT count(DISTINCT site_id) FROM ancestors)>1 OR
                (site_id IS NOT NULL AND NOT EXISTS (SELECT FROM jsonb_each_text(CAST(? AS jsonb)) visible
                    WHERE visible.key=site_id::text AND visible.value IS NOT DISTINCT FROM area_id::text)))"""
    }

    private fun scoped(predicates: MutableList<String>, values: MutableList<Any?>, column: String, scope: AuthorityScope) {
        if (scope is AuthorityScope.Restricted) {
            if (scope.ids.isEmpty()) predicates += "FALSE" else {
                predicates += "$column IN (${scope.ids.joinToString(",") { "?" }})"
                values.addAll(scope.ids.sortedBy(UUID::toString))
            }
        }
    }

    private fun snapshot(kind: MasterKind, row: ResultSet): MasterSnapshot {
        val id = row.uuid("id"); val revision = row.getLong("revision"); val state = WarehouseMasterState.valueOf(row.getString("state"))
        return when (kind) {
            MasterKind.SKU -> SkuSnapshot(id, revision, state, row.getString("code"), row.getString("name"),
                WarehouseTracking.valueOf(row.getString("tracking")), WarehouseBaseUnit.valueOf(row.getString("base_unit")),
                row.getString("category"), row.getString("model"), (row.getArray("allowed_ownership_modes").array as Array<*>).map { AssetOwnershipMode.valueOf(it.toString()) }.toSet(),
                row.getBoolean("inspection_required"), row.getLong("minimum_quantity_base").toString())
            MasterKind.SUPPLIER -> SupplierSnapshot(id, revision, state, row.getString("code"), row.getString("name"), row.getString("contact_reference"))
            MasterKind.LOCATION -> LocationSnapshot(id, revision, state, row.getString("code"), row.getString("name"),
                LocationKind.valueOf(row.getString("kind")), row.optionalUuid("parent_location_id"), row.optionalUuid("site_id"),
                row.optionalUuid("area_id"), row.optionalUuid("custodian_id"), row.getBoolean("issue_eligible"))
        }
    }
}
