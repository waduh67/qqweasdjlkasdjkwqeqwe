package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerAssetTitleUpgradeIT : CustomerAssetOwnershipFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.69") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.69" }
        }
    }
    @AfterAll fun cleanup() { database.close() }

    @Test
    fun `unsealed admitted acceptance survives upgrade unchanged but cannot authorize a correction`() {
        val old = ownershipCase("SALE")
        val accepted = accept(old)
        assertThat(accepted.status).withFailMessage(accepted.contentAsString).isEqualTo(200)
        val handover = mapper.readTree(accepted.contentAsString).path("acceptedHandover").path("handoverId").asString()
        val stock = fixture(old.installation.receipt.stock.token)
        val fingerprint = stock.transaction { scalar("SELECT md5(snapshot) FROM inventory_asset_acceptance WHERE handover_id='$handover'") }

        val migration = database.migrate()

        assertThat(migration.migrationsExecuted).isGreaterThan(0)
        stock.transaction {
            assertThat(scalar("SELECT md5(snapshot) FROM inventory_asset_acceptance WHERE handover_id='$handover'")).isEqualTo(fingerprint)
            assertThat(scalar("SELECT count(*) FROM inventory_asset_acceptance_origin WHERE handover_id='$handover'")).isEqualTo("0")
        }
        assertThrows<Exception> { stock.transaction { sql("SELECT warehouse_assert_asset_handover('$tenant','$handover')") } }
        assertThat(accept(old).status).isEqualTo(409)
        val fresh = ownershipCase()
        assertThat(accept(fresh).status).isEqualTo(200)
        assertThat(database.migrate().migrationsExecuted).isZero()
    }
}
