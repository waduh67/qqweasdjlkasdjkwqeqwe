package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.storage.ObjectStorage
import com.duluin.ftth.iam.CurrentAuthorityApi
import com.duluin.ftth.iam.IamApi
import com.duluin.ftth.inventory.*
import com.duluin.ftth.workorder.application.port.inbound.WorkOrderEvidenceQuery
import jakarta.persistence.EntityManager
import org.hibernate.Session
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class AssetHandoverWorkOrderAdapter(private val authorities: CurrentAuthorityApi, private val users: IamApi,
    private val evidence: WorkOrderEvidenceQuery, private val entityManager: EntityManager, private val storage: ObjectStorage) : AssetHandoverWorkOrderPort {
    override fun lock(binding: DeploymentBinding, authority: AuthorityFence): AssetHandoverWorkOrder {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (binding.tenantId != TenantContext.tenantId() || binding.actorId != current.fence.identity.userId ||
            authority.identity != current.fence.identity || authority.epoch != current.fence.epoch) fail(WarehouseErrorCode.FORBIDDEN)
        if (!current.platformAdmin && !current.permissions.containsAll(setOf("workorder.order.field", "customer.onu.assign")))
            fail(WarehouseErrorCode.FORBIDDEN)
        val sender = users.usersByIds(setOf(binding.actorId)).singleOrNull { it.active && it.technician }
            ?: fail(WarehouseErrorCode.FORBIDDEN)
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT code,warehouse_revision,area_id FROM work_order
                WHERE tenant_id=? AND id=? AND customer_id=? AND type=? AND status IN ('ASSIGNED','IN_PROGRESS','DONE')
                AND EXISTS(SELECT FROM work_order_assignee WHERE tenant_id=work_order.tenant_id
                    AND work_order_id=work_order.id AND technician_id=?) FOR UPDATE""").use { query ->
                query.setObject(1, binding.tenantId); query.setObject(2, binding.workOrderId)
                query.setObject(3, binding.customerId)
                query.setString(4, when (binding.purpose) {
                    DeploymentPurpose.INSTALL -> "PSB"
                    DeploymentPurpose.REPLACE -> "MIGRATION"
                    DeploymentPurpose.RETURN_CUSTOMER_RMA -> "REPAIR"
                    DeploymentPurpose.REMOVE -> fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                })
                query.setObject(5, binding.actorId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids)
                        fail(WarehouseErrorCode.FORBIDDEN)
                    AssetHandoverWorkOrder(row.getString("code"), row.getLong("warehouse_revision"), sender.name)
                }
            }
        }
    }

    override fun signature(workOrderId: UUID, evidenceId: UUID): AssetHandoverSignature {
        val snapshot = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT signature.storage_key,signature.sha256,signature.signer_name,signature.receipt_at
                FROM wo_signature signature JOIN evidence_object_registry registry
                ON registry.tenant_id=signature.tenant_id AND registry.revision_id=signature.id
                WHERE signature.tenant_id=? AND signature.work_order_id=? AND signature.id=?
                AND signature.revision_state='COMMITTED' AND signature.purge_state='ACTIVE'
                AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE'
                AND registry.expected_sha256=signature.sha256 AND registry.object_key=signature.storage_key
                FOR SHARE OF signature,registry""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, workOrderId); query.setObject(3, evidenceId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    AssetHandoverSignature(evidenceId, row.getString("storage_key"), row.getString("sha256"),
                        row.getString("signer_name"), row.getTimestamp("receipt_at").toInstant())
                }
            }
        }
        val content = evidence.downloadSignature(workOrderId)
        val digest = MessageDigest.getInstance("SHA-256").digest(content.bytes).joinToString("") { "%02x".format(it) }
        if (digest != snapshot.digest) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return snapshot
    }

    private fun fail(code: WarehouseErrorCode): Nothing = throw WarehouseContractException(WarehouseError(code, code.name))

    override fun signatureForApproval(workOrderId: UUID, evidenceId: UUID, expectedDigest: String, authority: AuthorityFence): AssetHandoverSignatureContent {
        // Narrow approval capability: current WO area plus the exact sealed signature, not general WO evidence access.
        lockTitle(workOrderId, authority)
        val current = authorities.lockCurrent()
        if (!current.platformAdmin && "inventory.approval.view" !in current.permissions) fail(WarehouseErrorCode.FORBIDDEN)
        val metadata = entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("""SELECT signature.storage_key,signature.content_type,signature.size_bytes
                FROM wo_signature signature JOIN evidence_object_registry registry
                ON registry.tenant_id=signature.tenant_id AND registry.revision_id=signature.id
                WHERE signature.tenant_id=? AND signature.work_order_id=? AND signature.id=? AND signature.sha256=?
                AND signature.revision_state='COMMITTED' AND signature.purge_state='ACTIVE'
                AND registry.state='COMMITTED' AND registry.purge_state='ACTIVE'
                AND registry.expected_sha256=signature.sha256 AND registry.object_key=signature.storage_key
                AND registry.expected_content_type=signature.content_type AND registry.expected_size_bytes=signature.size_bytes
                FOR SHARE OF signature,registry""").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, workOrderId); query.setObject(3, evidenceId); query.setString(4, expectedDigest)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
                    Triple(row.getString("storage_key"), row.getString("content_type"), row.getLong("size_bytes"))
                }
            }
        }
        if (!metadata.first.startsWith("${TenantContext.tenantId()}/") || metadata.third !in 1..15728640L)
            fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        val content = try { storage.get(metadata.first) } catch (_: NotFoundException) { fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED) }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.bytes).joinToString("") { "%02x".format(it) }
        if (content.size != metadata.third || content.contentType != metadata.second || digest != expectedDigest || !signatureImage(content.contentType, content.bytes))
            fail(WarehouseErrorCode.SOURCE_NOT_VERIFIED)
        return AssetHandoverSignatureContent(content.contentType, content.bytes)
    }

    override fun lockTitle(workOrderId: UUID, authority: AuthorityFence): Long {
        authority.assertHeld()
        val current = authorities.lockCurrent()
        if (current.fence.identity != authority.identity || current.fence.epoch != authority.epoch) fail(WarehouseErrorCode.STALE_AUTHORITY)
        return entityManager.unwrap(Session::class.java).doReturningWork { connection ->
            connection.prepareStatement("SELECT warehouse_revision,area_id FROM work_order WHERE tenant_id=? AND id=? FOR UPDATE").use { query ->
                query.setObject(1, TenantContext.tenantId()); query.setObject(2, workOrderId)
                query.executeQuery().use { row ->
                    if (!row.next()) fail(WarehouseErrorCode.NOT_FOUND)
                    val scope = current.areaScope
                    if (scope is AuthorityScope.Restricted && row.getObject("area_id", UUID::class.java) !in scope.ids) fail(WarehouseErrorCode.NOT_FOUND)
                    row.getLong("warehouse_revision")
                }
            }
        }
    }
}

private fun signatureImage(type: String, bytes: ByteArray): Boolean {
    fun starts(prefix: ByteArray) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
    return when (type) {
        "image/png" -> starts(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        "image/jpeg" -> starts(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()))
        "image/gif" -> starts("GIF87a".toByteArray()) || starts("GIF89a".toByteArray())
        "image/webp" -> bytes.size > 12 && starts("RIFF".toByteArray()) && bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        else -> false
    }
}
