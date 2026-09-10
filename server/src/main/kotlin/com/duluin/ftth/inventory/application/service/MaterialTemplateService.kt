package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialCommandStore
import com.duluin.ftth.inventory.adapter.outbound.persistence.MaterialTemplateStore
import com.duluin.ftth.inventory.application.port.inbound.masterFailure
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

@Service
class MaterialTemplateService(private val authority: CurrentAuthorityApi, private val cutovers: InventoryTenantCutoverApi,
    private val store: MaterialTemplateStore, private val validation: MaterialPlanValidation, private val commands: MaterialCommandStore) : InventoryMaterialTemplateApi {
    private val mapper = jacksonObjectMapper()
    @Transactional(timeout = 30)
    override fun current(workType: String, action: String): MaterialTemplateSnapshot? {
        receiptPermission(authority.lockCurrent(), "inventory.request.view")
        return store.current(workType, action)
    }
    @Transactional(timeout = 30, rollbackFor = [Exception::class])
    override fun publish(workType: String, action: String, request: MaterialTemplateRequest, metadata: WarehouseMutationMetadata): WarehouseOperationReceipt {
        if (workType to action !in setOf("PSB" to "INSTALL", "REPAIR" to "REPAIR", "REPAIR" to "NETWORK", "MIGRATION" to "REPLACE",
                "DISMANTLE" to "REMOVE", "PREVENTIVE" to "PREVENTIVE") || request.expectedRevision !in 0 until Long.MAX_VALUE || request.lines.any { it.substitution != null })
            masterFailure(WarehouseErrorCode.MALFORMED_REQUEST)
        receiptKey(metadata.idempotencyKey)
        val cutover = cutovers.lockForCommand(cutovers.read().epoch, WarehouseOperationClass.ORDINARY_STOCK)
        val current = authority.lockCurrent()
        receiptPermission(current, "inventory.request.manage")
        receiptPermission(current, "workorder.order.assign")
        val resource = UUID.nameUUIDFromBytes("$workType|$action".toByteArray())
        val canonical = WarehouseCanonicalPayload.parse(mapper.writeValueAsString(mapOf("workType" to workType, "action" to action, "request" to request)))
        commands.replay(resource, "TEMPLATE", metadata.idempotencyKey, canonical, current.fence, cutover)?.let { return it }
        store.lock(workType, action)
        val previous = store.current(workType, action)
        if ((previous?.revision ?: 0) != request.expectedRevision) masterFailure(WarehouseErrorCode.STALE_REVISION)
        val snapshot = MaterialTemplateSnapshot(UUID.randomUUID(), workType, action, request.expectedRevision + 1, validation.lines(request.lines, current, null))
        store.insert(snapshot, current.fence.identity.userId)
        return commands.record(resource, "TEMPLATE", metadata.idempotencyKey, canonical, current.fence, cutover, snapshot.id, snapshot.revision, mapper.writeValueAsString(snapshot))
    }
}
