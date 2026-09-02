package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.ProvisioningStepResult
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

data class PersistedRouterOsMutation(
    val mutationId: String,
    val order: Int = 0,
    val status: String = MUTATION_PLANNED,
    val kind: String,
    val endpoint: String,
    val id: String? = null,
    val owner: String,
    val locator: Map<String, String> = emptyMap(),
    val before: Map<String, String> = emptyMap(),
    val expectedAfter: Map<String, String> = emptyMap(),
    val after: Map<String, String> = emptyMap(),
) {
    companion object {
        const val MUTATION_PLANNED = "PLANNED"
        const val MUTATION_APPLIED = "APPLIED"
    }
}

data class PersistedRouterOsSnapshot(
    val beforeHash: String,
    val before: RouterOsNormalizedState,
    val mutations: List<PersistedRouterOsMutation> = emptyList(),
    val afterHash: String? = null,
)

interface RouterOsProvisioningStateStore {
    fun result(idempotencyKey: String): ProvisioningStepResult?
    fun saveResult(idempotencyKey: String, result: ProvisioningStepResult)
    fun acceptFence(deviceId: String, epoch: Long): Boolean
    fun snapshot(stepKey: String): PersistedRouterOsSnapshot?
    fun saveSnapshot(stepKey: String, snapshot: PersistedRouterOsSnapshot)
    fun planMutation(stepKey: String, mutation: PersistedRouterOsMutation): PersistedRouterOsMutation
    fun markMutationApplied(
        stepKey: String,
        mutationId: String,
        resourceId: String,
        after: Map<String, String>,
    ): PersistedRouterOsMutation
    fun markApplied(stepKey: String, afterHash: String)
}

class InMemoryRouterOsProvisioningStateStore : RouterOsProvisioningStateStore {
    private val results = linkedMapOf<String, ProvisioningStepResult>()
    private val fences = linkedMapOf<String, Long>()
    private val snapshots = linkedMapOf<String, PersistedRouterOsSnapshot>()

    @Synchronized
    override fun result(idempotencyKey: String) = results[idempotencyKey]

    @Synchronized
    override fun saveResult(idempotencyKey: String, result: ProvisioningStepResult) {
        results.putIfAbsent(idempotencyKey, result)
    }

    @Synchronized
    override fun acceptFence(deviceId: String, epoch: Long): Boolean {
        val current = fences[deviceId]
        if (current != null && epoch < current) return false
        fences[deviceId] = epoch
        return true
    }

    @Synchronized
    override fun snapshot(stepKey: String) = snapshots[stepKey]

    @Synchronized
    override fun saveSnapshot(stepKey: String, snapshot: PersistedRouterOsSnapshot) {
        snapshots[stepKey] = snapshot
    }

    @Synchronized
    override fun planMutation(stepKey: String, mutation: PersistedRouterOsMutation): PersistedRouterOsMutation {
        val snapshot = checkNotNull(snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        snapshot.mutations.firstOrNull { it.mutationId == mutation.mutationId }?.let { return it }
        val planned = mutation.copy(order = (snapshot.mutations.maxOfOrNull { it.order } ?: 0) + 1)
        snapshots[stepKey] = snapshot.copy(mutations = snapshot.mutations + planned)
        return planned
    }

    @Synchronized
    override fun markMutationApplied(
        stepKey: String,
        mutationId: String,
        resourceId: String,
        after: Map<String, String>,
    ): PersistedRouterOsMutation {
        val snapshot = checkNotNull(snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        val current = snapshot.mutations.single { it.mutationId == mutationId }
        val applied = current.copy(status = PersistedRouterOsMutation.MUTATION_APPLIED, id = resourceId, after = after)
        snapshots[stepKey] = snapshot.copy(
            mutations = snapshot.mutations.map { if (it.mutationId == mutationId) applied else it },
        )
        return applied
    }

    @Synchronized
    override fun markApplied(stepKey: String, afterHash: String) {
        val snapshot = checkNotNull(snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        snapshots[stepKey] = snapshot.copy(afterHash = afterHash)
    }
}

class FileRouterOsProvisioningStateStore(private val file: Path) : RouterOsProvisioningStateStore {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
    private var state: RouterOsProvisioningStateFile = load()

    @Synchronized
    override fun result(idempotencyKey: String) = state.results[idempotencyKey]

    @Synchronized
    override fun saveResult(idempotencyKey: String, result: ProvisioningStepResult) {
        if (idempotencyKey in state.results) return
        state = state.copy(results = state.results + (idempotencyKey to result))
        persist()
    }

    @Synchronized
    override fun acceptFence(deviceId: String, epoch: Long): Boolean {
        val current = state.highestFenceByDevice[deviceId]
        if (current != null && epoch < current) return false
        if (current != epoch) {
            state = state.copy(highestFenceByDevice = state.highestFenceByDevice + (deviceId to epoch))
            persist()
        }
        return true
    }

    @Synchronized
    override fun snapshot(stepKey: String) = state.snapshots[stepKey]

    @Synchronized
    override fun saveSnapshot(stepKey: String, snapshot: PersistedRouterOsSnapshot) {
        state = state.copy(snapshots = state.snapshots + (stepKey to snapshot))
        persist()
    }

    @Synchronized
    override fun planMutation(stepKey: String, mutation: PersistedRouterOsMutation): PersistedRouterOsMutation {
        val snapshot = checkNotNull(state.snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        snapshot.mutations.firstOrNull { it.mutationId == mutation.mutationId }?.let { return it }
        val planned = mutation.copy(order = (snapshot.mutations.maxOfOrNull { it.order } ?: 0) + 1)
        state = state.copy(snapshots = state.snapshots + (stepKey to snapshot.copy(mutations = snapshot.mutations + planned)))
        persist()
        return planned
    }

    @Synchronized
    override fun markMutationApplied(
        stepKey: String,
        mutationId: String,
        resourceId: String,
        after: Map<String, String>,
    ): PersistedRouterOsMutation {
        val snapshot = checkNotNull(state.snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        val current = snapshot.mutations.single { it.mutationId == mutationId }
        val applied = current.copy(status = PersistedRouterOsMutation.MUTATION_APPLIED, id = resourceId, after = after)
        state = state.copy(
            snapshots = state.snapshots + (
                stepKey to snapshot.copy(
                    mutations = snapshot.mutations.map { if (it.mutationId == mutationId) applied else it },
                )
                ),
        )
        persist()
        return applied
    }

    @Synchronized
    override fun markApplied(stepKey: String, afterHash: String) {
        val snapshot = checkNotNull(state.snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        state = state.copy(snapshots = state.snapshots + (stepKey to snapshot.copy(afterHash = afterHash)))
        persist()
    }

    private fun load(): RouterOsProvisioningStateFile {
        if (!Files.exists(file)) return RouterOsProvisioningStateFile()
        return mapper.readValue(Files.readString(file), RouterOsProvisioningStateFile::class.java)
    }

    private fun persist() {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        setPermissions(parent, DIRECTORY_PERMISSIONS)
        val temporary = Files.createTempFile(parent, ".${file.fileName}.", ".tmp")
        try {
            setPermissions(temporary, FILE_PERMISSIONS)
            val bytes = mapper.writeValueAsBytes(state)
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                var buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
            setPermissions(file, FILE_PERMISSIONS)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions)
        }
    }

    private data class RouterOsProvisioningStateFile(
        val highestFenceByDevice: Map<String, Long> = emptyMap(),
        val results: Map<String, ProvisioningStepResult> = emptyMap(),
        val snapshots: Map<String, PersistedRouterOsSnapshot> = emptyMap(),
    )

    private companion object {
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
