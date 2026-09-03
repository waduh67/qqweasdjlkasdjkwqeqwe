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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
        const val MUTATION_COMPENSATING = "COMPENSATING"
        const val MUTATION_COMPENSATED = "COMPENSATED"
    }
}

data class PersistedRouterOsSnapshot(
    val beforeHash: String,
    val before: RouterOsNormalizedState,
    val mutations: List<PersistedRouterOsMutation> = emptyList(),
    val afterHash: String? = null,
    val intentDigest: String? = null,
)

data class PersistedRouterOsResult(val commandDigest: String, val result: ProvisioningStepResult)

sealed interface RouterOsResultLookup {
    data object Missing : RouterOsResultLookup
    data class Hit(val result: ProvisioningStepResult) : RouterOsResultLookup
    data object Conflict : RouterOsResultLookup
}

class RouterOsStateCorruptionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface RouterOsProvisioningStateStore {
    fun <T> withExecutionLock(deviceId: String, block: () -> T): T
    fun result(deliveryKey: String, commandDigest: String): RouterOsResultLookup
    fun saveResult(deliveryKey: String, commandDigest: String, result: ProvisioningStepResult)
    fun acceptFence(deviceId: String, epoch: Long): Boolean
    fun snapshot(stepKey: String): PersistedRouterOsSnapshot?
    fun saveSnapshotIfAbsent(stepKey: String, snapshot: PersistedRouterOsSnapshot): PersistedRouterOsSnapshot
    fun planMutation(stepKey: String, mutation: PersistedRouterOsMutation): PersistedRouterOsMutation
    fun markMutationApplied(stepKey: String, mutationId: String, resourceId: String, after: Map<String, String>): PersistedRouterOsMutation
    fun markMutationStatus(stepKey: String, mutationId: String, status: String): PersistedRouterOsMutation
    fun markApplied(stepKey: String, afterHash: String)
}

class InMemoryRouterOsProvisioningStateStore : RouterOsProvisioningStateStore {
    private val results = linkedMapOf<String, PersistedRouterOsResult>()
    private val fences = linkedMapOf<String, Long>()
    private val snapshots = linkedMapOf<String, PersistedRouterOsSnapshot>()

    override fun <T> withExecutionLock(deviceId: String, block: () -> T): T =
        EXECUTION_LOCKS.computeIfAbsent(deviceId) { ReentrantLock() }.withLock(block)

    @Synchronized
    override fun result(deliveryKey: String, commandDigest: String): RouterOsResultLookup = results[deliveryKey]?.let {
        if (it.commandDigest == commandDigest) RouterOsResultLookup.Hit(it.result) else RouterOsResultLookup.Conflict
    } ?: RouterOsResultLookup.Missing

    @Synchronized
    override fun saveResult(deliveryKey: String, commandDigest: String, result: ProvisioningStepResult) {
        results.putIfAbsent(deliveryKey, PersistedRouterOsResult(commandDigest, result))
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
    override fun saveSnapshotIfAbsent(stepKey: String, snapshot: PersistedRouterOsSnapshot): PersistedRouterOsSnapshot =
        snapshots.getOrPut(stepKey) { snapshot }

    @Synchronized
    override fun planMutation(stepKey: String, mutation: PersistedRouterOsMutation): PersistedRouterOsMutation =
        updateSnapshot(stepKey) { snapshot ->
            snapshot.mutations.firstOrNull { it.mutationId == mutation.mutationId }?.let { return it }
            val planned = mutation.copy(order = (snapshot.mutations.maxOfOrNull { it.order } ?: 0) + 1)
            snapshots[stepKey] = snapshot.copy(mutations = snapshot.mutations + planned)
            planned
        }

    @Synchronized
    override fun markMutationApplied(
        stepKey: String,
        mutationId: String,
        resourceId: String,
        after: Map<String, String>,
    ): PersistedRouterOsMutation = replaceMutation(stepKey, mutationId) {
        it.copy(status = PersistedRouterOsMutation.MUTATION_APPLIED, id = resourceId, after = after)
    }

    @Synchronized
    override fun markMutationStatus(stepKey: String, mutationId: String, status: String): PersistedRouterOsMutation =
        replaceMutation(stepKey, mutationId) { it.copy(status = status) }

    @Synchronized
    override fun markApplied(stepKey: String, afterHash: String) {
        snapshots[stepKey] = requireSnapshot(stepKey).copy(afterHash = afterHash)
    }

    private fun requireSnapshot(stepKey: String) = checkNotNull(snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }

    private inline fun <T> updateSnapshot(stepKey: String, block: (PersistedRouterOsSnapshot) -> T): T = block(requireSnapshot(stepKey))

    private inline fun replaceMutation(
        stepKey: String,
        mutationId: String,
        transform: (PersistedRouterOsMutation) -> PersistedRouterOsMutation,
    ): PersistedRouterOsMutation {
        val snapshot = requireSnapshot(stepKey)
        val updated = transform(snapshot.mutations.single { it.mutationId == mutationId })
        snapshots[stepKey] = snapshot.copy(mutations = snapshot.mutations.map { if (it.mutationId == mutationId) updated else it })
        return updated
    }

    private companion object {
        val EXECUTION_LOCKS = ConcurrentHashMap<String, ReentrantLock>()
    }
}

class FileRouterOsProvisioningStateStore(private val file: Path) : RouterOsProvisioningStateStore {
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
    private val absoluteFile = file.toAbsolutePath().normalize()

    init {
        withStateLock { load() }
    }

    override fun <T> withExecutionLock(deviceId: String, block: () -> T): T =
        withFileLock(executionLockPath(deviceId), block)

    override fun result(deliveryKey: String, commandDigest: String): RouterOsResultLookup = read { state ->
        state.commandResults[deliveryKey]?.let {
            if (it.commandDigest == commandDigest) RouterOsResultLookup.Hit(it.result) else RouterOsResultLookup.Conflict
        } ?: RouterOsResultLookup.Missing
    }

    override fun saveResult(deliveryKey: String, commandDigest: String, result: ProvisioningStepResult) {
        update { state ->
            if (deliveryKey in state.commandResults) state
            else state.copy(commandResults = state.commandResults + (deliveryKey to PersistedRouterOsResult(commandDigest, result)))
        }
    }

    override fun acceptFence(deviceId: String, epoch: Long): Boolean {
        var accepted = false
        update { state ->
            val current = state.highestFenceByDevice[deviceId]
            if (current != null && epoch < current) return@update state
            accepted = true
            if (current == epoch) state else state.copy(highestFenceByDevice = state.highestFenceByDevice + (deviceId to epoch))
        }
        return accepted
    }

    override fun snapshot(stepKey: String): PersistedRouterOsSnapshot? = read { it.snapshots[stepKey] }

    override fun saveSnapshotIfAbsent(stepKey: String, snapshot: PersistedRouterOsSnapshot): PersistedRouterOsSnapshot {
        var installed = snapshot
        update { state ->
            state.snapshots[stepKey]?.let {
                installed = it
                state
            } ?: state.copy(snapshots = state.snapshots + (stepKey to snapshot))
        }
        return installed
    }

    override fun planMutation(stepKey: String, mutation: PersistedRouterOsMutation): PersistedRouterOsMutation {
        var result = mutation
        update { state ->
            val snapshot = state.requireSnapshot(stepKey)
            snapshot.mutations.firstOrNull { it.mutationId == mutation.mutationId }?.let {
                result = it
                return@update state
            }
            result = mutation.copy(order = (snapshot.mutations.maxOfOrNull { it.order } ?: 0) + 1)
            state.withSnapshot(stepKey, snapshot.copy(mutations = snapshot.mutations + result))
        }
        return result
    }

    override fun markMutationApplied(
        stepKey: String,
        mutationId: String,
        resourceId: String,
        after: Map<String, String>,
    ): PersistedRouterOsMutation = replaceMutation(stepKey, mutationId) {
        it.copy(status = PersistedRouterOsMutation.MUTATION_APPLIED, id = resourceId, after = after)
    }

    override fun markMutationStatus(stepKey: String, mutationId: String, status: String): PersistedRouterOsMutation =
        replaceMutation(stepKey, mutationId) { it.copy(status = status) }

    override fun markApplied(stepKey: String, afterHash: String) {
        update { state -> state.withSnapshot(stepKey, state.requireSnapshot(stepKey).copy(afterHash = afterHash)) }
    }

    private fun replaceMutation(
        stepKey: String,
        mutationId: String,
        transform: (PersistedRouterOsMutation) -> PersistedRouterOsMutation,
    ): PersistedRouterOsMutation {
        lateinit var result: PersistedRouterOsMutation
        update { state ->
            val snapshot = state.requireSnapshot(stepKey)
            result = transform(snapshot.mutations.single { it.mutationId == mutationId })
            state.withSnapshot(stepKey, snapshot.copy(mutations = snapshot.mutations.map { if (it.mutationId == mutationId) result else it }))
        }
        return result
    }

    private fun <T> read(block: (RouterOsProvisioningStateFile) -> T): T = withStateLock { block(load()) }

    private fun update(transform: (RouterOsProvisioningStateFile) -> RouterOsProvisioningStateFile) {
        withStateLock {
            val current = load()
            val next = transform(current)
            if (next != current) persist(next)
        }
    }

    private fun load(): RouterOsProvisioningStateFile {
        if (!Files.exists(absoluteFile)) {
            val backup = backupPath()
            if (!Files.exists(backup)) return RouterOsProvisioningStateFile()
            return try {
                mapper.readValue(Files.readString(backup), RouterOsProvisioningStateFile::class.java).also {
                    writeAtomically(absoluteFile, mapper.writeValueAsBytes(it))
                }
            } catch (failure: Exception) {
                throw RouterOsStateCorruptionException("ROUTEROS_STATE_BACKUP_CORRUPT", failure)
            }
        }
        return try {
            mapper.readValue(Files.readString(absoluteFile), RouterOsProvisioningStateFile::class.java)
        } catch (primaryFailure: Exception) {
            val backup = backupPath()
            if (!Files.exists(backup)) throw RouterOsStateCorruptionException("ROUTEROS_STATE_CORRUPT", primaryFailure)
            try {
                mapper.readValue(Files.readString(backup), RouterOsProvisioningStateFile::class.java).also {
                    writeAtomically(absoluteFile, mapper.writeValueAsBytes(it))
                }
            } catch (backupFailure: Exception) {
                throw RouterOsStateCorruptionException("ROUTEROS_STATE_AND_BACKUP_CORRUPT", backupFailure)
            }
        }
    }

    private fun persist(state: RouterOsProvisioningStateFile) {
        val bytes = mapper.writeValueAsBytes(state)
        writeAtomically(absoluteFile, bytes)
        writeAtomically(backupPath(), bytes)
    }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        val parent = path.parent
        Files.createDirectories(parent)
        setPermissions(parent, DIRECTORY_PERMISSIONS)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            setPermissions(temporary, FILE_PERMISSIONS)
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            setPermissions(path, FILE_PERMISSIONS)
            runCatching { FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withStateLock(block: () -> T): T = withFileLock(absoluteFile.resolveSibling("${absoluteFile.fileName}.lock"), block)

    private fun <T> withFileLock(lockPath: Path, block: () -> T): T {
        Files.createDirectories(lockPath.parent)
        setPermissions(lockPath.parent, DIRECTORY_PERMISSIONS)
        val processLock = PROCESS_LOCKS.computeIfAbsent(lockPath.toAbsolutePath().normalize()) { ReentrantLock() }
        return processLock.withLock {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                setPermissions(lockPath, FILE_PERMISSIONS)
                channel.lock().use { block() }
            }
        }
    }

    private fun executionLockPath(deviceId: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray()).joinToString("") { "%02x".format(it) }
        return absoluteFile.resolveSibling("${absoluteFile.fileName}.execution.$digest.lock")
    }

    private fun backupPath() = absoluteFile.resolveSibling("${absoluteFile.fileName}.bak")

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, permissions)
    }

    private data class RouterOsProvisioningStateFile(
        val highestFenceByDevice: Map<String, Long> = emptyMap(),
        val results: Map<String, ProvisioningStepResult> = emptyMap(),
        val commandResults: Map<String, PersistedRouterOsResult> = emptyMap(),
        val snapshots: Map<String, PersistedRouterOsSnapshot> = emptyMap(),
    ) {
        fun requireSnapshot(stepKey: String) = checkNotNull(snapshots[stepKey]) { "ROUTEROS_SNAPSHOT_MISSING" }
        fun withSnapshot(stepKey: String, snapshot: PersistedRouterOsSnapshot) =
            copy(snapshots = snapshots + (stepKey to snapshot))
    }

    private companion object {
        val PROCESS_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
