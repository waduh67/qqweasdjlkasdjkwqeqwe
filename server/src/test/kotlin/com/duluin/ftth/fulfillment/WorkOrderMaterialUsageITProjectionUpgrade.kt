package com.duluin.ftth.fulfillment

import com.duluin.ftth.inventory.WarehouseSchemaDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkOrderMaterialUsageITProjectionUpgrade : MaterialUsageProjectionFixture() {
    companion object {
        private val database by lazy { WarehouseSchemaDatabase("175.21") }

        @JvmStatic @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.flyway.url") { database.url }
            registry.add("spring.flyway.schemas") { database.schema }
            registry.add("spring.flyway.default-schema") { database.schema }
            registry.add("spring.flyway.target") { "175.21" }
        }
    }

    @AfterAll fun closeDatabase() {
        database.close()
    }

    @Test fun `upgrade rejects replay of committed historical zero and descendant corruption without repair`() {
        val zero = consumedCase()
        val descendant = consumedCase()
        changeProjection(zero, "zero")
        changeProjection(descendant, "deep-child")
        val cases = listOf(zero, descendant)
        val before = cases.map { usageAccounting(it.usage) }
        cases.forEach { assertThat(use(it.usage).status).isEqualTo(200) }

        assertThat(database.migrate().migrationsExecuted).isEqualTo(1)

        cases.forEachIndexed { index, case ->
            val replay = use(case.usage)
            assertThat(replay.status).withFailMessage(replay.contentAsString).isEqualTo(409)
            assertThat(replay.contentAsString).isNotEqualTo(case.body)
            assertThat(request("GET", "/api/v1/warehouse/my-material-usage/${case.id}", case.usage.receipt.receiver.first).status).isEqualTo(409)
            assertThat(usageAccounting(case.usage)).isEqualTo(before[index])
        }
    }
}
