package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.common.domain.identity.MacIdentity
import com.duluin.ftth.common.domain.identity.SerialIdentity
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.application.port.inbound.*
import com.duluin.ftth.inventory.application.port.outbound.WarehouseMasterStore
import com.duluin.ftth.inventory.domain.model.*
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ReceiptDraftValidation(private val masters: WarehouseMasterStore) {
    fun prepare(input: ReceiptDraftInput): ReceiptIntake {
        receiptText(input.externalReference, 500)
        if (input.lines.isEmpty() || input.lines.size > 100) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val supplier = masters.get(MasterKind.SUPPLIER, input.supplierId, true) as SupplierSnapshot
        val source = masters.get(MasterKind.LOCATION, input.sourceLocationId, true) as LocationSnapshot
        val inspection = masters.get(MasterKind.LOCATION, input.inspectionLocationId, true) as LocationSnapshot
        if (listOf(supplier.state, source.state, inspection.state).any { it != WarehouseMasterState.ACTIVE }) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        if (source.code != "RECEIPT_SOURCE" || source.kind != LocationKind.TRANSIT || inspection.kind != LocationKind.QUARANTINE || inspection.issueEligible)
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        val serials = mutableSetOf<String>()
        val macs = mutableSetOf<String>()
        val lines = input.lines.flatMapIndexed { index, line ->
            val sku = masters.get(MasterKind.SKU, line.skuId, true) as SkuSnapshot
            if (sku.state != WarehouseMasterState.ACTIVE) masterFailure(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
            val quantity = positiveReceiptQuantity(line.quantityBase, sku.baseUnit)
            line.conversion?.let { conversion ->
                val ratio = ReceiptConversion.parse(conversion.numerator, conversion.denominator)
                positiveReceiptQuantity(conversion.packageQuantity, WarehouseBaseUnit.EA)
                if (ratio.toBase(conversion.packageQuantity, StockUnit.valueOf(sku.baseUnit.name)) != quantity) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
            }
            val cost = line.cost?.let {
                if (!it.currency.matches(Regex("[A-Z]{3}"))) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                ReceiptCostSnapshot(StockQuantity.parseBase(it.totalMinor, StockUnit.EA).quantityBase.toString(), it.currency, quantity.quantityBase.toString())
            }
            when (sku.tracking) {
                WarehouseTracking.SERIAL -> {
                    if (line.lotCode != null || line.serials.size.toLong() != quantity.quantityBase || line.serials.size > 500)
                        masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                    line.serials.map { serial ->
                        receiptText(serial.serial, 128)
                        if (!serials.add(SerialIdentity.parse(serial.serial).canonical)) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT)
                        serial.mac?.let { if (!macs.add(MacIdentity.parse(it).canonical)) masterFailure(WarehouseErrorCode.IDEMPOTENCY_CONFLICT) }
                        ReceiptIntakeLine(UUID.randomUUID(), index + 1, sku, "1", serial.serial, serial.mac, null, line.conversion, cost)
                    }
                }
                WarehouseTracking.LOT, WarehouseTracking.BULK -> {
                    if (line.serials.isNotEmpty()) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
                    receiptText(line.lotCode ?: masterFailure(WarehouseErrorCode.MALFORMED_REQUEST), 120)
                    listOf(ReceiptIntakeLine(UUID.randomUUID(), index + 1, sku, quantity.quantityBase.toString(), null, null, line.lotCode, line.conversion, cost))
                }
            }
        }
        if (lines.size > 500) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        return ReceiptIntake(supplier, input.externalReference, source, inspection, lines)
    }
}

internal fun receiptText(value: String, maximum: Int) {
    if (value.isBlank() || value.length > maximum || value.any { it.isISOControl() }) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
}
internal fun positiveReceiptQuantity(value: String, unit: WarehouseBaseUnit): StockQuantity =
    StockQuantity.parseBase(value, StockUnit.valueOf(unit.name)).also {
        if (it.quantityBase == 0L) masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
    }
