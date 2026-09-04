package com.duluin.ftth.mobile.domain

interface WorkOrderPort { suspend fun list(): Result<List<WorkOrder>>; suspend fun detail(id: String): Result<WorkOrder> }
interface AuthPort { suspend fun refresh(): Result<Unit> }
interface LocationPort { suspend fun permission(): PermissionState; suspend fun current(): Result<Pair<Double, Double>> }
interface EvidencePort { suspend fun enqueue(operation: OutboxOperation, bytes: ByteArray): EnqueueResult }
data class SecureOutboxOperation(
    val userId: String,
    val deviceId: String,
    val sessionId: String,
    val namespace: String,
    val key: String,
    val payloadHash: String,
    val revision: Long,
    val payload: ByteArray,
)

data class OutboxIdentity(val userId: String, val deviceId: String, val sessionId: String)

interface SecureOutboxPort : Outbox {
    fun enqueueSecure(operation: SecureOutboxOperation): EnqueueResult
    fun retry(key: String): Boolean
    fun purge(userId: String)
}

class ObserveWorkOrders(private val port: WorkOrderPort) {
    suspend operator fun invoke(): WorkOrderState = port.list().fold(
        onSuccess = { if (it.isEmpty()) WorkOrderState.Ready else WorkOrderState.Ready },
        onFailure = { WorkOrderState.Error(it.message ?: "Unable to load work orders") },
    )
}
