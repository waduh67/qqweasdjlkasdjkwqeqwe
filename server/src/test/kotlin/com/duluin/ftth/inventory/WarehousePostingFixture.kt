package com.duluin.ftth.inventory

import com.duluin.ftth.FtthApplication
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import com.duluin.ftth.inventory.domain.model.*
import jakarta.persistence.EntityManagerFactory
import org.hibernate.Session
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.util.UUID

internal fun postingContext(database: WarehouseSchemaDatabase): ConfigurableApplicationContext =
    SpringApplicationBuilder(FtthApplication::class.java).profiles("test").run(
        "--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=${database.url}",
        "--spring.flyway.url=${database.url}", "--spring.flyway.schemas=${database.schema}",
        "--spring.flyway.default-schema=${database.schema}", "--ftth.bootstrap.seed-demo-tenant=false",
    )

internal class WarehousePostingFixture(val context: ConfigurableApplicationContext, val tenant: UUID =
    context.getBean(com.duluin.ftth.tenancy.TenantApi::class.java).ensureTenant("post-${UUID.randomUUID()}", "Posting").id) {
    val actor = UUID.randomUUID()
    val customer = UUID.randomUUID()
    val workOrder = UUID.randomUUID()
    val warehouse = UUID.randomUUID()
    val technician = UUID.randomUUID()
    val source = UUID.randomUUID()
    val consumed = UUID.randomUUID()
    val sku = UUID.randomUUID()
    val serialSku = UUID.randomUUID()
    val bulkSku = UUID.randomUUID()

    fun <T> transaction(block: WarehousePostingFixture.() -> T): T = TenantContext.runAs(tenant) {
        var result: T? = null
        TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult { result = block() }
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    fun <T> jdbc(block: (Connection) -> T): T {
        val manager = requireNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(context.getBean(EntityManagerFactory::class.java)))
        return manager.unwrap(Session::class.java).doReturningWork { block(it) }
    }

    fun sql(query: String) = jdbc { connection -> connection.createStatement().use { it.execute(query) }; Unit }
    fun scalar(query: String): String = jdbc { connection -> connection.createStatement().use { statement ->
        statement.executeQuery(query).use { rows -> check(rows.next()); rows.getString(1) }
    } }

    fun setup() = transaction {
        check(scalar("SELECT current_user") == "warehouse_app")
        listOf(warehouse to "BIN", technician to "TECHNICIAN", source to "TRANSIT", consumed to "CUSTOMER_SITE").forEach { (id, kind) ->
            val code = when (id) { source -> "RECEIPT_SOURCE"; consumed -> "CONSUMED"; else -> id.toString() }
            sql("INSERT INTO inventory_location(id,tenant_id,code,kind,issue_eligible) VALUES ('$id','$tenant','$code','$kind',${id==warehouse || id==technician})")
        }
        sql("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$sku','$tenant','cable','Cable','LOT','MM'),('$serialSku','$tenant','onu','ONU','SERIAL','EA')")
        sql("INSERT INTO inventory_sku(id,tenant_id,code,name,tracking,base_unit) VALUES ('$bulkSku','$tenant','fastener','Fastener','BULK','EA')")
    }

    fun dimension(identity: UUID, lot: UUID?, serial: Boolean = false) = PostingDimension(
        if (serial) serialSku else sku, identity, lot, warehouse, warehouse, OwnerKind.WAREHOUSE, WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP,
    )

    fun operation(action: String = "POST") = PostingOperation(UUID.randomUUID(), "test.post", UUID.randomUUID().toString(), actor,
        workOrder, "warehouse:$warehouse", "a".repeat(64), action, 200, "{}", 0)

    fun receipt(quantity: StockQuantity, bulk: Boolean = false): PostingDimension {
        val serial = quantity.unit == StockUnit.EA && !bulk
        val identity = UUID.randomUUID()
        val lot = if (serial) null else UUID.randomUUID()
        val dimension = dimension(identity, lot, serial).let { if(bulk && quantity.unit==StockUnit.EA) it.copy(skuId=bulkSku) else it }
        val document = UUID.randomUUID()
        val line = UUID.randomUUID()
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','RECEIPT','$actor',0,0)")
        sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'${dimension.skuId}','${quantity.unit}','${if(serial) "SERIAL" else if(dimension.skuId==bulkSku) "BULK" else "LOT"}',${quantity.quantityBase},'$warehouse','$warehouse','WAREHOUSE','SERVICEABLE','ISP')")
        if (serial) {
            sql("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','${identity.toString().uppercase()}','ADMITTED','$identity')")
            sql("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,warehouse_sku_id,serial_number,canonical_serial,status,location_id,custody_owner_id,custody_owner_kind,quantity_base,base_unit,condition,legal_owner,origin_document_line_id) VALUES ('$identity','$tenant','$serialSku','$serialSku','$identity','${identity.toString().uppercase()}','AVAILABLE','$warehouse','$warehouse','WAREHOUSE',1,'EA','SERVICEABLE','ISP','$line')")
            sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base) VALUES ('$identity','$tenant','$serialSku','$identity','SERIAL','EA',1)")
        } else {
            sql("INSERT INTO inventory_lot(id,tenant_id,sku_id,code,base_unit,received_quantity_base,received_at,origin_document_line_id) VALUES ('$lot','$tenant','${dimension.skuId}','$lot','${quantity.unit}',${quantity.quantityBase},now(),'$line')")
            sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,lot_id,kind,base_unit,quantity_base) VALUES ('$identity','$tenant','${dimension.skuId}','$lot','${if(bulk) "BULK" else "REEL"}','${quantity.unit}',${quantity.quantityBase})")
        }
        sql("UPDATE inventory_document_line SET stock_identity_id='$identity',lot_id=${lot?.let { "'$it'" } ?: "NULL"},revision=1 WHERE id='$line'")
        val legs = listOf(
            PostingLeg(LegDirection.OUT, dimension.copy(locationId=source,custodianId=source,custodianKind=OwnerKind.TRANSIT), quantity,line,InventoryStatus.IN_TRANSIT,PostingEndpoint.RECEIPT_SOURCE),
            PostingLeg(LegDirection.IN, dimension,quantity,line,InventoryStatus.AVAILABLE),
        )
        post(WarehousePost(document,0,"RECEIVED_IN_INSPECTION",operation("RECEIVE"),MovementKind.RECEIVE,"Receipt",legs))
        return dimension
    }

    fun move(from: PostingDimension, to: PostingDimension, quantity: StockQuantity, kind: MovementKind = MovementKind.TRANSFER,
             splits: List<PostingSplit> = emptyList(), extra: List<Pair<PostingDimension,StockQuantity>> = emptyList(),
             facts: List<PostingMaterialFact> = emptyList()): WarehousePost {
        val document = UUID.randomUUID()
        val line = UUID.randomUUID()
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','TRANSFER','$actor','$workOrder','$customer',0,0)")
        sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'${from.skuId}','${from.stockIdentityId}',${from.lotId?.let { "'$it'" } ?: "NULL"},'${quantity.unit}','${if(from.skuId==serialSku) "SERIAL" else if(from.skuId==bulkSku) "BULK" else "LOT"}',${quantity.quantityBase},'${from.locationId}','${from.custodianId}','${from.custodianKind}','${from.condition}','${from.legalOwner}')")
        val total = extra.fold(quantity) { sum, value -> sum + value.second }
        val legs = listOf(PostingLeg(LegDirection.OUT,from,total,line,status(from)),
            PostingLeg(LegDirection.IN,to,quantity,line,status(to),if(to.locationId==consumed) PostingEndpoint.CONSUMED else PostingEndpoint.PHYSICAL)) +
            extra.map { (dimension, amount) -> PostingLeg(LegDirection.IN,dimension,amount,line,status(dimension)) }
        return WarehousePost(document,0,"DISPATCHED",operation(),kind,"Move",legs,splits=splits,facts=facts)
    }

    fun status(dimension: PostingDimension) = when(dimension.locationId) {
        consumed -> InventoryStatus.CONSUMED
        technician -> InventoryStatus.ISSUED
        else -> if(dimension.condition==WarehouseCondition.QUARANTINE) InventoryStatus.QUARANTINE else InventoryStatus.AVAILABLE
    }

    fun reservation(piece: PostingDimension,quantity: StockQuantity): WarehousePost {
        val document=UUID.randomUUID()
        val line=UUID.randomUUID()
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','DEMAND','$actor',0,0)")
        sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,base_unit,tracking,quantity_base) VALUES ('$line','$tenant','$document',1,0,'${piece.skuId}','${piece.stockIdentityId}',${piece.lotId?.let { "'$it'" } ?: "NULL"},'${quantity.unit}','${if(piece.skuId==serialSku) "SERIAL" else if(piece.skuId==bulkSku) "BULK" else "LOT"}',${quantity.quantityBase})")
        return WarehousePost(document,0,"SUBMITTED",operation("RESERVE"),MovementKind.RESERVE,"Reserve",emptyList(),listOf(
            ReservationChange(UUID.randomUUID(),line,piece,null,quantity,StockQuantity.of(0,quantity.unit),java.time.Instant.now().plusSeconds(86400))))
    }

    fun post(command: WarehousePost): WarehousePostResult {
        val fence = context.getBean(InventoryTenantPolicyService::class.java).lockForCommand(0,WarehouseOperationClass.ORDINARY_STOCK)
        return context.getBean(WarehousePosting::class.java).post(command,fence)
    }
    fun total(location: UUID, unit: StockUnit = StockUnit.MM): Long = scalar("SELECT coalesce(sum(quantity_base),0) FROM inventory_balance_projection WHERE location_id='$location' AND base_unit='$unit'").toLong()
    fun counts(): String = scalar("SELECT json_build_array((SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_movement_leg),(SELECT count(*) FROM inventory_balance_projection),(SELECT count(*) FROM inventory_reservation),(SELECT count(*) FROM inventory_customer_material_fact),(SELECT count(*) FROM inventory_outbox),(SELECT count(*) FROM inventory_operation),(SELECT count(*) FROM inventory_document),(SELECT count(*) FROM inventory_document_line),(SELECT count(*) FROM inventory_usage_snapshot),(SELECT count(*) FROM inventory_fulfillment_effect))::text")

    fun acknowledge(piece: PostingDimension): PostingDimension {
        val issued=piece.copy(locationId=technician,custodianId=actor,custodianKind=OwnerKind.TECHNICIAN)
        post(move(piece,issued,StockQuantity.each("1")))
        val document=UUID.randomUUID()
        sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','ISSUE','$actor','$workOrder','$customer',0,7)")
        sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,base_unit,tracking,quantity_base,accepted_base,location_id,destination_location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('${UUID.randomUUID()}','$tenant','$document',1,0,'${piece.skuId}','${piece.stockIdentityId}','EA','SERIAL',1,1,'$warehouse','$technician','$actor','TECHNICIAN','SERVICEABLE','ISP')")
        listOf("PICKED","DISPATCHED","RECEIVED").forEachIndexed { index,state -> sql("UPDATE inventory_document SET state='$state',revision=${index+1} WHERE id='$document'") }
        return issued
    }

    fun usage(piece: PostingDimension, usageWorkOrder: UUID = workOrder): PostingUsage {
        val plan=UUID.randomUUID()
        sql("INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id) VALUES ('$plan','$tenant','$usageWorkOrder',1,0,'MATERIAL_REQUIRED','$actor')")
        sql("INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit) VALUES ('${UUID.randomUUID()}','$tenant','$plan',1,'${piece.skuId}',1,'EA')")
        sql("UPDATE inventory_material_plan SET state='SUBMITTED',submitted_at=now(),revision=1 WHERE id='$plan'")
        return PostingUsage(UUID.randomUUID(),usageWorkOrder,0,plan,1,"{\"used\":\"1\"}")
    }
}
