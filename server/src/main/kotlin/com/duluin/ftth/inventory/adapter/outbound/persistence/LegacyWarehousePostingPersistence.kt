package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class LegacyWarehousePostingPersistence(private val entityManager: EntityManager) : LegacyWarehousePostingPort {
    override fun fulfillment(command: MovementCommand): InventoryFulfillmentCommand = within { sql ->
        require(command.kind==MovementKind.CONSUME && command.tenantId==sql.tenant)
        val leg=command.legs.single()
        require(leg.direction==LegDirection.OUT && leg.serialized && leg.quantity==1)
        val context=sql.query("""SELECT document.work_order_id,document.customer_id FROM inventory_document document
            JOIN inventory_document_line line ON line.tenant_id=document.tenant_id AND line.document_id=document.id
            WHERE document.tenant_id=? AND document.kind='ISSUE' AND document.state IN ('RECEIVED','PART_RECEIVED')
            AND line.stock_identity_id=? AND line.accepted_base=1 ORDER BY document.created_at DESC,document.id DESC LIMIT 1""",sql.tenant,leg.itemId) {
            it.uuid("work_order_id") to it.uuid("customer_id")
        }.singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        InventoryFulfillmentCommand(sql.tenant,leg.itemId,leg.itemId,leg.skuId,leg.locationId,context.second,context.first,
            leg.quantity,true,true,command.actorId,command.namespace,command.operationKey,command.payloadHash,command.reason)
    }

    override fun resolve(command: InventoryFulfillmentCommand, returned: Boolean, cutoverEpoch: Long): WarehousePost = within { sql ->
        require(command.tenantId==sql.tenant && command.quantity>0 && command.installed!=returned)
        if(!command.serialized || command.quantity!=1) sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if(sql.value("SELECT id FROM inventory_movement WHERE tenant_id=? AND operation_namespace=? AND operation_key=?",sql.tenant,command.namespace,command.operationKey)!=null)
            sql.fail(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
        val issued = sql.query("""SELECT line.id,line.document_id,document.revision,document.authority_epoch FROM inventory_document_line line
            JOIN inventory_document document ON document.tenant_id=line.tenant_id AND document.id=line.document_id
            WHERE line.tenant_id=? AND line.stock_identity_id=? AND line.sku_id=? AND line.base_unit='EA'
            AND document.kind='ISSUE' AND document.state IN ('RECEIVED','PART_RECEIVED') AND line.accepted_base>=1
            AND document.work_order_id=? AND document.customer_id=? ORDER BY document.created_at DESC,document.id DESC LIMIT 1""",
            sql.tenant,command.itemId,command.skuId,command.workOrderId,command.customerId) { LegacyIssue(it.uuid("id"),it.uuid("document_id"),it.getLong("revision"),it.getLong("authority_epoch")) }
            .singleOrNull() ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val source=sql.query("SELECT * FROM inventory_balance_projection WHERE tenant_id=? AND stock_identity_id=? AND sku_id=? AND quantity_base=1 AND base_unit='EA' AND custody_owner_id=? AND custody_owner_kind='TECHNICIAN' AND status='ISSUED' AND warehouse_admission='VERIFIED'",
            sql.tenant,command.itemId,command.skuId,command.actorId) { PostingProjection.dimension(it) }.singleOrNull() ?: sql.fail(WarehouseErrorCode.INSUFFICIENT_STOCK)
        if(source.locationId!=command.locationId) sql.fail(WarehouseErrorCode.WRONG_CUSTODIAN)
        val targetLocation=if(returned) sql.value("SELECT location_id FROM inventory_document_line WHERE tenant_id=? AND id=?",sql.tenant,issued.line)
            else sql.value("SELECT id FROM inventory_location WHERE tenant_id=? AND code='CONSUMED' AND state='ACTIVE'",sql.tenant)
        val destination=targetLocation?.let(UUID::fromString) ?: sql.fail(WarehouseErrorCode.NOT_FOUND)
        val target=source.copy(locationId=destination,custodianId=if(returned) destination else command.customerId,
            custodianKind=if(returned) OwnerKind.WAREHOUSE else OwnerKind.CUSTOMER,condition=if(returned) WarehouseCondition.QUARANTINE else source.condition)
        val document=UUID.randomUUID()
        val line=UUID.randomUUID()
        sql.update("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,source_document_id,source_revision,cutover_epoch,authority_epoch)
            VALUES (?,?,?,'TRANSFER',?,?,?,?,?,?,?)""",document,sql.tenant,"legacy-$document",command.actorId,command.workOrderId,command.customerId,issued.document,issued.revision,cutoverEpoch,issued.authorityEpoch)
        sql.update("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,source_line_id,
            base_unit,tracking,quantity_base,location_id,destination_location_id,custodian_id,custodian_kind,condition,legal_owner)
            VALUES (?,?,?,1,0,?,?,?,?, 'EA','SERIAL',1,?,?,?,?,?,?)""",line,sql.tenant,document,source.skuId,source.stockIdentityId,source.lotId,issued.line,
            source.locationId,target.locationId,source.custodianId,source.custodianKind,source.condition,source.legalOwner)
        val quantity=StockQuantity.each("1")
        WarehousePost(document,0,"DISPATCHED",PostingOperation(UUID.randomUUID(),command.namespace,command.operationKey,command.actorId,command.targetId,
            "workorder:${command.workOrderId}",command.payloadHash,if(returned) "RETURN" else "CONSUME",200,"{}",issued.authorityEpoch),
            if(returned) MovementKind.RETURN else MovementKind.CONSUME,command.reason,listOf(
                PostingLeg(LegDirection.OUT,source,quantity,line,InventoryStatus.ISSUED),
                PostingLeg(LegDirection.IN,target,quantity,line,if(returned) InventoryStatus.QUARANTINE else InventoryStatus.CONSUMED,
                    if(returned) PostingEndpoint.PHYSICAL else PostingEndpoint.CONSUMED)),facts=listOf(
                PostingMaterialFact(UUID.randomUUID(),source.stockIdentityId,command.customerId,command.workOrderId,command.itemCategory,quantity,
                    issued.revision,command.installed,returned,command.targetId)))
    }

    override fun facts(customerId: UUID): List<CustomerMaterialFact> = within { sql -> sql.query("""SELECT * FROM inventory_customer_material_fact
        WHERE tenant_id=? AND customer_id=? AND quantity IS NOT NULL AND (warehouse_admission='LEGACY_UNRESOLVED' OR base_unit='EA') ORDER BY recorded_at,id""",sql.tenant,customerId) {
        CustomerMaterialFact(sql.tenant,it.uuid("customer_id"),it.uuid("work_order_id"),it.getString("item_category"),it.getInt("quantity"),it.getBoolean("installed"),it.getBoolean("returned"),it.getTimestamp("recorded_at").toInstant())
    } }
    override fun hasFact(operationKey: String, payloadHash: String): Boolean = within { sql ->
        sql.value("SELECT id FROM inventory_fulfillment_effect WHERE tenant_id=? AND operation_key=? AND payload_hash=?",sql.tenant,operationKey,payloadHash)!=null
    }
    private fun <T> within(action: (PostingSql) -> T): T = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
        check(!connection.autoCommit)
        val sql=PostingSql(connection,TenantContext.tenantId())
        check(sql.value("SELECT current_setting('app.tenant_id',true)")==sql.tenant.toString())
        action(sql)
    }
    private data class LegacyIssue(val line: UUID,val document: UUID,val revision: Long,val authorityEpoch: Long)
}
