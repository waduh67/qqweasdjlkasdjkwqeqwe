package com.duluin.ftth.inventory

import com.duluin.ftth.common.security.AuthorityFence
import java.time.Instant
import java.util.UUID

interface AssetHandoverWorkOrderPort {
    fun lock(binding: DeploymentBinding, authority: AuthorityFence): AssetHandoverWorkOrder
    fun signature(workOrderId: UUID, evidenceId: UUID): AssetHandoverSignature
    fun lockTitle(workOrderId: UUID, authority: AuthorityFence): Long
    /** Requester context, with the ordinary work-order evidence read permissions. */
    fun exceptionContext(workOrderId: UUID, customerId: UUID, authority: AuthorityFence): AssetExceptionWorkOrder
    /** Only for an authorized inventory approval and its captured signature/digest. */
    fun signatureForApproval(workOrderId: UUID, evidenceId: UUID, expectedDigest: String, authority: AuthorityFence): AssetHandoverSignatureContent
}

interface AssetHandoverCustomerPort {
    fun lock(customerId: UUID, assignmentId: UUID): AssetHandoverCustomer
    fun lockForException(customerId: UUID, assignmentId: UUID, authority: AuthorityFence): AssetHandoverCustomer
}

data class AssetHandoverWorkOrder(val code: String, val revision: Long, val senderLabel: String)
data class AssetHandoverCustomer(val label: String, val status: String)
data class AssetHandoverSignature(val id: UUID, val reference: String, val digest: String,
    val receiverLabel: String, val receivedAt: Instant)
class AssetHandoverSignatureContent(val contentType: String, val bytes: ByteArray)
data class AssetExceptionSignature(val id: UUID, val signerName: String, val recordedAt: Instant)
data class AssetExceptionWorkOrder(val id: UUID, val code: String, val revision: Long, val signature: AssetExceptionSignature?)
