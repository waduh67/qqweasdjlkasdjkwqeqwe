package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigInteger

class ReplenishmentNeed(
    val minimum: StockQuantity, val maximum: StockQuantity, val target: StockQuantity,
    val packageMultiple: StockQuantity, val leadTimeDays: Int,
) {
    init {
        if (setOf(minimum.unit, maximum.unit, target.unit, packageMultiple.unit).size != 1 ||
            maximum.quantityBase < minimum.quantityBase || target.quantityBase !in minimum.quantityBase..maximum.quantityBase ||
            packageMultiple.quantityBase == 0L || leadTimeDays !in 0..3650)
            throw ValidationException("Invalid replenishment thresholds, units, package multiple or lead time")
    }

    fun quantity(available: BigInteger, confirmedInbound: BigInteger): StockQuantity {
        if (available.signum() < 0 || confirmedInbound.signum() < 0)
            throw ValidationException("Replenishment position cannot be negative")
        val position = available + confirmedInbound
        if (position >= minimum.quantityBase.toBigInteger()) return StockQuantity.of(0, minimum.unit)
        val deficit = target.quantityBase.toBigInteger() - position
        val multiple = packageMultiple.quantityBase.toBigInteger()
        val rounded = ((deficit + multiple - BigInteger.ONE) / multiple) * multiple
        return StockQuantity.of(checkedStockArithmetic { rounded.longValueExact() }, minimum.unit)
    }
}
