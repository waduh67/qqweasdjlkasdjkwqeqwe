package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.ProvisioningStepResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class HsgqProvisioningSnapshot(
    val beforeHash: String,
    val before: HsgqDeviceState,
    val intentDigest: String,
    val afterHash: String? = null,
)

data class HsgqPersistedResult(val commandDigest: String, val result: ProvisioningStepResult)

sealed interface HsgqResultLookup {
    data object Missing : HsgqResultLookup
    data object Conflict : HsgqResultLookup
    data class Hit(val result: ProvisioningStepResult) : HsgqResultLookup
}

class HsgqStatePersistenceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface HsgqProvisioningStateStore {
    fun <T> withExecutionLock(deviceId: String, block: () -> T): T
    fun result(deliveryKey: String, commandDigest: String): HsgqResultLookup
    fun saveResult(deliveryKey: String, commandDigest: String, result: ProvisioningStepResult)
    fun acceptFence(deviceId: String, epoch: Long): Boolean
    fun snapshot(stepKey: String): HsgqProvisioningSnapshot?
    fun saveSnapshotIfAbsent(stepKey: String, snapshot: HsgqProvisioningSnapshot): HsgqProvisioningSnapshot
    fun markApplied(stepKey: String, afterHash: String)
}

class InMemoryHsgqProvisioningStateStore : HsgqProvisioningStateStore {
    private val results = linkedMapOf<String, HsgqPersistedResult>()
    private val fences = linkedMapOf<String, Long>()
    private val snapshots = linkedMapOf<String, HsgqProvisioningSnapshot>()

    override fun <T> withExecutionLock(deviceId: String, block: () -> T): T =
        locks.computeIfAbsent(deviceId) { ReentrantLock() }.withLock(block)

    @Synchronized
    override fun result(deliveryKey: String, commandDigest: String): HsgqResultLookup = results[deliveryKey]?.let {
        if (it.commandDigest == commandDigest) HsgqResultLookup.Hit(it.result) else HsgqResultLookup.Conflict
    } ?: HsgqResultLookup.Missing

    @Synchronized
    override fun saveResult(deliveryKey: String, commandDigest: String, result: ProvisioningStepResult) {
        results.putIfAbsent(deliveryKey, HsgqPersistedResult(commandDigest, result))
    }

    @Synchronized
    override fun acceptFence(deviceId: String, epoch: Long): Boolean {
        val current = fences[deviceId]
        if (current != null && epoch < current) return false
        fences[deviceId] = epoch
        return true
    }

    @Synchronized
    override fun snapshot(stepKey: String): HsgqProvisioningSnapshot? = snapshots[stepKey]

    @Synchronized
    override fun saveSnapshotIfAbsent(
        stepKey: String,
        snapshot: HsgqProvisioningSnapshot,
    ): HsgqProvisioningSnapshot = snapshots.getOrPut(stepKey) { snapshot }

    @Synchronized
    override fun markApplied(stepKey: String, afterHash: String) {
        val snapshot = snapshots[stepKey] ?: throw HsgqStatePersistenceException("HSGQ_SNAPSHOT_MISSING")
        snapshots[stepKey] = snapshot.copy(afterHash = afterHash)
    }

    private companion object {
        val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
