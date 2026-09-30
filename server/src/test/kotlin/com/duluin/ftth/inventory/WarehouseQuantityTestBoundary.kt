package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.application.toReceiptConversion
import com.duluin.ftth.inventory.application.toReceiptConversionSnapshot
import com.duluin.ftth.inventory.application.toStockQuantity
import com.duluin.ftth.inventory.application.toWarehouseQuantity
import com.duluin.ftth.inventory.domain.model.StockQuantity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WarehouseQuantityTestBoundary {
    @Test
    fun `wire output retains exact quantities and fixed three decimal metre display`() {
        listOf("0", "1", "82.5", "82.500", "0.001", "0.01", "0001.010", "9223372036854775.807").forEach { input ->
            val quantity = StockQuantity.metres(input)
            val wire = quantity.toWarehouseQuantity()
            assertThat(wire).isEqualTo(WarehouseQuantity(quantity.quantityBase.toString(), WarehouseBaseUnit.MM, quantity.toMetres(), WarehouseDisplayUnit.M))
            assertThat(wire.toStockQuantity()).isEqualTo(quantity)
        }
        assertThat(StockQuantity.metres("82.5").toWarehouseQuantity().displayQuantity).isEqualTo("82.500")
        assertThat(StockQuantity.each("1").toWarehouseQuantity()).isEqualTo(WarehouseQuantity("1", WarehouseBaseUnit.EA, "1", WarehouseDisplayUnit.EA))
        assertThat(StockQuantity.each("1").toWarehouseQuantity().toStockQuantity()).isEqualTo(StockQuantity.each("1"))
    }

    @Test
    fun `wire decoding compares both exact quantities and their units`() {
        val good = WarehouseQuantity("82500", WarehouseBaseUnit.MM, "82.500", WarehouseDisplayUnit.M)
        assertThat(good.copy(displayQuantity = "82.5").toStockQuantity()).isEqualTo(good.toStockQuantity())
        listOf(good.copy(quantityBase = "82501"), good.copy(quantityBase = "82.5"),
            good.copy(quantityBase = "-1"), good.copy(displayQuantity = "0.0001"),
            good.copy(displayQuantity = "NaN"), good.copy(displayUnit = WarehouseDisplayUnit.EA),
            good.copy(baseUnit = WarehouseBaseUnit.EA),
            WarehouseQuantity("1", WarehouseBaseUnit.EA, "1.0", WarehouseDisplayUnit.EA),
            WarehouseQuantity("1", WarehouseBaseUnit.EA, "2", WarehouseDisplayUnit.EA)).forEach { wire ->
            assertThatThrownBy { wire.toStockQuantity() }.isInstanceOfSatisfying(WarehouseContractException::class.java) {
                assertThat(it.error.code).isEqualTo(WarehouseErrorCode.MALFORMED_REQUEST)
            }
        }
    }

    @Test
    fun `receipt wire preserves ratio components and maps invalid input to typed boundary errors`() {
        val wire = ReceiptConversionSnapshot("2000", "2")
        assertThat(wire.toReceiptConversion().toReceiptConversionSnapshot()).isEqualTo(wire)
        listOf("", "0", "-1", "+1", "1.5", "NaN", "Infinity", "1e3", " ", "\u0661", "9223372036854775808").forEach { input ->
            listOf(ReceiptConversionSnapshot(input, "1"), ReceiptConversionSnapshot("1", input)).forEach { invalid ->
                assertThatThrownBy { invalid.toReceiptConversion() }.isInstanceOfSatisfying(WarehouseContractException::class.java) {
                    assertThat(it.error.code).isEqualTo(WarehouseErrorCode.MALFORMED_REQUEST)
                }
            }
        }
    }
}
