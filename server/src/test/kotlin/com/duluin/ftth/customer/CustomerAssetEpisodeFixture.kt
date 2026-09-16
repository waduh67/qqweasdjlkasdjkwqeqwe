package com.duluin.ftth.customer

import com.duluin.ftth.fulfillment.MaterialReceiptFixture
import com.duluin.ftth.inventory.WarehousePostingFixture
import java.util.UUID

abstract class CustomerAssetEpisodeFixture : MaterialReceiptFixture() {
    internal data class EpisodeCase(
        val stock: WarehousePostingFixture,
        val asset: UUID,
        val issueLine: UUID,
        val workOrder: UUID,
        val actor: UUID,
        val customerA: UUID,
        val customerB: UUID,
        val serial: String,
        val token: String,
    )

    internal fun episodeCase(serials: List<String> = listOf("EPISODE-${UUID.randomUUID()}".uppercase(), "SPARE-${UUID.randomUUID()}".uppercase())): EpisodeCase {
        val received = receiptCase(serial = true, serials = serials)
        received(received)
        val stock = fixture(received.stock.token)
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        stock.transaction {
            for (customer in listOf(first, second)) {
                sql("INSERT INTO customer(id,tenant_id,code,name,address) VALUES ('$customer','$tenant','${customer.toString().take(8)}','Episode','Test')")
            }
        }
        val selected = received.input.lines.single()
        return EpisodeCase(stock, selected.stockIdentityId, selected.issueLineId, UUID.fromString(received.workOrder),
            UUID.fromString(received.receiver.second), first, second, requireNotNull(selected.serial), received.stock.token)
    }

    internal fun EpisodeCase.assignment(id: UUID, customer: UUID = customerA, start: String = "2026-01-01T00:00:00Z"): String = """
        INSERT INTO inventory_asset_assignment(id,tenant_id,asset_id,customer_id,work_order_id,issue_line_id,
            purpose,ownership_mode,legal_owner,provenance,actor_id,started_at)
        VALUES ('$id','${stock.tenant}','$asset','$customer','$workOrder','$issueLine',
            'INSTALL','LOAN','ISP','RECEIPT','$actor','$start')
    """.trimIndent()

    internal fun EpisodeCase.episode(assignment: UUID, customer: UUID = customerA, start: String = "2026-01-01T00:00:00Z"): String = """
        INSERT INTO onu(id,tenant_id,customer_id,original_customer_id,serial_number,canonical_serial,asset_id,
            assignment_id,started_at,provenance,warehouse_admission)
        VALUES ('${UUID.randomUUID()}','${stock.tenant}','$customer','$customer','$serial','$serial','$asset',
            '$assignment','$start','RECEIPT','VERIFIED')
    """.trimIndent()
}
