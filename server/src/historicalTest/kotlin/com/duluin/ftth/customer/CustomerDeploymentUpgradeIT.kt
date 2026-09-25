package com.duluin.ftth.customer

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import com.duluin.ftth.inventory.WarehouseMigrationInventory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerDeploymentUpgradeIT : CustomerDeploymentGraphFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.63") }
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.63" }
        }
    }
    @AfterAll fun closeDatabase() { database.close() }

    @Test
    fun `upgrade preserves admitted corruption but fails validated reads and private replay`() {
        val factCase = installation()
        val orphanCase = installation()
        val valid = installation(generic = true)
        for (case in listOf(factCase, orphanCase, valid)) assertThat(consume(case).status).isEqualTo(201)
        val orphan = UUID.randomUUID()
        fixture(factCase.receipt.stock.token).transaction { sql(extraFact(factCase)) }
        fixture(orphanCase.receipt.stock.token).transaction {
            clonedDocument(orphanCase, orphan).forEach(::sql)
            sql("UPDATE inventory_document SET state='POSTED',revision=1 WHERE id='$orphan'")
        }
        val cases = listOf(factCase, orphanCase)
        val before = cases.map(::fingerprint)
        val validBody = consume(valid).contentAsString

        WarehouseMigrationInventory.assertUpgrade("175.63", database.migrate())

        cases.forEachIndexed { index, case ->
            assertThrows<Exception> { fixture(case.receipt.stock.token).transaction {
                sql("SELECT warehouse_read_deployment_authorization('$tenant','${case.authorization}')")
            } }
            val replay = consume(case)
            assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(409)
            assertThat(fingerprint(case)).isEqualTo(before[index])
        }
        assertThat(consume(valid).contentAsString).isEqualTo(validBody)
        assertThat(database.migrate().migrationsExecuted).isZero()
    }

    private fun fingerprint(case: Installation): String = fixture(case.receipt.stock.token).transaction {
        scalar("""SELECT concat_ws('|',
            (SELECT count(*) FROM inventory_customer_material_fact),(SELECT count(*) FROM inventory_document WHERE kind='DEPLOYMENT'),
            (SELECT count(*) FROM inventory_movement),(SELECT count(*) FROM inventory_asset_assignment),
            (SELECT count(*) FROM customer_asset_installation),(SELECT count(*) FROM onu),
            (SELECT md5(string_agg(snapshot::text,'|' ORDER BY revision)) FROM inventory_deployment_authorization_history WHERE authorization_id='${case.authorization}'))""")
    }
}
