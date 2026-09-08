package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.service.InventoryTenantPolicyService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseMasterITCutover : WarehouseMasterHttpFixture() {
    companion object {
        private val legacyTenant = UUID.randomUUID()
        private val legacySlug = "legacy-master-${UUID.randomUUID()}"
        private val database = WarehouseSchemaDatabase("172").also { database ->
            database.ownerFixture { connection -> connection.createStatement().use {
                it.execute("INSERT INTO tenant(id,slug,name) VALUES ('$legacyTenant','$legacySlug','Legacy masters')")
            } }
        }
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
        }
        @JvmStatic @AfterAll fun cleanup() { database.close() }
    }

    @Test fun `LEGACY and VALIDATING allow HTTP masters but not ordinary stock or stale cutover replay`() {
        val token = tenant(legacySlug)
        val fixture = fixture(token)
        val policy = context.getBean(InventoryTenantPolicyService::class.java)
        assertThat(fixture.transaction { policy.read().state }).isEqualTo(WarehouseCutoverState.LEGACY)
        val body = """{"code":"OLD-SUP","name":"Legacy supplier"}"""
        val key = UUID.randomUUID().toString()
        assertThat(request("POST", "/api/v1/warehouse/suppliers", token, body, key).status).isEqualTo(201)
        assertThatThrownBy { fixture.transaction { policy.lockForCommand(0, WarehouseOperationClass.ORDINARY_STOCK) } }
            .isInstanceOf(WarehouseContractException::class.java)
        fixture.transaction { policy.beginValidation(0) }
        assertThat(fixture.transaction { policy.read().state }).isEqualTo(WarehouseCutoverState.VALIDATING)
        create("locations", token, """{"code":"VALIDATING","name":"Validation warehouse","kind":"WAREHOUSE"}""")
        create("skus", token, """{"code":"VALIDATING","name":"Validation SKU","tracking":"BULK","baseUnit":"EA"}""")
        assertThat(request("POST", "/api/v1/warehouse/suppliers", token, body, key).status).isEqualTo(409)
        assertThatThrownBy { fixture.transaction { policy.lockForCommand(1, WarehouseOperationClass.ORDINARY_STOCK) } }
            .isInstanceOf(WarehouseContractException::class.java)
    }

    @Test fun `unresolved legacy unit remains scoped visible with provenance badge and blocks archive`() {
        val token = tenant()
        val fixture = fixture(token)
        val sku = create("skus", token, """{"code":"LEGACY","name":"Legacy SKU","tracking":"SERIAL","baseUnit":"EA"}""").path("id").asString()
        val location = create("locations", token, """{"code":"LEGACY","name":"Legacy warehouse","kind":"WAREHOUSE"}""").path("id").asString()
        database.ownerFixture { connection ->
            connection.createStatement().use { sql ->
                sql.execute("SET app.tenant_id='${fixture.tenant}'")
                sql.execute("INSERT INTO inventory_serialized_asset(id,tenant_id,sku_id,serial_number,canonical_serial_candidate,status,location_id,custody_owner_id,custody_owner_kind,warehouse_admission) VALUES ('${UUID.randomUUID()}','${fixture.tenant}','$sku','legacy-uncertain','LEGACY-UNCERTAIN','AVAILABLE','$location','$location','WAREHOUSE','LEGACY_UNRESOLVED')")
            }
        }
        val response = request("GET", "/api/v1/warehouse/assets/lookup?value=legacy-uncertain", token)
        assertThat(response.status).withFailMessage(response.contentAsString).isEqualTo(200)
        assertThat(mapper.readTree(response.contentAsString).path("legacyUnresolved").asBoolean()).isTrue()
        val foreign = tenant()
        assertThat(request("GET", "/api/v1/warehouse/assets/lookup?value=legacy-uncertain", foreign).contentAsString)
            .isEqualTo(request("GET", "/api/v1/warehouse/assets/lookup?value=absent", foreign).contentAsString)
        assertThat(request("POST", "/api/v1/warehouse/skus/$sku/archive", token, """{"expectedRevision":0}""").status).isEqualTo(409)
        assertThat(request("POST", "/api/v1/warehouse/locations/$location/archive", token, """{"expectedRevision":0}""").status).isEqualTo(409)
    }
}
