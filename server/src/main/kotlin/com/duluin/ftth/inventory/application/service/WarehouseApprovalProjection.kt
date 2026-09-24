package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.WarehouseApprovalQuery
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.UUID

/** Explicit allow-list projection. Raw sealed source contains cost/storage/internal metadata. */
@Component
class WarehouseApprovalProjection(private val query: WarehouseApprovalQuery, private val users: IamApi) {
    private val mapper = jacksonObjectMapper()
    fun people(ids: Set<UUID>): Map<UUID, String> = users.usersByIds(ids).associate { it.id to it.name }
    fun person(id: UUID, names: Map<UUID, String>) = WarehouseApprovalPerson(id, names[id])
    fun document(content: String, locations: Set<UUID>): WarehouseApprovalDocument {
        val source = mapper.readTree(content)
        val document = source.path("document")
        val id = document.requiredId("id")
        val kind = document.path("kind").asString()
        val state = document.path("state").asString()
        val comparisons = if (kind == "COUNT" && state in setOf("SUBMITTED", "APPROVED", "POSTED")) source.path("count").asSequence().toList() else emptyList()
        val requester = document.requiredId("actor_id")
        val names = people(comparisons.map { it.requiredId("counter_id") }.toSet() + requester)
        val intake = source.path("intake").optionalText("snapshot")?.let(mapper::readTree)
        val lines = source.path("lines").asSequence().map { line ->
            val skuId = line.requiredId("sku_id")
            val item = query.item(skuId)
            val intakeLine = intake?.path("lines")?.asSequence()?.firstOrNull { it.optionalId("id") == line.requiredId("id") }
            WarehouseApprovalLine(line.requiredId("id"), skuId, item.first, item.second, item.third,
                WarehouseBaseUnit.valueOf(line.path("base_unit").asString()), if (kind == "COUNT") null else line.path("quantity_base").asLong().toString(),
                intakeLine?.optionalText("serial") ?: query.serial(line.optionalId("stock_identity_id")),
                intakeLine?.optionalText("lotCode") ?: query.lot(line.optionalId("lot_id")), line.optionalId("location_id"), line.optionalId("destination_location_id"),
                WarehouseCondition.valueOf(line.path("condition").asString()), AssetLegalOwner.valueOf(line.path("legal_owner").asString()))
        }.toList()
        val measured = comparisons.map { fact -> WarehouseApprovalComparison(fact.requiredId("balance_id"), fact.requiredId("sku_id"),
            person(fact.requiredId("counter_id"), names), WarehouseBaseUnit.valueOf(fact.path("base_unit").asString()),
            fact.path("prior_quantity_base").asLong().toString(), fact.path("observed_quantity_base").asLong().toString(), fact.path("evidence_reference").asString()) }
        val returnId = listOf("returnTitle", "disposition", "compensation").firstNotNullOfOrNull { key -> source.path(key).path("returned").path("view").optionalId("id") }
            ?: source.path("replacement").path("view").optionalId("returnId")
        return WarehouseApprovalDocument(id, document.path("revision").asLong(), kind, document.path("code").asString(), state,
            document.optionalText("reason"), Instant.parse(document.path("created_at").asString()), person(requester, names), query.locations(locations), lines, measured,
            receiptId = id.takeIf { kind == "RECEIPT" }, countId = id.takeIf { kind == "COUNT" },
            transferId = document.optionalId("source_document_id").takeIf { kind == "ADJUSTMENT" }, returnId = returnId)
    }
    private fun JsonNode.optionalText(key: String): String? = path(key).takeUnless { it.isNull || it.isMissingNode }?.asString()
    private fun JsonNode.optionalId(key: String): UUID? = optionalText(key)?.let(UUID::fromString)
    private fun JsonNode.requiredId(key: String): UUID = requireNotNull(optionalId(key))
}
