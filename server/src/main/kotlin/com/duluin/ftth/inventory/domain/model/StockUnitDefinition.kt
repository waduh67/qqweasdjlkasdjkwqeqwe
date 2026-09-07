package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.error.ValidationException

enum class StockTracking { SERIAL, LOT, BULK }

class StockUnitDefinition private constructor(val tracking: StockTracking, val baseUnit: StockUnit) {
    fun parseQuantity(quantityBase: String): StockQuantity {
        val quantity = StockQuantity.parseBase(quantityBase, baseUnit)
        if (tracking == StockTracking.SERIAL && quantity.quantityBase != 1L) {
            throw ValidationException("Satu aset serial harus tepat 1 EA")
        }
        return quantity
    }

    override fun equals(other: Any?): Boolean =
        other is StockUnitDefinition && tracking == other.tracking && baseUnit == other.baseUnit

    override fun hashCode(): Int = 31 * tracking.hashCode() + baseUnit.hashCode()

    companion object {
        fun of(tracking: StockTracking, baseUnit: StockUnit): StockUnitDefinition {
            if (tracking == StockTracking.SERIAL && baseUnit != StockUnit.EA) {
                throw ValidationException("SKU serial harus menggunakan EA")
            }
            return StockUnitDefinition(tracking, baseUnit)
        }
    }
}
