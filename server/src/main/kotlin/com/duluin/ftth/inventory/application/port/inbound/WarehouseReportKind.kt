package com.duluin.ftth.inventory.application.port.inbound

import com.duluin.ftth.inventory.WarehouseErrorCode

enum class WarehouseReportKind(val path: String, val history: Boolean = false) {
    STOCK("stock"), UNKNOWN_STOCK("unknown-stock"), STOCK_CARD("stock-card", true), CUSTODY_AGING("custody-aging"),
    TRANSIT_BACKLOG("transit-backlog"), LOAN_ASSETS("loan-assets"), SOLD_ASSETS("sold-assets"),
    WORK_ORDER_COSTS("work-order-costs", true), MOVEMENTS("movements", true);

    companion object {
        fun parse(path: String) = entries.singleOrNull { it.path == path }
            ?: masterFailure(WarehouseErrorCode.NOT_FOUND)
    }
}
