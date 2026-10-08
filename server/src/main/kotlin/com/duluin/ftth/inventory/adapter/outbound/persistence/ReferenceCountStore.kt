package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.WarehouseCanonicalPayload
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Repository
class ReferenceCountStore(private val jdbc: WarehouseCommandJdbc) {
    private val mapper = jacksonObjectMapper()

    fun isAdmin(actor: UUID): Boolean = jdbc.execute { sql -> sql.value("""SELECT EXISTS(SELECT FROM user_role link
        JOIN role ON role.id=link.role_id AND role.tenant_id=? WHERE link.user_id=? AND role.default_key='ADMIN')""",
        sql.tenant, actor) == "t" }

    fun capture(sku: UUID, location: UUID): Pair<String, String> = jdbc.execute { sql ->
        val state = requireNotNull(sql.value("SELECT warehouse_reference_count_state(?,?,?)::text", sql.tenant, sku, location))
        state to requireNotNull(sql.value("SELECT encode(sha256(convert_to(?,'UTF8')),'hex')", state))
    }

    fun visibleLocations(actor: UUID): Set<UUID> = jdbc.execute { sql ->
        sql.query("SELECT id FROM inventory_location WHERE tenant_id=? AND warehouse_reference_count_visible(tenant_id,?,id) ORDER BY id",
            sql.tenant, actor) { it.uuid("id") }.toSet()
    }

    fun positions(state: String): List<ReferenceCountPosition> {
        val location = mapper.readTree(state).path("location")
        val technician = location.path("kind").asString() == "TECHNICIAN"
        val custodian = UUID.fromString(if (technician) location.path("custodianId").asString() else location.path("id").asString())
        return mapper.readTree(state).path("positions").asSequence().map {
        mapper.treeToValue(it, ReferenceCountPosition::class.java)
        }.toList().filter { it.quantityBase.toLong() > 0 && it.status.name == (if (technician) "ISSUED" else "AVAILABLE") &&
            it.dimension.custodianId == custodian && it.dimension.custodianKind.name == (if (technician) "TECHNICIAN" else "WAREHOUSE") &&
            it.dimension.condition == WarehouseCondition.SERVICEABLE && it.dimension.legalOwner == AssetLegalOwner.ISP }
    }

    fun load(view: ReferenceCountSnapshot, state: String, actor: UUID, authority: Long, epoch: Long) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_reference_count_snapshot(id,tenant_id,sku_id,location_id,actor_id,authority_epoch,cutover_epoch,
            book_state,snapshot_hash,snapshot,loaded_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?,?::jsonb,?)""",
            view.id, sql.tenant, view.skuId, view.locationId, actor, authority, epoch, state, view.snapshotHash, mapper.writeValueAsString(view), view.loadedAt)
        Unit
    }

    fun snapshot(id: UUID): Pair<ReferenceCountSnapshot, Long> = jdbc.execute { sql ->
        sql.query("SELECT snapshot::text,cutover_epoch FROM inventory_reference_count_snapshot WHERE tenant_id=? AND id=? FOR UPDATE", sql.tenant, id) {
            mapper.readValue(it.getString(1), ReferenceCountSnapshot::class.java) to it.getLong(2)
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun missingLocation(source: LocationSnapshot): UUID = jdbc.execute { sql ->
        sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND code=? AND kind='QUARANTINE' AND state='ACTIVE'",
            sql.tenant, "COUNT-MISSING-${source.id}")?.let(UUID::fromString) ?: UUID.randomUUID().also { id ->
            sql.update("INSERT INTO inventory_location(id,tenant_id,code,name,kind,area_id) VALUES (?,?,?,?,'QUARANTINE',?)",
                id, sql.tenant, "COUNT-MISSING-${source.id}", "Selisih opname ${source.name ?: source.code}", source.areaId)
        }
    }

    fun missing(sku: UUID, source: UUID): List<ReferenceCountPosition> = jdbc.execute { sql ->
        val location = sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND code=? AND kind='QUARANTINE' AND state='ACTIVE'",
            sql.tenant, "COUNT-MISSING-$source")?.let(UUID::fromString) ?: return@execute emptyList()
        val state = capture(sku, location).first
        mapper.readTree(state).path("positions").asSequence().map { mapper.treeToValue(it, ReferenceCountPosition::class.java) }.toList().filter {
            it.quantityBase.toLong() > 0 && it.status.name == "LOST" && it.dimension.condition == WarehouseCondition.QUARANTINE &&
                it.dimension.legalOwner == AssetLegalOwner.ISP
        }
    }

    fun claimed(serial: String): UUID? = jdbc.execute { sql -> sql.value("""SELECT admitted_asset_id FROM inventory_identity_claim
        WHERE tenant_id=? AND identity_type='SERIAL' AND canonical_value=warehouse_canonical_serial(?)""", sql.tenant, serial)?.let(UUID::fromString) }

    fun draft(id: UUID, source: UUID, destination: UUID, lines: List<com.duluin.ftth.inventory.application.service.TransferLine>,
        actor: UUID, authority: Long, epoch: Long, reason: String) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch,reason)
            VALUES (?,?,?,'ADJUSTMENT',?,?,?,?)""", id, sql.tenant, "OPN-$id", actor, epoch, authority, reason)
        lines.forEachIndexed { index, line ->
            sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,document_revision,line_number,sku_id,stock_identity_id,lot_id,
                base_unit,tracking,quantity_base,location_id,destination_location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES (?,?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,?)""", line.id, sql.tenant, id, index + 1, line.source.dimension.skuId,
                line.source.dimension.stockIdentityId, line.source.dimension.lotId, line.source.unit, line.source.tracking, line.quantity,
                source, destination, line.source.dimension.custodianId, line.source.dimension.custodianKind, line.source.dimension.condition, AssetLegalOwner.ISP)
        }
        Unit
    }

    fun bind(count: UUID, document: UUID, direction: String) = jdbc.execute { sql ->
        sql.update("INSERT INTO inventory_reference_count_movement(tenant_id,count_id,document_id,direction) VALUES (?,?,?,?)",
            sql.tenant, count, document, direction)
        Unit
    }

    fun begin(id: UUID, snapshot: UUID, key: String, actor: UUID, authority: Long, epoch: Long, canonical: WarehouseCanonicalPayload) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_reference_count_attempt(id,tenant_id,snapshot_id,operation_key,actor_id,authority_epoch,cutover_epoch,canonical_payload,payload_hash)
            VALUES (?,?,?,?,?,?,?,?,?)""", id, sql.tenant, snapshot, key, actor, authority, epoch, canonical.json, canonical.hash)
        Unit
    }

    fun lockKey(key: String): Pair<String, ReferenceCountView>? = jdbc.execute { sql ->
        sql.value("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "${sql.tenant}|reference-count|$key")
        sql.query("SELECT payload_hash,snapshot::text FROM inventory_reference_count WHERE tenant_id=? AND operation_key=?", sql.tenant, key) {
            it.getString(1) to mapper.readValue(it.getString(2), ReferenceCountView::class.java)
        }.singleOrNull()
    }

    fun save(view: ReferenceCountView, key: String, canonical: WarehouseCanonicalPayload, authority: Long, epoch: Long) = jdbc.execute { sql ->
        sql.update("""INSERT INTO inventory_reference_count(id,tenant_id,snapshot_id,operation_key,actor_id,authority_epoch,cutover_epoch,
            canonical_payload,payload_hash,snapshot,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)""", view.id, sql.tenant, view.snapshot.id, key,
            view.actorId, authority, epoch, canonical.json, canonical.hash, mapper.writeValueAsString(view), view.recordedAt)
        Unit
    }

    fun get(id: UUID): ReferenceCountView = jdbc.execute { sql ->
        sql.value("SELECT snapshot::text FROM inventory_reference_count WHERE tenant_id=? AND id=?", sql.tenant, id)
            ?.let { mapper.readValue(it, ReferenceCountView::class.java) } ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
    }

    fun list(page: Int, size: Int, locations: Set<UUID>): WarehousePage<ReferenceCountView> = jdbc.execute { sql ->
        if (locations.isEmpty()) return@execute WarehousePage(emptyList(), page, size, 0)
        val where = "count.tenant_id=? AND loaded.location_id IN (${locations.joinToString(",") { "?" }})"
        val values = listOf(sql.tenant) + locations.sortedBy(UUID::toString)
        val joins = "FROM inventory_reference_count count JOIN inventory_reference_count_snapshot loaded ON loaded.tenant_id=count.tenant_id AND loaded.id=count.snapshot_id"
        val total = requireNotNull(sql.value("SELECT count(*) $joins WHERE $where", *values.toTypedArray())).toLong()
        val items = sql.query("SELECT count.snapshot::text $joins WHERE $where ORDER BY count.recorded_at DESC,count.id LIMIT ? OFFSET ?",
            *values.toTypedArray(), size, page.toLong() * size) { mapper.readValue(it.getString(1), ReferenceCountView::class.java) }
        WarehousePage(items, page, size, total)
    }
}
