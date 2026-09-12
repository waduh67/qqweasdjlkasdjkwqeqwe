package com.duluin.ftth.fulfillment

import com.duluin.ftth.common.tenant.TenantContext
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import java.util.UUID

@Repository
class FulfillmentApprovalStore(private val entityManager: EntityManager) {
    private val mapper = jacksonObjectMapper()

    fun find(namespace: String, key: String): FrozenFulfillment? = entityManager.createNativeQuery(
        "SELECT snapshot,request_payload FROM fulfillment_approval_snapshot WHERE tenant_id=:tenant AND namespace=:namespace AND operation_key=:key",
    ).setParameter("tenant", TenantContext.tenantId()).setParameter("namespace", namespace).setParameter("key", key)
        .resultList.singleOrNull()?.let { row ->
            val values = row as Array<*>
            FrozenFulfillment(mapper.readValue(values[0] as String, FulfillmentApprovalSnapshot::class.java), (values[1] as String).decodeFulfillmentRequest())
        }

    fun lock(id: UUID) {
        entityManager.createNativeQuery("SELECT id FROM fulfillment_approval_snapshot WHERE tenant_id=:tenant AND id=:id FOR UPDATE")
            .setParameter("tenant", TenantContext.tenantId()).setParameter("id", id).singleResult
    }

    fun forWorkOrder(id: UUID): FrozenFulfillment? {
        val key = entityManager.createNativeQuery("SELECT operation_key FROM fulfillment_approval_snapshot WHERE tenant_id=:tenant AND work_order_id=:id ORDER BY work_order_revision DESC LIMIT 1")
            .setParameter("tenant", TenantContext.tenantId()).setParameter("id", id).resultList.singleOrNull() as? String ?: return null
        return find("workorder.fulfillment.approve", key)
    }

    fun save(snapshot: FulfillmentApprovalSnapshot, key: String): FrozenFulfillment {
        val body = mapper.writeValueAsString(snapshot)
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val workOrder = snapshot.workOrder.material
        val request = FulfillmentRequest(snapshot.identity.tenantId, "workorder.fulfillment.approve", key, hash,
            FulfillmentSource.WORK_ORDER, workOrder.workOrderId, workOrder.subscriptionId, workOrder.workOrderId,
            workOrder.workType, true, snapshot.effects, workOrder.orderId, snapshot.workOrder.approvedBy)
        entityManager.createNativeQuery("""INSERT INTO fulfillment_approval_snapshot(id,tenant_id,namespace,operation_key,payload_hash,
            work_order_id,work_order_revision,approved_by,usage_id,use_revision,plan_id,material_mode,required_effects,snapshot,request_payload)
            VALUES (:id,:tenant,:namespace,:key,:hash,:wo,:revision,:actor,:usage,:useRevision,:plan,:mode,string_to_array(:effects,','),:snapshot,:request)""")
            .setParameter("id", snapshot.id).setParameter("tenant", snapshot.identity.tenantId)
            .setParameter("namespace", request.namespace).setParameter("key", key).setParameter("hash", hash)
            .setParameter("wo", workOrder.workOrderId).setParameter("revision", workOrder.workOrderRevision)
            .setParameter("actor", snapshot.workOrder.approvedBy).setParameter("usage", snapshot.material.usageId)
            .setParameter("useRevision", snapshot.material.useRevision).setParameter("plan", snapshot.material.planId)
            .setParameter("mode", snapshot.material.materialMode.name).setParameter("effects", snapshot.effects.sortedBy { it.name }.joinToString(",") { it.name })
            .setParameter("snapshot", body).setParameter("request", request.encode()).executeUpdate()
        return FrozenFulfillment(snapshot, request)
    }
}
