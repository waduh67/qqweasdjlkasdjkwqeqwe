package com.duluin.ftth.inventory

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class MigrationReviewCase(val caseId: UUID, val sourceTable: String, val sourceId: UUID, val sourceHash: String,
    val sourceSnapshot: JsonNode, val resolution: WarehouseMigrationResolution?, val resolutionRequired: Boolean)
data class MigrationReviewManifest(val batchId: UUID, val cutoverEpoch: Long, val watermark: String, val sourceHash: String,
    val cases: List<MigrationReviewCase>)
data class MigrationReviewIssue(val caseId: UUID, val code: String)
data class WarehouseMigrationReview(val manifest: MigrationReviewManifest, val reviewHash: String,
    val issues: List<MigrationReviewIssue>)
data class MigrationReviewLocation(val id: UUID, val revision: Long, val areaId: UUID?)
data class MigrationOpeningScope(val customers: List<ProvenanceCustomerReference>, val workOrders: List<ProvenanceWorkOrderReference>)

/** Frozen proposal. Physical stock and unknown historical cost are not fabricated by requesting approval. */
data class WarehouseMigrationOpening(val id: UUID, val code: String, val batchId: UUID, val reviewHash: String,
    val manifest: MigrationReviewManifest, val reviewLocation: MigrationReviewLocation, val sourceAccess: MigrationOpeningScope,
    val requestedBy: UUID, val authorityEpoch: Long, val cutoverEpoch: Long, val migrationReference: String,
    val reason: String, val createdAt: Instant)
