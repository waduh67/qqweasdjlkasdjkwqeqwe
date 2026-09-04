package com.duluin.ftth.mobile.domain

interface WorkOrderPort { suspend fun list(): Result<List<WorkOrder>>; suspend fun detail(id: String): Result<WorkOrder> }
interface AuthPort { suspend fun refresh(): Result<Unit> }
interface LocationPort { suspend fun permission(): PermissionState; suspend fun current(): Result<Pair<Double, Double>> }
interface EvidencePort { suspend fun enqueue(operation: OutboxOperation, bytes: ByteArray): EnqueueResult }
interface SecureOutboxPort : Outbox

class ObserveWorkOrders(private val port: WorkOrderPort) {
    suspend operator fun invoke(): WorkOrderState = port.list().fold(
        onSuccess = { if (it.isEmpty()) WorkOrderState.Ready else WorkOrderState.Ready },
        onFailure = { WorkOrderState.Error(it.message ?: "Unable to load work orders") },
    )
}
