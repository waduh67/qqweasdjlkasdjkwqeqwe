package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

class WarehouseDraftSchemaStartupIT {
    @Test fun `current application refuses a pre-expiry schema even with an explicit older Flyway target`() {
        WarehouseSchemaDatabase("178.11").use { database ->
            refused { postingContext(database, "178.11").close() }
        }
    }

    @Test fun `current application refuses a partially disabled clock installation`() {
        WarehouseSchemaDatabase().use { database ->
            database.ownerFixture { connection -> connection.createStatement().use {
                it.execute("ALTER TABLE inventory_document DISABLE TRIGGER warehouse_aaa_draft_live")
            } }
            refused { postingContext(database).close() }
        }
    }

    private fun refused(start: () -> Unit) {
        val failure = catchThrowable(start)
        assertThat(failure).isNotNull()
        assertThat(generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList())
            .anyMatch { it.contains("Warehouse idle draft schema is incomplete") }
    }
}
