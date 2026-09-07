package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.application.port.outbound.WarehousePost
import com.duluin.ftth.inventory.WarehouseContractException
import com.duluin.ftth.inventory.WarehouseError
import com.duluin.ftth.inventory.WarehouseErrorCode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

class WarehousePreparedCommand private constructor(
    internal val posting: WarehousePost,
    val key: String,
    val cutoverEpoch: Long,
    val authorityEpoch: Long?,
    val canonical: WarehouseCanonicalPayload,
) {
    val namespace = "warehouse.post.${posting.kind.name.lowercase()}"
    val documentId: UUID = posting.documentId
    val revision: Long = Math.addExact(posting.expectedRevision, 1)
    val locations: Set<UUID> = (posting.legs.map { it.dimension.locationId } + posting.reservations.map { it.dimension.locationId }).toSet()
    val resourceScope: String = locations.sortedBy(UUID::toString).joinToString(",", "locations:")

    companion object {
        fun prepare(posting: WarehousePost, key: String, cutoverEpoch: Long,
                    referencedRevisions: Map<String, Long>, authorityEpoch: Long? = null): WarehousePreparedCommand {
            if (key.length !in 1..240 || key.any { it.code !in 33..126 } || posting.expectedRevision !in 0 until Long.MAX_VALUE ||
                cutoverEpoch < 0 || authorityEpoch?.let { it < 0 } == true || referencedRevisions.any { it.key.isBlank() || it.value < 0 }) {
                throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "Invalid warehouse command identity"))
            }
            val frozen = posting.copy(legs = posting.legs.toList(), reservations = posting.reservations.toList(),
                splits = posting.splits.map { it.copy(children = it.children.toList()) }, facts = posting.facts.toList(), events = posting.events.toList())
            val mapper = jacksonObjectMapper()
            val tree = mapper.valueToTree<tools.jackson.databind.node.ObjectNode>(frozen)
            tree.remove("operation")
            val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf(
                "posting" to tree, "referencedRevisions" to referencedRevisions.toSortedMap())))
            return WarehousePreparedCommand(frozen, key, cutoverEpoch, authorityEpoch, canonical)
        }
    }
}
