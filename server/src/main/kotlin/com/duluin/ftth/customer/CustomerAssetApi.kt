package com.duluin.ftth.customer

import com.duluin.ftth.inventory.AssetLegalOwner
import com.duluin.ftth.inventory.AssetOwnershipMode
import com.duluin.ftth.inventory.AssetProvenance
import com.duluin.ftth.inventory.WarehouseMutationMetadata
import com.duluin.ftth.inventory.WarehousePage
import com.duluin.ftth.inventory.WarehousePageRequest
import java.time.Instant
import java.util.UUID

interface CustomerAssetApi {
    fun install(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode
    fun replace(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode
    fun remove(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode
    fun history(customerId: UUID, page: WarehousePageRequest): WarehousePage<CustomerAssetEpisode>
    fun attributeObservation(query: CustomerAssetObservationQuery): CustomerAssetObservationAttribution
}

data class InstallCustomerAssetRequest(
    val authorizationId: UUID,
    val expectedRevision: Long,
    val topology: CustomerAssetTopology?,
)

data class CustomerAssetTopology(val odpId: UUID, val portNumber: Int, val installRxPowerDbm: Double?)

data class CustomerAssetEpisode(
    val episodeId: UUID,
    val onuId: UUID?,
    val customerId: UUID,
    val assetId: UUID,
    val assignmentId: UUID,
    val assignmentRevision: Long,
    val episodeRevision: Long,
    val startedAt: Instant,
    val retiredAt: Instant?,
    val provenance: AssetProvenance,
    val ownershipMode: AssetOwnershipMode,
    val legalOwner: AssetLegalOwner,
)

data class PortalCustomerAsset(
    val deviceLabel: String,
    val serialNumber: String?,
    val ownershipMode: AssetOwnershipMode,
    val legalOwner: AssetLegalOwner,
    val provenance: AssetProvenance,
    val installedAt: Instant,
    val removedAt: Instant?,
)

data class CustomerAssetObservationQuery(
    val canonicalSerial: String,
    val observedAt: Instant,
    val deviceId: UUID?,
    val pathId: UUID?,
    val clock: ObservationClock,
)

enum class ObservationClock { SERVER, AUTHENTICATED_COLLECTOR }
enum class ObservationUnassignedReason { UNMATCHED, AMBIGUOUS, UNTRUSTED_TIME, TOO_OLD, FUTURE_TIME }

sealed interface CustomerAssetObservationAttribution {
    data class Attributed(
        val episodeId: UUID,
        val assignmentId: UUID,
        val episodeRevision: Long,
        val mayUpdateLiveState: Boolean,
    ) : CustomerAssetObservationAttribution
    data class Unassigned(val reason: ObservationUnassignedReason) : CustomerAssetObservationAttribution
}
