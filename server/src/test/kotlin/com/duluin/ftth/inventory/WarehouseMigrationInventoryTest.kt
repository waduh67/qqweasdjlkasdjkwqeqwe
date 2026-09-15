package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WarehouseMigrationInventoryTest {
    @Test
    fun `packaged classpath includes the required historical migration range`() {
        val versions = WarehouseMigrationInventory.expectedVersions("175.34")

        assertThat(versions).contains("175.35", "175.67", "175.79")
    }
    @ParameterizedTest
    @CsvSource("175.34,45", "175.29,50", "175.23,56", "175.24,55", "latest,0")
    fun `current historical inventory yields the required delta`(starting: String, expected: Int) {
        val inventory = WarehouseMigrationInventory.required

        val versions = WarehouseMigrationInventory.expectedVersions(starting, inventory)

        assertThat(versions).hasSize(expected)
    }

    @Test
    fun `appended forward migration contributes without updating historical counts`() {
        val inventory = WarehouseMigrationInventory.required + "V175_80__future_forward_migration.sql"

        val versions = WarehouseMigrationInventory.expectedVersions("175.34", inventory.reversed())

        assertThat(versions).hasSize(46).endsWith("175.79", "175.80")
    }

    @Test
    fun `missing required migration rejects instead of lowering the expected count`() {
        val inventory = WarehouseMigrationInventory.required.filterNot { it.startsWith("V175_67__") }

        assertThrows<IllegalArgumentException> { WarehouseMigrationInventory.expectedVersions("175.34", inventory) }
    }

    @Test
    fun `renumbered historical migration rejects instead of accepting changed order`() {
        val inventory = WarehouseMigrationInventory.required.map { it.replace("V175_67__", "V175_80__") }

        assertThrows<IllegalArgumentException> { WarehouseMigrationInventory.expectedVersions("175.34", inventory) }
    }

    @Test
    fun `duplicate versions and forward gaps reject ambiguous inventory`() {
        val baseline = WarehouseMigrationInventory.required

        assertThrows<IllegalArgumentException> {
            WarehouseMigrationInventory.expectedVersions("175.34", baseline + "V175_79__duplicate.sql")
        }
        assertThrows<IllegalArgumentException> {
            WarehouseMigrationInventory.expectedVersions("175.34", baseline + "V175_81__gap.sql")
        }
    }
}
