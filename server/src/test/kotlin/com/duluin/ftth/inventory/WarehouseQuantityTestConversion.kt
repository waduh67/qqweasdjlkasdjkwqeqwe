package com.duluin.ftth.inventory

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.domain.model.ReceiptConversion
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EmptySource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import java.math.BigInteger
import kotlin.random.Random

class WarehouseQuantityTestConversion {
    @Test
    fun `receipt snapshot preserves positive ratio rather than mutable catalogue interpretation`() {
        val snapshot = ReceiptConversion.parse("2000", "2")
        val newCatalogue = ReceiptConversion.of(2000L, 1L)
        assertThat(snapshot.toBase("2", StockUnit.MM)).isEqualTo(StockQuantity.metres("2"))
        assertThat(newCatalogue.toBase("2", StockUnit.MM)).isEqualTo(StockQuantity.metres("4"))
        assertThat(snapshot.numerator).isEqualTo(2000L)
        assertThat(snapshot.denominator).isEqualTo(2L)
        assertThat(snapshot).isEqualTo(ReceiptConversion.of(2000L, 2L)).hasSameHashCodeAs(ReceiptConversion.of(2000L, 2L))
        assertThat(snapshot).isNotEqualTo(ReceiptConversion.of(1000L, 1L))
        assertThat(ReceiptConversion::class.java.declaredConstructors.filterNot { it.isSynthetic }).allMatch { Modifier.isPrivate(it.modifiers) }
        assertThat(ReceiptConversion::class.java.methods.map { it.name }).noneMatch { it.startsWith("set") || it == "copy" }
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = ["0", "-1", "+1", "1.5", "NaN", "Infinity", "1e3", " ", "\u0661", "9223372036854775808"])
    fun `conversion ratio components must be positive checked integers`(input: String) {
        assertThatThrownBy { ReceiptConversion.parse(input, "1") }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ReceiptConversion.parse("1", input) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `direct ratio construction rejects signed invalid values`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { input ->
            assertThatThrownBy { ReceiptConversion.of(input, 1L) }.isInstanceOf(ValidationException::class.java)
            assertThatThrownBy { ReceiptConversion.of(1L, input) }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `conversion requires exact integer base and package quantities`() {
        val ratio = ReceiptConversion.of(3L, 2L)
        assertThat(ratio.toBase("2", StockUnit.EA)).isEqualTo(StockQuantity.each("3"))
        assertThat(ratio.toBase("0", StockUnit.MM).quantityBase).isZero()
        assertThat(ratio.toPackages(StockQuantity.each("3"))).isEqualTo(2L)
        assertThat(ratio.toPackages(StockQuantity.each("0"))).isZero()
        assertThatThrownBy { ratio.toBase("1", StockUnit.EA) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ratio.toPackages(StockQuantity.each("1")) }.isInstanceOf(ValidationException::class.java)
        listOf("-1", "1.5", "+2", "9223372036854775808").forEach { count ->
            assertThatThrownBy { ratio.toBase(count, StockUnit.EA) }.isInstanceOf(ValidationException::class.java)
        }
    }

    @Test
    fun `conversion cancels exact factors before checked multiplication`() {
        val maximum = Long.MAX_VALUE
        val identityRatio = ReceiptConversion.of(maximum, maximum)
        assertThat(identityRatio.toBase(maximum.toString(), StockUnit.MM).quantityBase).isEqualTo(maximum)
        assertThat(identityRatio.toPackages(StockQuantity.of(maximum, StockUnit.MM))).isEqualTo(maximum)
        assertThat(ReceiptConversion.of(2L, 2L).toBase(maximum.toString(), StockUnit.EA).quantityBase).isEqualTo(maximum)
        assertThat(ReceiptConversion.of(maximum, 2L).toBase("2", StockUnit.MM).quantityBase).isEqualTo(maximum)
        assertThatThrownBy { ReceiptConversion.of(maximum, 1L).toBase("2", StockUnit.MM) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { ReceiptConversion.of(1L, maximum).toPackages(StockQuantity.each("2")) }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `rational conversion agrees with independent arbitrary precision oracle`() {
        val random = Random(9327)
        repeat(1000) {
            val count = random.nextLong(0L, Long.MAX_VALUE)
            val numerator = random.nextLong(1L, 1000L)
            val denominator = random.nextLong(1L, 20L)
            val ratio = ReceiptConversion.of(numerator, denominator)
            val result = BigInteger.valueOf(count).multiply(BigInteger.valueOf(numerator))
                .divideAndRemainder(BigInteger.valueOf(denominator))
            if (result[1] == BigInteger.ZERO && result[0] <= BigInteger.valueOf(Long.MAX_VALUE)) {
                val actual = ratio.toBase(count.toString(), StockUnit.MM)
                assertThat(actual.quantityBase).isEqualTo(result[0].longValueExact())
                assertThat(ratio.toPackages(actual)).isEqualTo(count)
            } else {
                assertThatThrownBy { ratio.toBase(count.toString(), StockUnit.MM) }.isInstanceOf(ValidationException::class.java)
            }
        }
    }
}
