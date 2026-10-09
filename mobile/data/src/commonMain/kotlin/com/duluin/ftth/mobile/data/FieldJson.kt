package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.*
import com.duluin.ftth.mobile.data.MaterialJson.flag
import com.duluin.ftth.mobile.data.MaterialJson.id
import com.duluin.ftth.mobile.data.MaterialJson.number
import com.duluin.ftth.mobile.data.MaterialJson.obj
import com.duluin.ftth.mobile.data.MaterialJson.optional
import com.duluin.ftth.mobile.data.MaterialJson.parse
import com.duluin.ftth.mobile.data.MaterialJson.quantity
import com.duluin.ftth.mobile.data.MaterialJson.rows
import com.duluin.ftth.mobile.data.MaterialJson.text
import kotlinx.serialization.json.*

internal object FieldJson {
    fun work(row: JsonObject): FieldWork {
        val type = row.obj("type")
        val slots = type.getValue("photoSlots").jsonArray.map { it.jsonPrimitive.let { value -> require(value.isString && value.content.isNotBlank()); value.content } }
        require(slots.size <= 12 && slots.toSet().size == slots.size)
        return FieldWork(row.id("id"), row.text("code"), row.text("title"), plain(row, "description"), row.number("revision"), row.id("technicianId"),
            row.number("assignmentGeneration"), FieldWorkState.valueOf(row.text("state")), FieldWorkType(type.text("name"), type.flag("materialRequired"), slots), row.optional("scheduledAt"))
    }
    fun detail(value: String): FieldWorkDetail {
        val row = parse(value)
        return FieldWorkDetail(work(row.obj("workOrder")), row.flag("overdue"), row.rows("timeline").map { FieldActivity(it.text("action"), it.text("actorName"), plain(it, "notes"), it.text("recordedAt")) })
    }
    fun photo(row: JsonObject) = FieldPhoto(row.id("id"), row.text("slot"), row.number("assignmentGeneration"), row.flag("current"))
    fun stock(row: JsonObject): FieldStock {
        val sku = MaterialSku(row.id("skuId"), row.text("skuCode"), row.text("skuName"), MaterialTracking.valueOf(row.text("tracking")), MaterialUnit.valueOf(row.text("baseUnit")))
        val quantity = row.quantity("quantityBase"); val serial = row.optional("serial")
        require(sku.tracking != MaterialTracking.SERIAL || sku.baseUnit == MaterialUnit.EA && quantity.value == 1L && !serial.isNullOrBlank())
        return FieldStock(row.id("stockIdentityId"), sku, quantity, row.number("revision"), row.text("locationName"), row.id("holderId"), row.text("status"), serial, row.optional("mac"))
    }
    fun warehouse(row: JsonObject) = FieldWarehouse(row.id("id"), row.optional("name") ?: row.text("code"), row.text("kind"), row.flag("active"))
    fun request(row: JsonObject) = FieldRequest(row.id("id"), row.text("reason"), row.text("state"), row.text("requesterName"), row.rows("lines").map { line ->
        val requested = line.quantity("requestedBase"); val approved = line.quantity("approvedBase"); val received = line.quantity("receivedBase"); val fulfilled = line.quantity("fulfilledBase")
        require(requested.value > 0 && approved.value <= requested.value && received.value <= approved.value && fulfilled.value <= approved.value)
        FieldRequestLine(line.text("name"), MaterialUnit.valueOf(line.text("baseUnit")), requested, approved, received, fulfilled)
    })
    fun returned(row: JsonObject) = FieldReturn(row.id("id"), row.text("reason"), row.text("state"), row.text("skuName"), MaterialUnit.valueOf(row.text("baseUnit")), row.quantity("quantityBase"), row.text("warehouseName"))
    private fun plain(row: JsonObject, key: String) = row.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
}
