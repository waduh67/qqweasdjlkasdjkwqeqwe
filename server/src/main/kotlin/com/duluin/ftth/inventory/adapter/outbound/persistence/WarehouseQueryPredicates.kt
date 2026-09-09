package com.duluin.ftth.inventory.adapter.outbound.persistence

internal object WarehouseQueryPredicates {
    val positionDimensions = """(request.sku IS NULL OR position.sku_id=request.sku)
        AND (request.serial IS NULL OR warehouse_canonical_serial(position.serial_number)=request.serial)
        AND (request.location IS NULL OR position.location_id=request.location)
        AND (request.status IS NULL OR position.status=request.status)
        AND (request.condition IS NULL OR position.condition=request.condition)
        AND (request.owner IS NULL OR position.legal_owner=request.owner)"""

    val positionUpdated = dateRange("position.updated_at")
    val lotReceived = dateRange("lot.received_at")
    val assetCreated = dateRange("asset.created_at")
    val segmentCreated = dateRange("segment.created_at")
    val eventHistory = """(request.sku IS NULL OR event.sku_id=request.sku)
        AND (request.location IS NULL OR event.location_id=request.location)
        AND (request.status IS NULL OR event.status=request.status)
        AND (request.condition IS NULL OR event.condition=request.condition)
        AND (request.owner IS NULL OR event.legal_owner=request.owner)
        AND ${dateRange("event.created_at")}"""

    private fun dateRange(column: String) =
        "(request.since IS NULL OR $column>=request.since) AND (request.until IS NULL OR $column<request.until)"
}
