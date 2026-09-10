package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WorkOrderMaterialsITPostedFacts : MaterialWorkflowFixture() {
    @Test fun `existing posting owner facts produce accountable totals and prohibit plan replacement`() {
        val setup = setupReceipt()
        receiveStock(setup)
        val token = setup.token
        val customerResponse = request("POST", "/api/customers", token,
            """{"code":"FACT-CUSTOMER","name":"Fact customer","areaId":"${area(token)}","address":"Test","location":{"longitude":106.99,"latitude":-6.24}}""")
        assertThat(customerResponse.status).isEqualTo(201)
        val customer = UUID.fromString(mapper.readTree(customerResponse.contentAsString).path("id").asString())
        val workOrder = workOrder(token, "PSB", customer.toString())
        val plan = putPlan(token, workOrder, plan(token, workOrder, "[${line(setup.cable)}]"))
        val demand = action(token, workOrder, "submit-request", command(token, workOrder, 1)).path("demandDocumentId").asString()
        val actor = UUID.fromString(mapper.readTree(request("GET", "/api/me", token).contentAsString).path("id").asString())
        val technician = create("locations", token, """{"code":"FIELD","name":"Field stock","kind":"TECHNICIAN","custodianId":"$actor"}""").path("id").asString()
        val sink = create("locations", token, """{"code":"CONSUMED","name":"Consumed","kind":"CUSTOMER_SITE"}""").path("id").asString()
        val fixture = fixture(token)
        fixture.transaction {
            val identity = UUID.fromString(scalar("SELECT id FROM inventory_segment WHERE sku_id='${setup.cable}'"))
            val lot = UUID.fromString(scalar("SELECT lot_id FROM inventory_segment WHERE id='$identity'"))
            val demandLine = scalar("SELECT id FROM inventory_document_line WHERE document_id='$demand'")
            val issue = UUID.randomUUID()
            val issueLine = UUID.randomUUID()
            sql("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,source_document_id,source_revision,cutover_epoch,authority_epoch)
                VALUES ('$issue','$tenant','$issue','ISSUE','$actor','$workOrder','$customer','$demand',1,0,0)""")
            sql("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,source_line_id,
                base_unit,tracking,quantity_base,accepted_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES ('$issueLine','$tenant','$issue',1,0,'${setup.cable}','$identity','$lot','$demandLine','MM','LOT',60000,60000,'${setup.bin}','${setup.bin}','WAREHOUSE','SERVICEABLE','ISP')""")
            sql("UPDATE inventory_document SET state='PICKED',revision=revision+1 WHERE id='$issue'")
            val source = PostingDimension(UUID.fromString(setup.cable), identity, lot, UUID.fromString(setup.bin), UUID.fromString(setup.bin),
                OwnerKind.WAREHOUSE, WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP)
            val field = source.copy(locationId = UUID.fromString(technician), custodianId = actor, custodianKind = OwnerKind.TECHNICIAN)
            fun operation(document: UUID, action: String) = PostingOperation(UUID.randomUUID(), "material.projection.test", UUID.randomUUID().toString(), actor,
                document, "workorder:$workOrder", "a".repeat(64), action, 200, "{}", 0)
            post(WarehousePost(issue, 1, "DISPATCHED", operation(issue, "ISSUE"), MovementKind.ISSUE, "Existing posting fixture",
                listOf(PostingLeg(LegDirection.OUT, source, StockQuantity.metres("60"), issueLine, InventoryStatus.AVAILABLE),
                    PostingLeg(LegDirection.IN, field, StockQuantity.metres("60"), issueLine, InventoryStatus.ISSUED))))
            sql("UPDATE inventory_document SET state='RECEIVED',revision=revision+1 WHERE id='$issue'")
            val usage = UUID.randomUUID()
            val useLine = UUID.randomUUID()
            sql("""INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,work_order_id,customer_id,source_document_id,source_revision,cutover_epoch,authority_epoch)
                VALUES ('$usage','$tenant','$usage','TRANSFER','$actor','$workOrder','$customer','$issue',3,0,0)""")
            sql("""INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,stock_identity_id,lot_id,source_line_id,
                base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner)
                VALUES ('$useLine','$tenant','$usage',1,0,'${setup.cable}','$identity','$lot','$issueLine','MM','LOT',60000,'$technician','$actor','TECHNICIAN','SERVICEABLE','ISP')""")
            val used = field.copy(stockIdentityId = UUID.randomUUID(), locationId = UUID.fromString(sink), custodianId = customer, custodianKind = OwnerKind.CUSTOMER)
            val remnant = field.copy(stockIdentityId = UUID.randomUUID())
            val quantity = StockQuantity.metres("40")
            post(WarehousePost(usage, 0, "DISPATCHED", operation(usage, "USE"), MovementKind.CONSUME, "Existing immutable usage",
                listOf(PostingLeg(LegDirection.OUT, field, StockQuantity.metres("60"), useLine, InventoryStatus.ISSUED),
                    PostingLeg(LegDirection.IN, used, quantity, useLine, InventoryStatus.CONSUMED, PostingEndpoint.CONSUMED),
                    PostingLeg(LegDirection.IN, remnant, StockQuantity.metres("20"), useLine, InventoryStatus.ISSUED)),
                splits = listOf(PostingSplit(identity, 0, listOf(SegmentChild(used.stockIdentityId, quantity, SegmentKind.CUT),
                    SegmentChild(remnant.stockIdentityId, StockQuantity.metres("20"), SegmentKind.REMNANT)))),
                facts = listOf(PostingMaterialFact(UUID.randomUUID(), used.stockIdentityId, customer, UUID.fromString(workOrder), "Cable", quantity, 1, true, false)),
                usage = PostingUsage(UUID.randomUUID(), UUID.fromString(workOrder), 0, UUID.fromString(plan.path("id").asString()), 1, "{\"used\":\"40000\"}")))
        }
        val result = summary(token, workOrder)
        assertThat(result.path("lines")[0].path("issuedBase").asString()).isEqualTo("60000")
        assertThat(result.path("lines")[0].path("physicallyUsedBase").asString()).isEqualTo("40000")
        assertThat(result.path("lines")[0].path("stillAccountableBase").asString()).isEqualTo("20000")
        assertThat(result.path("lines")[0].path("backorderBase").asString()).isEqualTo("40000")
        assertThat(result.path("revisions").path("useRevision").asLong()).isEqualTo(1)
        val changed = request("PUT", "/api/work-orders/$workOrder/materials/plan", token,
            plan(token, workOrder, "[]", 1, "NONE", "Cannot erase usage"))
        assertThat(changed.status).isEqualTo(409)
        fixture.transaction { assertThat(scalar("SELECT count(*) FROM inventory_usage_snapshot")).isEqualTo("1") }
    }
}
