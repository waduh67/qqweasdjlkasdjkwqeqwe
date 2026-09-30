package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.domain.model.ReplenishmentNeed
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigInteger

class WarehouseReplenishmentITFormula {
    @Test fun `exact package ceiling and threshold apply identically to EA and MM`() {
        StockUnit.entries.forEach { unit ->
            val need = need(unit, 50, 100, 100, 25)
            assertThat(need.quantity(BigInteger.valueOf(40), BigInteger.ZERO).quantityBase).isEqualTo(75)
            assertThat(need.quantity(BigInteger.valueOf(40), BigInteger.TEN).quantityBase).isZero()
            assertThat(need.quantity(BigInteger.valueOf(30), BigInteger.TEN).quantityBase).isEqualTo(75)
            assertThat(need.quantity(BigInteger.ZERO, Long.MAX_VALUE.toBigInteger() * BigInteger.TWO).quantityBase).isZero()
        }
    }

    @Test fun `zero and equal thresholds are allowed but invalid ranges and ceiling overflow reject`() {
        assertThat(need(StockUnit.MM, 0, 0, 0, 1).quantity(BigInteger.ZERO, BigInteger.ZERO).quantityBase).isZero()
        assertThatThrownBy { need(StockUnit.EA, 2, 1, 1, 1) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { need(StockUnit.EA, 1, 2, 3, 1) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { need(StockUnit.EA, 1, 2, 2, 0) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { need(StockUnit.MM, 1, Long.MAX_VALUE, Long.MAX_VALUE, 2).quantity(BigInteger.ZERO, BigInteger.ZERO) }
            .isInstanceOf(ValidationException::class.java)
    }

    private fun need(unit: StockUnit, min: Long, max: Long, target: Long, multiple: Long) = ReplenishmentNeed(
        StockQuantity.of(min, unit), StockQuantity.of(max, unit), StockQuantity.of(target, unit), StockQuantity.of(multiple, unit), 0)
}
