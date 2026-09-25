package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMigrationITQuery : WarehousePolicyHttpFixture() {
    companion object {
        private val legacy = WarehouseMigrationLegacyFixture()
        private val database = WarehouseSchemaDatabase("172").also { it.ownerFixture(legacy::seed) }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }

    @Test fun `booted colliding legacy report preserves exact snapshots and authorizes all locations and customer areas before counts`() {
        val admin = tenant("migration-${legacy.tenant}")
        val me = mapper.readTree(request("GET", "/api/me", admin).contentAsString).path("id").asString()
        // Reference metadata setup only. Legacy physical rows came through V172 and every packaged migration.
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${legacy.tenant}'")
            sql.execute("""UPDATE inventory_location SET name='Gudang lama',area_id='${area(admin)}',revision=revision+1
                WHERE tenant_id='${legacy.tenant}' AND id='${legacy.location}'""")
            sql.execute("UPDATE customer SET area_id='${area(admin)}' WHERE tenant_id='${legacy.tenant}' AND id='${legacy.customer}'")
        } }
        grant(admin, me, listOf(legacy.location.toString()))
        val report = request("GET", "/api/v1/warehouse/provenance", admin)
        assertThat(report.status).withFailMessage(report.contentAsString).isEqualTo(200)
        assertThat(report.getHeader("Cache-Control")).contains("no-store")
        val summary = mapper.readTree(report.contentAsString)
        assertThat(summary.path("sourceCount").asInt()).isEqualTo(11)
        assertThat(summary.path("conflictGroupCount").asInt()).isEqualTo(3)
        assertThat(summary.path("unitUnverifiedBalanceCount").asInt()).isEqualTo(1)
        assertThat(summary.path("pendingLegacyMovementCount").asInt()).isEqualTo(1)
        assertThat(summary.path("sourceCounts").path("inventory_fulfillment_effect").asInt()).isZero()
        assertThat(summary.path("sourceCounts").path("inventory_customer_material_fact").asInt()).isZero()
        assertThat(summary.path("cutover").path("state").asString()).isEqualTo("LEGACY")
        assertThat(summary.path("batch").isNull).isTrue()
        assertThat(request("GET", "/api/v1/warehouse/provenance", admin).contentAsString).isEqualTo(report.contentAsString)

        val page = request("GET", "/api/v1/warehouse/provenance/cases?sourceTable=inventory_serialized_asset&size=2", admin)
        assertThat(page.status).withFailMessage(page.contentAsString).isEqualTo(200)
        val pageBody = mapper.readTree(page.contentAsString)
        assertThat(pageBody.path("totalElements").asInt()).isEqualTo(3)
        assertThat(pageBody.path("items").size()).isEqualTo(2)
        val source = pageBody.path("items")[0]
        assertThat(source.path("location").path("name").asString()).isEqualTo("Gudang lama")
        assertThat(source.path("claims").size()).isEqualTo(2)
        val casePath = "/api/v1/warehouse/provenance/cases/${source.path("id").asString()}"
        assertThat(mapper.readTree(request("GET", casePath, admin).contentAsString)).isEqualTo(source)
        val balances = mapper.readTree(request("GET", "/api/v1/warehouse/provenance/cases?sourceTable=inventory_balance_projection", admin).contentAsString)
        assertThat(balances.path("items")[0].path("sourceSnapshot").path("legacyQuantity").asString()).isEqualTo("82500")
        assertThat(balances.path("items")[0].path("sourceSnapshot").path("baseUnit").isNull).isTrue()
        val onus = request("GET", "/api/v1/warehouse/provenance/cases?sourceTable=onu", admin)
        assertThat(onus.contentAsString).contains("Legacy customer", "UNMATCHED-ONU").doesNotContain("Private address")
        assertThat(mapper.readTree(onus.contentAsString).path("items")[0].path("customer").path("areaId").asString()).isEqualTo(area(admin))

        val viewer = user(admin, setOf("inventory.provenance.manage"))
        assertThat(request("GET", "/api/v1/warehouse/provenance", viewer.first).status).isEqualTo(404)
        grant(admin, viewer.second, listOf(legacy.location.toString()))
        assertThat(request("GET", "/api/v1/warehouse/provenance", viewer.first).status).isEqualTo(200)
        val otherArea = request("POST", "/api/areas", admin, """{"code":"OTHER","name":"Other area"}""")
        assertThat(otherArea.status).isEqualTo(201)
        val otherAreaId = mapper.readTree(otherArea.contentAsString).path("id").asString()
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${legacy.tenant}'")
            sql.execute("UPDATE customer SET area_id='$otherAreaId' WHERE tenant_id='${legacy.tenant}' AND id='${legacy.customer}'")
        } }
        // Location access still holds. The current customer area, not its frozen snapshot, now denies the whole report.
        assertThat(request("GET", "/api/v1/warehouse/provenance", viewer.first).status).isEqualTo(404)
        database.ownerFixture { connection -> connection.createStatement().use { sql ->
            sql.execute("SET app.tenant_id='${legacy.tenant}'")
            sql.execute("UPDATE customer SET area_id='${area(admin)}' WHERE tenant_id='${legacy.tenant}' AND id='${legacy.customer}'")
        } }
        assertThat(request("GET", "/api/v1/warehouse/provenance", viewer.first).status).isEqualTo(200)
        val principal = mapper.readTree(request("GET", "/api/users/${viewer.second}", admin).contentAsString)
        val revoked = request("PUT", "/api/users/${viewer.second}/access", admin, mapper.writeValueAsString(mapOf(
            "roleIds" to principal.path("roleIds").asSequence().map { it.asString() }.toList(), "areaIds" to emptyList<String>())))
        assertThat(revoked.status).isEqualTo(200)
        assertThat(request("GET", "/api/v1/warehouse/provenance/cases", viewer.first).status).isEqualTo(404)
        assertThat(request("GET", casePath, viewer.first).status).isEqualTo(404)
        val denied = user(admin, setOf("inventory.item.view"))
        assertThat(request("GET", "/api/v1/warehouse/provenance", denied.first).status).isEqualTo(403)
        val foreign = tenant()
        assertThat(request("GET", casePath, foreign).status).isEqualTo(404)
        assertThat(mapper.readTree(request("GET", "/api/v1/warehouse/provenance", foreign).contentAsString).path("sourceCount").asInt()).isZero()
        for (query in listOf("tenantId=${legacy.tenant}", "page=0&page=1", "size=101", "sourceTable=customer", "page=-1"))
            assertThat(request("GET", "/api/v1/warehouse/provenance/cases?$query", admin).status).isEqualTo(400)
        assertThat(request("GET", "/api/v1/warehouse/provenance?tenantId=${legacy.tenant}", admin).status).isEqualTo(400)

        val beginBody = mapper.writeValueAsString(mapOf("expectedEpoch" to 0, "expectedPreservationHash" to summary.path("preservationHash").asString()))
        val wrongSource = mapper.writeValueAsString(mapOf("expectedEpoch" to 0, "expectedPreservationHash" to "0".repeat(64)))
        assertThat(request("POST", "/api/v1/warehouse/provenance/batches", admin, wrongSource).status).isEqualTo(409)
        val key = java.util.UUID.randomUUID().toString()
        val begin = request("POST", "/api/v1/warehouse/provenance/batches", admin, beginBody, key)
        assertThat(begin.status).withFailMessage(begin.contentAsString).isEqualTo(201)
        val retry = request("POST", "/api/v1/warehouse/provenance/batches", admin, beginBody, key)
        assertThat(retry.status).isEqualTo(201)
        assertThat(retry.contentAsString).isEqualTo(begin.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/provenance/batches", admin, wrongSource, key).status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/provenance/batches", admin, beginBody).status).isEqualTo(409)
        val otherActor = user(admin, setOf("inventory.provenance.manage"))
        grant(admin, otherActor.second, listOf(legacy.location.toString()))
        assertThat(request("POST", "/api/v1/warehouse/provenance/batches", otherActor.first, beginBody, key).status).isEqualTo(403)
        val validating = mapper.readTree(request("GET", "/api/v1/warehouse/provenance", admin).contentAsString)
        assertThat(validating.path("cutover").path("state").asString()).isEqualTo("VALIDATING")
        assertThat(validating.path("preservationHash")).isEqualTo(summary.path("preservationHash"))
        assertThat(validating.path("batch").path("sourceCount").asInt()).isEqualTo(11)
        assertThat(validating.path("batch").path("sourceHash")).isEqualTo(summary.path("preservationHash"))
        val policy = context.getBean(InventoryTenantPolicyService::class.java)
        org.junit.jupiter.api.assertThrows<WarehouseContractException> {
            fixture(admin).transaction { policy.lockForCommand(1, WarehouseOperationClass.ORDINARY_STOCK) }
        }
        fixture(admin).transaction {
            assertThat(scalar("SELECT count(*) FROM inventory_document")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_balance_projection WHERE warehouse_admission='VERIFIED'")).isEqualTo("0")
            assertThat(scalar("SELECT count(*) FROM inventory_movement")).isEqualTo("2")
            assertThat(scalar("SELECT count(*) FROM inventory_migration_batch")).isEqualTo("1")
            assertThat(scalar("SELECT count(*) FROM inventory_migration_command")).isEqualTo("1")
        }
    }

    @Test fun `existing validating empty tenant captures its original fence without inventing another cutoff`() {
        val admin = tenant("migration-${legacy.otherTenant}")
        val policy = context.getBean(InventoryTenantPolicyService::class.java)
        val before = requireNotNull(fixture(admin).transaction { policy.beginValidation(0) })
        val summary = mapper.readTree(request("GET", "/api/v1/warehouse/provenance", admin).contentAsString)
        assertThat(summary.path("sourceCount").asInt()).isZero()
        val body = mapper.writeValueAsString(mapOf("expectedEpoch" to 1, "expectedPreservationHash" to summary.path("preservationHash").asString()))
        val key = java.util.UUID.randomUUID().toString()
        val response = request("POST", "/api/v1/warehouse/provenance/batches", admin, body, key)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(201)
        val batch = mapper.readTree(response.contentAsString).path("batch")
        assertThat(batch.path("id").asString()).isEqualTo(before.migrationBatchId.toString())
        assertThat(batch.path("snapshotWatermark").asString()).isEqualTo(before.snapshotWatermark)
        assertThat(batch.path("sourceCount").asInt()).isZero()
        assertThat(request("POST", "/api/v1/warehouse/provenance/batches", admin, body, key).contentAsString).isEqualTo(response.contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/provenance/batches", admin, body).status).isEqualTo(409)
        assertThat(fixture(admin).transaction { policy.read() }).isEqualTo(before)
    }
}
