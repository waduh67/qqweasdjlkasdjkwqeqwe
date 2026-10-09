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

    fun validateOwners(id: UUID) {
        try {
            entityManager.createNativeQuery("SELECT warehouse_assert_fulfillment_owners(:tenant,:id)")
                .setParameter("tenant",TenantContext.tenantId()).setParameter("id",id).singleResult
        } catch (failure: org.hibernate.exception.ConstraintViolationException) {
            throw com.duluin.ftth.common.domain.error.ConflictException("FULFILLMENT_OWNER_RECONCILIATION_REQUIRED")
        }
    }

    fun forWorkOrder(id: UUID): FrozenFulfillment? {
        val row = entityManager.createNativeQuery("SELECT namespace,operation_key FROM fulfillment_approval_snapshot WHERE tenant_id=:tenant AND work_order_id=:id ORDER BY work_order_revision DESC LIMIT 1")
            .setParameter("tenant", TenantContext.tenantId()).setParameter("id", id).resultList.singleOrNull() as? Array<*> ?: return null
        return find(row[0] as String, row[1] as String)
    }

    fun validateReference(id: UUID) {
        entityManager.createNativeQuery("SELECT warehouse_assert_reference_fulfillment_snapshot(:tenant,:id,true)")
            .setParameter("tenant", TenantContext.tenantId()).setParameter("id", id).singleResult
    }

    fun verifyReference(id: UUID) {
        validateReference(id)
        entityManager.createNativeQuery("""INSERT INTO fulfillment_reference_material_receipt(tenant_id,id,work_order_id,completion_hash,document_id,payload_hash)
            SELECT tenant_id,id,work_order_id,snapshot::jsonb#>>'{workOrder,proofHash}',
                (snapshot::jsonb#>>'{referenceCompletion,documentId}')::uuid,payload_hash
            FROM fulfillment_approval_snapshot WHERE tenant_id=:tenant AND id=:id
            ON CONFLICT(tenant_id,id) DO NOTHING""")
            .setParameter("tenant", TenantContext.tenantId()).setParameter("id", id).executeUpdate()
    }

    fun save(snapshot: FulfillmentApprovalSnapshot, key: String): FrozenFulfillment {
        val body = mapper.writeValueAsString(snapshot)
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val workOrder = snapshot.workOrder.material
        val reference = snapshot.referenceCompletion
        val material = snapshot.material
        val actor = if (reference != null) snapshot.workOrder.completedBy else requireNotNull(snapshot.workOrder.approvedBy)
        val request = FulfillmentRequest(snapshot.identity.tenantId, if (reference != null)
            "workorder.fulfillment.complete" else "workorder.fulfillment.approve", key, hash,
            if (reference != null) FulfillmentSource.REFERENCE_WORK_ORDER else FulfillmentSource.WORK_ORDER,
            workOrder.workOrderId, workOrder.subscriptionId, workOrder.workOrderId,
            workOrder.workType, reference == null, snapshot.effects, workOrder.orderId, actor)
        entityManager.createNativeQuery("""INSERT INTO fulfillment_approval_snapshot(id,tenant_id,namespace,operation_key,payload_hash,
            work_order_id,work_order_revision,approved_by,usage_id,use_revision,plan_id,material_mode,required_effects,snapshot,request_payload,source,fulfillment_actor_id)
            VALUES (:id,:tenant,:namespace,:key,:hash,:wo,:revision,CAST(:approver AS uuid),CAST(:usage AS uuid),:useRevision,CAST(:plan AS uuid),:mode,string_to_array(:effects,','),:snapshot,:request,:source,:actor)""")
            .setParameter("id", snapshot.id).setParameter("tenant", snapshot.identity.tenantId)
            .setParameter("namespace", request.namespace).setParameter("key", key).setParameter("hash", hash)
            .setParameter("wo", workOrder.workOrderId).setParameter("revision", workOrder.workOrderRevision)
            .setParameter("actor", actor).setParameter("approver", snapshot.workOrder.approvedBy).setParameter("usage", material?.usageId)
            .setParameter("source", request.source.name).setParameter("useRevision", material?.useRevision ?: requireNotNull(reference).revision)
            .setParameter("plan", material?.planId).setParameter("mode", material?.materialMode?.name ?:
                if (requireNotNull(reference).materials.isEmpty()) "NONE" else "MATERIAL_REQUIRED")
            .setParameter("effects", snapshot.effects.sortedBy { it.name }.joinToString(",") { it.name })
            .setParameter("snapshot", body).setParameter("request", request.encode()).executeUpdate()
        return FrozenFulfillment(snapshot, request)
    }
}
