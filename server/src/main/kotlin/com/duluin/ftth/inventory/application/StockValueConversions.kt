package com.duluin.ftth.inventory.application

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.inventory.ReceiptConversionSnapshot
import com.duluin.ftth.inventory.WarehouseBaseUnit
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseDisplayUnit
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.WarehouseQuantity
import com.duluin.ftth.inventory.domain.model.ReceiptConversion
import com.duluin.ftth.inventory.domain.model.StockQuantity
import com.duluin.ftth.inventory.domain.model.StockUnit

fun WarehouseQuantity.toStockQuantity(): StockQuantity = parseStockBoundary {
    val quantity = StockQuantity.parseBase(quantityBase, baseUnit.toStockUnit())
    val display = when (baseUnit) {
        WarehouseBaseUnit.EA -> {
            if (displayUnit != WarehouseDisplayUnit.EA) throw ValidationException("Satuan tampilan EA tidak sesuai")
            StockQuantity.each(displayQuantity)
        }
        WarehouseBaseUnit.MM -> {
            if (displayUnit != WarehouseDisplayUnit.M) throw ValidationException("Satuan tampilan MM harus metre")
            StockQuantity.metres(displayQuantity)
        }
    }
    if (quantity != display) throw ValidationException("Kuantitas base dan tampilan tidak sesuai")
    quantity
}

fun StockQuantity.toWarehouseQuantity(): WarehouseQuantity = when (unit) {
    StockUnit.EA -> WarehouseQuantity(quantityBase.toString(), WarehouseBaseUnit.EA, quantityBase.toString(), WarehouseDisplayUnit.EA)
    StockUnit.MM -> WarehouseQuantity(quantityBase.toString(), WarehouseBaseUnit.MM, toMetres(), WarehouseDisplayUnit.M)
}

fun ReceiptConversionSnapshot.toReceiptConversion(): ReceiptConversion = parseStockBoundary {
    ReceiptConversion.parse(numerator, denominator)
}

fun ReceiptConversion.toReceiptConversionSnapshot(): ReceiptConversionSnapshot =
    ReceiptConversionSnapshot(numerator.toString(), denominator.toString())

fun WarehouseBaseUnit.toStockUnit(): StockUnit = when (this) {
    WarehouseBaseUnit.EA -> StockUnit.EA
    WarehouseBaseUnit.MM -> StockUnit.MM
}

private inline fun <T> parseStockBoundary(parse: () -> T): T = try {
    parse()
} catch (error: ValidationException) {
    throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, error.message.orEmpty()))
}
