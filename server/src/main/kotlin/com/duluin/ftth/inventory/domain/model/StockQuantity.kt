package com.duluin.ftth.inventory.domain.model

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException

enum class StockUnit { EA, MM }

class StockQuantity private constructor(val quantityBase: Long, val unit: StockUnit) {
    operator fun plus(other: StockQuantity): StockQuantity {
        sameUnit(other)
        return of(checkedStockArithmetic { Math.addExact(quantityBase, other.quantityBase) }, unit)
    }

    operator fun minus(other: StockQuantity): StockQuantity {
        sameUnit(other)
        if (other.quantityBase > quantityBase) throw ConflictException("Kuantitas stok tidak mencukupi")
        return of(checkedStockArithmetic { Math.subtractExact(quantityBase, other.quantityBase) }, unit)
    }

    operator fun times(multiplier: Long): StockQuantity {
        if (multiplier < 0) throw ValidationException("Pengali kuantitas tidak boleh negatif")
        return of(checkedStockArithmetic { Math.multiplyExact(quantityBase, multiplier) }, unit)
    }

    fun toMetres(): String {
        if (unit != StockUnit.MM) throw ValidationException("Hanya kuantitas MM dapat ditampilkan dalam metre")
        return "${quantityBase / 1000}.${(quantityBase % 1000).toString().padStart(3, '0')}"
    }

    private fun sameUnit(other: StockQuantity) {
        if (unit != other.unit) throw ValidationException("Satuan kuantitas stok berbeda")
    }

    override fun equals(other: Any?): Boolean =
        other is StockQuantity && quantityBase == other.quantityBase && unit == other.unit

    override fun hashCode(): Int = 31 * quantityBase.hashCode() + unit.hashCode()
    override fun toString(): String = "$quantityBase ${unit.name}"

    companion object {
        private val METRES = Regex("[0-9]+(?:\\.[0-9]{1,3})?")

        fun of(quantityBase: Long, unit: StockUnit): StockQuantity {
            if (quantityBase < 0) throw ValidationException("Kuantitas fisik tidak boleh negatif")
            return StockQuantity(quantityBase, unit)
        }

        fun parseBase(quantityBase: String, unit: StockUnit): StockQuantity =
            of(parseStockInteger(quantityBase), unit)

        fun each(quantity: String): StockQuantity = parseBase(quantity, StockUnit.EA)

        fun metres(quantity: String): StockQuantity {
            if (!METRES.matches(quantity)) throw ValidationException("Metre harus desimal dengan maksimal tiga digit pecahan")
            val whole = parseStockInteger(quantity.substringBefore('.'))
            val fraction = quantity.substringAfter('.', "").padEnd(3, '0').toLong()
            val millimetres = checkedStockArithmetic {
                Math.addExact(Math.multiplyExact(whole, 1000L), fraction)
            }
            return of(millimetres, StockUnit.MM)
        }
    }
}

internal fun parseStockInteger(value: String): Long {
    if (value.isEmpty() || value.any { it !in '0'..'9' }) {
        throw ValidationException("Kuantitas harus integer nonnegatif tanpa tanda")
    }
    return value.toLongOrNull() ?: throw ValidationException("Kuantitas melampaui kapasitas Long")
}

internal inline fun checkedStockArithmetic(operation: () -> Long): Long = try {
    operation()
} catch (_: ArithmeticException) {
    throw ValidationException("Kuantitas melampaui kapasitas Long")
}
