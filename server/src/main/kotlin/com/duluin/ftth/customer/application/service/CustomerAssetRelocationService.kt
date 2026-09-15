package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.customer.*
import com.duluin.ftth.customer.adapter.outbound.persistence.AssetRelocationWrite
import com.duluin.ftth.customer.adapter.outbound.persistence.CustomerAssetRelocationStore
import com.duluin.ftth.customer.application.port.outbound.OnuRepository
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.network.NetworkApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest

@Service
@Transactional(timeout = 30, rollbackFor = [Exception::class])
class CustomerAssetRelocationService(private val inventory: InventoryAssetReplacementApi, private val authorities: CurrentAuthorityApi,
    private val locks: CustomerFulfillmentLockApi, private val store: CustomerAssetRelocationStore,
    private val network: NetworkApi, private val onus: OnuRepository) : CustomerAssetRelocationApi {
    private val mapper = jacksonObjectMapper()
    override fun relocate(context: CustomerAssetRelocationContext, request: CustomerAssetRelocationRequest, metadata: WarehouseMutationMetadata): CustomerAssetRelocation {
        if (metadata.idempotencyKey.isBlank() || metadata.idempotencyKey.length>200 || request.expectedRevision<0 || request.expectedWorkOrderRevision<0 ||
            request.topology.portNumber<1 || request.topology.installRxPowerDbm?.let { !it.isFinite() || it !in -40.0..0.0 } == true)
            throw WarehouseContractException(WarehouseError(WarehouseErrorCode.MALFORMED_REQUEST, "MALFORMED_REQUEST"))
        inventory.lockTopology(AssetTopologyContext(context.customerId, context.assignmentId, request.workOrderId, request.expectedWorkOrderRevision))
        val current = authorities.lockCurrent()
        locks.lock(context.customerId, null)
        val hash = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(listOf(context, request))).joinToString("") { "%02x".format(it) }
        store.replay(metadata.idempotencyKey)?.let {
            if (it.actorId != current.fence.identity.userId) throw ConflictException("FORBIDDEN")
            if (it.hash != hash) throw ConflictException("IDEMPOTENCY_CONFLICT")
            return it.response
        }
        val occupied = onus.findByOdpId(request.topology.odpId).filter { it.id != context.assignmentId }.mapNotNullTo(HashSet()) { it.odpPortNumber }
        network.assertOdpPortAssignable(request.topology.odpId, request.topology.portNumber, occupied)
        return store.append(AssetRelocationWrite(context, request, current.fence.identity.userId, metadata.idempotencyKey, hash))
    }
}
