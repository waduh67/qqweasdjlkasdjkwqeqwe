package com.duluin.ftth.inventory.adapter.outbound.persistence

import com.duluin.ftth.inventory.WarehouseErrorCode
import com.duluin.ftth.inventory.application.port.outbound.ReservationState
import com.duluin.ftth.inventory.application.port.outbound.WarehousePost
import java.util.UUID

internal class ReservationPartitions(private val sql: PostingSql) {
    fun validate(command: WarehousePost): Map<UUID, UUID> = buildMap {
        command.reservations.filter { it.partitionFrom != null }.groupBy { requireNotNull(it.partitionFrom) }
            .toSortedMap(compareBy(UUID::toString)).forEach { (source, children) ->
                val original = sql.query("SELECT * FROM inventory_reservation WHERE tenant_id=? AND id=? AND state='OPEN' FOR UPDATE", sql.tenant, source) {
                    Triple(it.uuid("stock_identity_id"), it.getLong("reserved_unpicked_base"), it.getLong("reserved_picked_base"))
                }.singleOrNull() ?: sql.fail(WarehouseErrorCode.STALE_REVISION)
                val split = command.splits.singleOrNull { it.parentId == original.first } ?: sql.fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                require(children.count { it.id == source && it.expectedRevision != null } == 1)
                require(children.all { it.state == ReservationState.OPEN && it.dimension.stockIdentityId in split.children.map { child -> child.id } })
                require(children.filter { it.id != source }.all { it.expectedRevision == null })
                val anchor = children.single { it.id == source }
                require(children.all { it.documentLineId == anchor.documentLineId && it.unpicked.unit == anchor.unpicked.unit &&
                    it.dimension.copy(stockIdentityId = anchor.dimension.stockIdentityId) == anchor.dimension })
                val total = children.fold(0L) { sum, child -> Math.addExact(sum, (child.unpicked + child.picked).quantityBase) }
                require(total == Math.addExact(original.second, original.third)) { "Reservation partition must conserve one encumbrance" }
                require(original.third == 0L) { "Picked reservations cannot be recut" }
                put(source, original.first)
            }
    }
}
