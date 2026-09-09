package com.duluin.ftth.inventory

import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseQueryPredicates
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WarehouseQueryPredicateTest {
    @Test fun `dimension selection has no implicit temporal predicate`() {
        assertThat(WarehouseQueryPredicates.positionDimensions).contains("request.sku", "request.serial", "request.location", "request.status", "request.condition", "request.owner")
            .doesNotContain("request.since", "request.until", "updated_at", "created_at", "received_at")
    }

    @Test fun `each temporal predicate names its semantic timestamp explicitly`() {
        for ((predicate, column) in listOf(WarehouseQueryPredicates.positionUpdated to "position.updated_at",
            WarehouseQueryPredicates.lotReceived to "lot.received_at", WarehouseQueryPredicates.assetCreated to "asset.created_at",
            WarehouseQueryPredicates.segmentCreated to "segment.created_at")) {
            assertThat(predicate).isEqualTo("(request.since IS NULL OR $column>=request.since) AND (request.until IS NULL OR $column<request.until)")
        }
        assertThat(WarehouseQueryPredicates.eventHistory).contains("event.created_at>=request.since", "event.created_at<request.until")
            .doesNotContain("position.", "lot.", "asset.")
    }
}
