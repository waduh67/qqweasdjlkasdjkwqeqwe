package com.karuhun.ftth.inventory.adapter.inbound.web

internal object WarehouseReceiptJson {
    fun <T> decode(body: String, type: Class<T>): T =
        com.karuhun.ftth.common.infrastructure.web.WarehouseReceiptJson.decode(body, type)
}
