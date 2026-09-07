package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockTracking
import com.duluin.ftth.inventory.domain.model.StockUnit
import com.duluin.ftth.inventory.domain.model.StockUnitDefinition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import kotlin.random.Random

class WarehouseQuantityTest {
    @ParameterizedTest
    @CsvSource("0,0,0.000", "1,1000,1.000", "82.5,82500,82.500", "82.500,82500,82.500",
        "0.001,1,0.001", "0.01,10,0.010", "0001.010,1010,1.010",
        "9223372036854775.807,9223372036854775807,9223372036854775.807")
    fun `metres parse and format exactly`(input: String, base: Long, display: String) {
        val quantity = StockQuantity.metres(input)
        assertThat(quantity.quantityBase).isEqualTo(base)
        assertThat(quantity.unit).isEqualTo(StockUnit.MM)
        assertThat(quantity.toMetres()).isEqualTo(display)
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = [" ", "\t", " 1", "1 ", "1\n", "+1", "-1", "-0", "1e3", "1E-3",
        "NaN", "Infinity", "-Infinity", ".1", "1.", "1,5", "1_000", "1/2", "0x10",
        "\u0661", "\uFF11", "1.\u0661", "1\u0000", "1..0", "0.0001", "82.5000",
        "9223372036854775.808", "9223372036854776", "999999999999999999999999"])
    fun `malformed and overflowing metre values fail without rounding`(input: String) {
        assertThatThrownBy { StockQuantity.metres(input) }.isInstanceOf(ValidationException::class.java)
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = [" ", "+1", "-1", "1e3", "NaN", "Infinity", "\u0661", "1.5", "1.0", "0.001", "9223372036854775808"])
    fun `base quantities and EA accept only unsigned ASCII integer syntax`(input: String) {
        StockUnit.entries.forEach { unit ->
            assertThatThrownBy { StockQuantity.parseBase(input, unit) }.isInstanceOf(ValidationException::class.java)
        }
        assertThatThrownBy { StockQuantity.each(input) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `reel cut and installed cut conserve all millimetres`() {
        val reel = StockQuantity.metres("1000")
        val issued = StockQuantity.metres("100")
        val warehouse = reel - issued
        val installed = StockQuantity.metres("82.500")
        val remnant = issued - installed
        assertThat(warehouse).isEqualTo(StockQuantity.metres("900"))
        assertThat(remnant).isEqualTo(StockQuantity.metres("17.5"))
        assertThat(warehouse + issued).isEqualTo(reel)
        assertThat(installed + remnant).isEqualTo(issued)
        assertThat(warehouse + installed + remnant).isEqualTo(reel)
        assertThat(warehouse + remnant).isEqualTo(StockQuantity.metres("917.500"))
        assertThat(reel.quantityBase).isEqualTo(1000000L)
    }

    @Test
    fun `addition subtraction and multiplication are checked for both units`() {
        StockUnit.entries.forEach { unit ->
            val maximum = StockQuantity.of(Long.MAX_VALUE, unit)
            val one = StockQuantity.of(1L, unit)
            val zero = StockQuantity.of(0L, unit)
            assertThat(maximum + zero).isEqualTo(maximum)
            assertThat(maximum - maximum).isEqualTo(zero)
            assertThat(maximum * 0L).isEqualTo(zero)
            assertThat(maximum * 1L).isEqualTo(maximum)
            assertThat((maximum - one) + one).isEqualTo(maximum)
            assertThatThrownBy { maximum + one }.isInstanceOf(ValidationException::class.java)
            assertThatThrownBy { maximum * 2L }.isInstanceOf(ValidationException::class.java)
            assertThatThrownBy { zero - one }.isInstanceOf(ConflictException::class.java)
            listOf(-1L, Long.MIN_VALUE).forEach { negative ->
                assertThatThrownBy { StockQuantity.of(negative, unit) }.isInstanceOf(ValidationException::class.java)
                assertThatThrownBy { one * negative }.isInstanceOf(ValidationException::class.java)
            }
        }
    }

    @Test
    fun `different dimensions cannot be combined or displayed as another unit`() {
        val each = StockQuantity.each("1")
        val length = StockQuantity.metres("0.001")
        assertThat(each).isNotEqualTo(length)
        assertThatThrownBy { each + length }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { length - each }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { each.toMetres() }.isInstanceOf(ValidationException::class.java)
        assertThat(StockQuantity.each("0001")).isEqualTo(each).hasSameHashCodeAs(each)
    }

    @Test
    fun `SKU unit definitions and quantities expose no mutable or copy bypass`() {
        val serial = StockUnitDefinition.of(StockTracking.SERIAL, StockUnit.EA)
        assertThat(serial.parseQuantity("1")).isEqualTo(StockQuantity.each("1"))
        listOf("0", "2", "1.5").forEach { input ->
            assertThatThrownBy { serial.parseQuantity(input) }.isInstanceOf(ValidationException::class.java)
        }
        assertThatThrownBy { StockUnitDefinition.of(StockTracking.SERIAL, StockUnit.MM) }.isInstanceOf(ValidationException::class.java)
        listOf(StockTracking.LOT, StockTracking.BULK).forEach { tracking ->
            StockUnit.entries.forEach { unit ->
                assertThat(StockUnitDefinition.of(tracking, unit).parseQuantity("2")).isEqualTo(StockQuantity.of(2L, unit))
            }
        }
        listOf(StockQuantity::class.java, StockUnitDefinition::class.java).forEach { type ->
            assertThat(type.declaredConstructors.filterNot { it.isSynthetic }).allMatch { Modifier.isPrivate(it.modifiers) }
            assertThat(type.methods.map { it.name }).noneMatch { it.startsWith("set") || it == "copy" }
        }
    }

    @Test
    fun `deterministic large value splits and decimal round trips conserve stock`() {
        val random = Random(823500)
        repeat(1000) {
            val base = random.nextLong(1L, Long.MAX_VALUE)
            val parent = StockQuantity.of(base, StockUnit.MM)
            val cut = StockQuantity.of(random.nextLong(base), StockUnit.MM)
            assertThat((parent - cut) + cut).isEqualTo(parent)
            assertThat(StockQuantity.metres(parent.toMetres())).isEqualTo(parent)
        }
    }
}
