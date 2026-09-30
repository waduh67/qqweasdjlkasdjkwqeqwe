package com.duluin.ftth.inventory.adapter.outbound.persistence

internal object WarehouseQueryPredicates {
    val positionDimensions = """(request.sku IS NULL OR position.sku_id=request.sku)
        AND (request.serial IS NULL OR warehouse_canonical_serial(position.serial_number)=request.serial)
        AND (request.location IS NULL OR position.location_id=request.location)
        AND (request.status IS NULL OR position.status=request.status)
        AND (request.condition IS NULL OR position.condition=request.condition)
        AND (request.owner IS NULL OR position.legal_owner=request.owner)
        AND (request.bucket IS NULL
            OR (request.bucket='AVAILABLE' AND position.available>0)
            OR (request.bucket='RESERVED' AND position.unpicked+position.picked>0)
            OR (request.bucket='PICKED' AND position.picked>0)
            OR (request.bucket='TECHNICIAN' AND position.custody_owner_kind='TECHNICIAN' AND position.status NOT IN ('CONSUMED','LOST','DISPOSED'))
            OR (request.bucket='TRANSIT' AND position.status='IN_TRANSIT')
            OR (request.bucket='INSTALLED' AND position.status='CUSTOMER_INSTALLED')
            OR (request.bucket='QUARANTINE' AND (position.condition='QUARANTINE' OR position.status='QUARANTINE')))"""

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
