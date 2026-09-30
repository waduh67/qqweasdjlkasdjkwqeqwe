package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.port.outbound.*
import com.duluin.ftth.inventory.domain.model.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class WarehouseMasterITReferences : WarehouseMasterHttpFixture() {
    @Test fun `archive blocks open document plan and supplier references`() {
        val token = tenant()
        val supplier = create("suppliers", token, """{"code":"SUP-1","name":"Supplier"}""").path("id").asString()
        val sku = create("skus", token, """{"code":"CABLE","name":"Cable","tracking":"LOT","baseUnit":"MM"}""").path("id").asString()
        val location = create("locations", token, """{"code":"WH","name":"Warehouse","kind":"WAREHOUSE"}""").path("id").asString()
        val fixture = fixture(token)
        fixture.transaction {
            assertThat(scalar("SELECT current_user")).isEqualTo("warehouse_app")
            val document = UUID.randomUUID()
            sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,supplier_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','RECEIPT','$actor','$supplier',0,0)")
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id) VALUES ('${UUID.randomUUID()}','$tenant','$document',1,0,'$sku','MM','LOT',1,'$location')")
        }
        for ((resource, id) in listOf("suppliers" to supplier, "skus" to sku, "locations" to location)) {
            val response = request("POST", "/api/v1/warehouse/$resource/$id/archive", token, """{"expectedRevision":0}""")
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(409)
        }
        val planned = create("skus", token, """{"code":"PLAN","name":"Planned","tracking":"BULK","baseUnit":"EA"}""").path("id").asString()
        fixture.transaction {
            val plan = UUID.randomUUID()
            sql("INSERT INTO inventory_material_plan(id,tenant_id,work_order_id,plan_revision,work_order_revision,material_mode,actor_id) VALUES ('$plan','$tenant','$workOrder',1,0,'MATERIAL_REQUIRED','$actor')")
            sql("INSERT INTO inventory_material_plan_line(id,tenant_id,plan_id,line_number,sku_id,quantity_base,base_unit) VALUES ('${UUID.randomUUID()}','$tenant','$plan',1,'$planned',1,'EA')")
        }
        assertThat(request("POST", "/api/v1/warehouse/skus/$planned/archive", token, """{"expectedRevision":0}""").status).isEqualTo(409)
    }

    @Test fun `verified serial and MAC lookup is read only scoped and posted units cannot change`() {
        val token = tenant()
        val sku = create("skus", token, """{"code":"ONT","name":"ONT","tracking":"SERIAL","baseUnit":"EA"}""").path("id").asString()
        val warehouse = create("locations", token, """{"code":"WH","name":"Warehouse","kind":"WAREHOUSE","issueEligible":true}""").path("id").asString()
        val source = create("locations", token, """{"code":"RECEIPT_SOURCE","name":"Receipt source","kind":"TRANSIT"}""").path("id").asString()
        val fixture = fixture(token)
        val identity = UUID.randomUUID()
        fixture.transaction {
            val document = UUID.randomUUID(); val line = UUID.randomUUID()
            sql("INSERT INTO inventory_document(id,tenant_id,code,kind,actor_id,cutover_epoch,authority_epoch) VALUES ('$document','$tenant','$document','RECEIPT','$actor',0,0)")
            sql("INSERT INTO inventory_document_line(id,tenant_id,document_id,line_number,document_revision,sku_id,base_unit,tracking,quantity_base,location_id,custodian_id,custodian_kind,condition,legal_owner) VALUES ('$line','$tenant','$document',1,0,'$sku','EA','SERIAL',1,'$warehouse','$warehouse','WAREHOUSE','SERVICEABLE','ISP')")
            sql("INSERT INTO inventory_identity_claim(id,tenant_id,identity_type,canonical_value,state,admitted_asset_id) VALUES ('${UUID.randomUUID()}','$tenant','SERIAL','SCAN-001','ADMITTED','$identity'),('${UUID.randomUUID()}','$tenant','MAC','AABBCCDDEEFF','ADMITTED','$identity')")
            sql("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,warehouse_sku_id,serial_number,canonical_serial,mac_address,canonical_mac,status,location_id,custody_owner_id,custody_owner_kind,quantity_base,base_unit,condition,legal_owner,origin_document_line_id) VALUES ('$identity','$tenant','$sku','$sku','scan-001','SCAN-001','aa:bb:cc:dd:ee:ff','AABBCCDDEEFF','AVAILABLE','$warehouse','$warehouse','WAREHOUSE',1,'EA','SERVICEABLE','ISP','$line')")
            sql("INSERT INTO inventory_segment(id,tenant_id,sku_id,asset_id,kind,base_unit,quantity_base) VALUES ('$identity','$tenant','$sku','$identity','SERIAL','EA',1)")
            sql("UPDATE inventory_document_line SET stock_identity_id='$identity',revision=1 WHERE id='$line'")
            val dimension = PostingDimension(UUID.fromString(sku), identity, null, UUID.fromString(warehouse), UUID.fromString(warehouse), OwnerKind.WAREHOUSE, WarehouseCondition.SERVICEABLE, AssetLegalOwner.ISP)
            val quantity = StockQuantity.each("1")
            post(WarehousePost(document,0,"RECEIVED_IN_INSPECTION",operation("RECEIVE"),MovementKind.RECEIVE,"Lookup fixture",listOf(
                PostingLeg(LegDirection.OUT,dimension.copy(locationId=UUID.fromString(source),custodianId=UUID.fromString(source),custodianKind=OwnerKind.TRANSIT),quantity,line,InventoryStatus.IN_TRANSIT,PostingEndpoint.RECEIPT_SOURCE),
                PostingLeg(LegDirection.IN,dimension,quantity,line,InventoryStatus.AVAILABLE))))
        }
        val before = fixture.transaction { counts() }
        for (value in listOf("scan-001", "SCAN-001", "aa-bb-cc-dd-ee-ff", "AABB.CCDD.EEFF")) {
            val response = request("GET", "/api/v1/warehouse/assets/lookup?value=$value", token)
            assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
            assertThat(mapper.readTree(response.contentAsString).path("assetId").asString()).isEqualTo(identity.toString())
        }
        assertThat(fixture.transaction { counts() }).isEqualTo(before)
        val foreign = tenant()
        val missing = request("GET", "/api/v1/warehouse/assets/lookup?value=ABSENT", foreign)
        val inaccessible = request("GET", "/api/v1/warehouse/assets/lookup?value=SCAN-001", foreign)
        assertThat(inaccessible.status).isEqualTo(404)
        assertThat(inaccessible.contentAsString).isEqualTo(missing.contentAsString)
        assertThat(request("PUT", "/api/v1/warehouse/skus/$sku", token,
            """{"code":"ONT","name":"ONT","tracking":"LOT","baseUnit":"MM","expectedRevision":0}""").status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/skus/$sku/archive", token, """{"expectedRevision":0}""").status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/locations/$warehouse/archive", token, """{"expectedRevision":0}""").status).isEqualTo(409)
    }

    @Test fun `simultaneous identical creates return one durable original and changed key conflicts`() {
        val token = tenant()
        val key = UUID.randomUUID().toString()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val results = (1..2).map { executor.submit<Pair<Int,String>> {
                check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val response = request("POST", "/api/v1/warehouse/suppliers", token, """{"code":"RACE","name":"Race"}""", key)
                response.status to response.contentAsString
            } }
            start.countDown()
            val first = results[0].get(20, java.util.concurrent.TimeUnit.SECONDS)
            val second = results[1].get(20, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(first.first).isEqualTo(201)
            assertThat(second).isEqualTo(first)
            assertThat(fixture(token).transaction { scalar("SELECT count(*) FROM inventory_operation WHERE namespace='warehouse.master.supplier.create'") }).isEqualTo("1")
        } finally { executor.shutdownNow(); executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS) }
    }
}
