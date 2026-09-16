package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.*
import com.duluin.ftth.customer.adapter.outbound.persistence.CustomerAssetInstallationStore
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class CustomerAssetService(private val deployment: InventoryDeploymentApi, private val customers: CustomerRepository,
    private val locks: CustomerFulfillmentLockApi, private val store: CustomerAssetInstallationStore,
    private val network: NetworkApi, private val onus: OnuRepository,
    private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val observations: CustomerObservationApi,
    private val events: org.springframework.context.ApplicationEventPublisher) : CustomerAssetApi {
    private val mapper = jacksonObjectMapper()
    override fun install(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode =
        installEpisode(customerId, request, metadata, false)

    private fun installEpisode(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata,
        observedDelivery: Boolean): CustomerAssetEpisode {
        request.topology?.let { topology ->
            if (topology.portNumber < 1 || topology.installRxPowerDbm?.let { !it.isFinite() || it !in -40.0..0.0 } == true)
                throw ConflictException("INVALID_INSTALLATION_TOPOLOGY")
        }
        val command = ConsumeDeploymentRequest(request.authorizationId, request.expectedRevision, customerId, mapper.writeValueAsString(request))
        val consumption = if (observedDelivery) deployment.consumeDiscovered(command, metadata) else deployment.consume(command, metadata)
        locks.lock(customerId, null)
        store.find(consumption.operationId)?.let { return it }
        if (!consumption.createsOnu && request.topology != null) throw ConflictException("EQUIPMENT_HAS_NO_ONU_TOPOLOGY")
        request.topology?.let { topology ->
            network.assertOdpPortAssignable(topology.odpId, topology.portNumber,
                onus.findByOdpId(topology.odpId).mapNotNullTo(HashSet()) { it.odpPortNumber })
        }
        return store.append(consumption, request.topology).also { episode ->
            episode.onuId?.let { onuId -> events.publishEvent(OnuRegistered(
                com.duluin.ftth.common.tenant.TenantContext.tenantId(), onuId, customerId, consumption.serialNumber)) }
        }
    }
    override fun history(customerId: UUID, page: WarehousePageRequest): WarehousePage<CustomerAssetEpisode> {
        cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.CONTROL_PLANE).assertHeld()
        val current = authority.lockCurrent()
        if (!current.platformAdmin && "customer.onu.view" !in current.permissions) throw ConflictException("FORBIDDEN")
        val customer = customers.findById(customerId) ?: throw NotFoundException("Customer not found")
        val scope = current.areaScope
        if (scope is AuthorityScope.Restricted && customer.areaId !in scope.ids) throw NotFoundException("Customer not found")
        val rows = store.history(customerId)
        val offset = page.page.toLong() * page.size
        return WarehousePage(if (offset >= rows.size) emptyList() else rows.drop(offset.toInt()).take(page.size), page.page, page.size, rows.size.toLong())
    }
    override fun installDiscoveredAsset(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata) =
        installEpisode(customerId, request, metadata, true)
    override fun replace(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode = closed()
    override fun remove(customerId: UUID, request: InstallCustomerAssetRequest, metadata: WarehouseMutationMetadata): CustomerAssetEpisode = closed()
    override fun attributeObservation(query: CustomerAssetObservationQuery): CustomerAssetObservationAttribution {
        val now = java.time.Instant.now()
        val time = if (query.clock == ObservationClock.SERVER) now else query.observedAt
        ObservationTimePolicy.rejection(time, now)?.let { return CustomerAssetObservationAttribution.Unassigned(it) }
        val result = observations.resolveObservation(query.canonicalSerial, time,
            if (query.deviceId == null && query.pathId == null) null else ObservationPath(query.deviceId, "", null, query.pathId))
        val episode = result.episode ?: return CustomerAssetObservationAttribution.Unassigned(
            if (result.reason == "AMBIGUOUS_EPISODE") ObservationUnassignedReason.AMBIGUOUS else ObservationUnassignedReason.UNMATCHED)
        return CustomerAssetObservationAttribution.Attributed(episode.onu.id, episode.assignmentId, episode.episodeRevision, episode.endedAt == null)
    }
    private fun closed(): Nothing = throw ConflictException("ASSET_LIFECYCLE_OPERATION_NOT_ENABLED")
}
