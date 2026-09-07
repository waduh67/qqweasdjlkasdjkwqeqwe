package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.error.ValidationException

class ReceiptConversion private constructor(val numerator: Long, val denominator: Long) {
    fun toBase(packageCount: String, unit: StockUnit): StockQuantity =
        StockQuantity.of(convertExact(parseStockInteger(packageCount), numerator, denominator), unit)

    fun toPackages(quantity: StockQuantity): Long = convertExact(quantity.quantityBase, denominator, numerator)

    override fun equals(other: Any?): Boolean =
        other is ReceiptConversion && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    companion object {
        fun parse(numerator: String, denominator: String): ReceiptConversion =
            of(parseStockInteger(numerator), parseStockInteger(denominator))

        fun of(numerator: Long, denominator: Long): ReceiptConversion {
            if (numerator <= 0 || denominator <= 0) throw ValidationException("Rasio konversi harus positif")
            return ReceiptConversion(numerator, denominator)
        }

        private fun convertExact(quantity: Long, numerator: Long, denominator: Long): Long {
            val divisor = greatestCommonDivisor(quantity, denominator)
            val reducedDenominator = denominator / divisor
            if (numerator % reducedDenominator != 0L) throw ValidationException("Konversi paket tidak menghasilkan integer tepat")
            return checkedStockArithmetic {
                Math.multiplyExact(quantity / divisor, numerator / reducedDenominator)
            }
        }

        private fun greatestCommonDivisor(first: Long, second: Long): Long {
            var dividend = first
            var divisor = second
            while (divisor != 0L) {
                val remainder = dividend % divisor
                dividend = divisor
                divisor = remainder
            }
            return dividend
        }
    }
}
