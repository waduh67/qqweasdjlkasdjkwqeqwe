package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant
import java.util.UUID

class WarehouseQueryITSerialFilters : WarehouseReceiptHttpFixture() {
    @Test fun `AV9-02 serial binds the target before event date filters and rejects other identities`() {
        val setup = setupReceipt()
        val received = transition(setup, draft(setup, """{"skuId":"${setup.onu}","quantityBase":"2","serials":[{"serial":"MANUAL-1"},{"serial":"MANUAL-2"}]},
            {"skuId":"${setup.cable}","quantityBase":"1000","lotCode":"BULK"}""").path("id").asString(), "receive", """{"expectedRevision":0}""")
        val positions = mapper.readTree(request("GET", "/api/v1/warehouse/stock/positions", setup.token).contentAsString).path("items")
        val position = positions.single { it.path("serial").asString()=="MANUAL-1" }
        val positionId = position.path("id").asString()
        val assetId = position.path("stockIdentityId").asString()
        val paths = listOf("stock/positions/$positionId/history", "stock/$positionId/history", "assets/$assetId/history")
        val missing = request("GET", "/api/v1/warehouse/assets/${UUID.randomUUID()}/history", setup.token)
        for (path in paths) {
            val matching = mvc.perform(get("/api/v1/warehouse/$path").param("serial", " manual-1 ")
                .header("Authorization", "Bearer ${setup.token}")).andReturn().response
            assertThat(matching.status).isEqualTo(200)
            assertThat(mapper.readTree(matching.contentAsString).path("totalElements").asInt()).isEqualTo(2)
            for (serial in listOf("NOT-THE-ASSET", "MANUAL-2")) {
                val response = request("GET", "/api/v1/warehouse/$path?serial=$serial", setup.token)
                println("AV9-02 path=$path serial=$serial status=${response.status} body=${response.contentAsString}")
                assertThat(response.status).isEqualTo(404)
                assertThat(response.contentAsString).isEqualTo(missing.contentAsString)
            }
        }
        val history = mapper.readTree(request("GET", "/api/v1/warehouse/assets/$assetId/history", setup.token).contentAsString)
        val eventTime = Instant.parse(history.path("items")[0].path("recordedAt").asString())
        val range = "from=${eventTime.minusSeconds(1)}&until=${eventTime.plusSeconds(1)}"
        fixture(setup.token).transaction { sql("UPDATE inventory_balance_projection SET updated_at='${eventTime.plusSeconds(86400)}',revision=revision+1 WHERE id='$positionId'") }
        for (path in paths) {
            val response = request("GET", "/api/v1/warehouse/$path?$range&serial=manual-1", setup.token)
            assertThat(response.status).isEqualTo(200)
            assertThat(mapper.readTree(response.contentAsString).path("totalElements").asInt()).isEqualTo(2)
        }
        val bulkPosition = positions.single { it.path("skuId").asString()==setup.cable }.path("id").asString()
        assertThat(request("GET", "/api/v1/warehouse/stock/positions/$bulkPosition/history?serial=MANUAL-1", setup.token).contentAsString).isEqualTo(missing.contentAsString)
        val foreign = setupReceipt()
        transition(foreign, draft(foreign, """{"skuId":"${foreign.onu}","quantityBase":"1","serials":[{"serial":"FOREIGN-ONLY"}]}""").path("id").asString(), "receive", """{"expectedRevision":0}""")
        for (path in paths) {
            assertThat(request("GET", "/api/v1/warehouse/$path?serial=FOREIGN-ONLY", setup.token).contentAsString).isEqualTo(missing.contentAsString)
            assertThat(request("GET", "/api/v1/warehouse/$path?serial=MANUAL-1", foreign.token).contentAsString).isEqualTo(missing.contentAsString)
        }
        assertThat(received.path("state").asString()).isEqualTo("RECEIVED_IN_INSPECTION")
    }

    @Test fun `AV9-02 hidden legacy duplicate cannot make serial history choose the visible asset`() {
        val setup = setupReceipt()
        transition(setup, draft(setup, """{"skuId":"${setup.onu}","quantityBase":"1","serials":[{"serial":"AMBIGUOUS"}]}""").path("id").asString(), "receive", """{"expectedRevision":0}""")
        val position = mapper.readTree(request("GET", "/api/v1/warehouse/stock/positions", setup.token).contentAsString).path("items")[0]
        val fixture = fixture(setup.token)
        val hidden = UUID.randomUUID()
        fixture.transaction { sql("INSERT INTO inventory_location(id,tenant_id,code,kind,area_id) VALUES ('$hidden','$tenant','HIDDEN','WAREHOUSE','${area(setup.token)}')") }
        context.getBean(org.flywaydb.core.Flyway::class.java).configuration.dataSource.connection.use { connection ->
            connection.autoCommit=false
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL app.tenant_id='${fixture.tenant}'")
                statement.execute("""INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,canonical_serial_candidate,status,location_id,custody_owner_id,custody_owner_kind,warehouse_admission)
                    VALUES ('${UUID.randomUUID()}','${fixture.tenant}','${setup.onu}',' ambiguous ','AMBIGUOUS','AVAILABLE','$hidden','$hidden','WAREHOUSE','LEGACY_UNRESOLVED')""")
            }
            connection.commit()
        }
        val missing = request("GET", "/api/v1/warehouse/assets/${UUID.randomUUID()}/history", setup.token)
        for (path in listOf("stock/positions/${position.path("id").asString()}/history", "assets/${position.path("stockIdentityId").asString()}/history")) {
            val response = request("GET", "/api/v1/warehouse/$path?serial=AMBIGUOUS", setup.token)
            assertThat(response.status).isEqualTo(404)
            assertThat(response.contentAsString).isEqualTo(missing.contentAsString)
            assertThat(request("GET", "/api/v1/warehouse/$path", setup.token).status).isEqualTo(200)
        }
    }
}
