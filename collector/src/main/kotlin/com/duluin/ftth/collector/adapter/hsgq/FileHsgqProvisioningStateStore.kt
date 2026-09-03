package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.ProvisioningStepResult
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
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

class FileHsgqProvisioningStateStore(file: Path) : HsgqProvisioningStateStore {
    private val absoluteFile = file.toAbsolutePath().normalize()
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    init {
        withStateLock { load() }
    }

    override fun <T> withExecutionLock(deviceId: String, block: () -> T): T =
        withFileLock(executionLockPath(deviceId), block)

    override fun result(deliveryKey: String, commandDigest: String): HsgqResultLookup = read { state ->
        state.results[deliveryKey]?.let {
            if (it.commandDigest == commandDigest) HsgqResultLookup.Hit(it.result) else HsgqResultLookup.Conflict
        } ?: HsgqResultLookup.Missing
    }

    override fun saveResult(deliveryKey: String, commandDigest: String, result: ProvisioningStepResult) {
        update { state ->
            if (deliveryKey in state.results) state
            else state.copy(results = state.results + (deliveryKey to HsgqPersistedResult(commandDigest, result)))
        }
    }

    override fun acceptFence(deviceId: String, epoch: Long): Boolean {
        var accepted = false
        update { state ->
            val current = state.fences[deviceId]
            if (current != null && epoch < current) return@update state
            accepted = true
            if (current == epoch) state else state.copy(fences = state.fences + (deviceId to epoch))
        }
        return accepted
    }

    override fun snapshot(stepKey: String): HsgqProvisioningSnapshot? = read { it.snapshots[stepKey] }

    override fun saveSnapshotIfAbsent(
        stepKey: String,
        snapshot: HsgqProvisioningSnapshot,
    ): HsgqProvisioningSnapshot {
        var saved = snapshot
        update { state ->
            state.snapshots[stepKey]?.let {
                saved = it
                state
            } ?: state.copy(snapshots = state.snapshots + (stepKey to snapshot))
        }
        return saved
    }

    override fun markApplied(stepKey: String, afterHash: String) {
        update { state ->
            val snapshot = state.snapshots[stepKey]
                ?: throw HsgqStatePersistenceException("HSGQ_SNAPSHOT_MISSING")
            state.copy(snapshots = state.snapshots + (stepKey to snapshot.copy(afterHash = afterHash)))
        }
    }

    private fun <T> read(block: (HsgqStateFile) -> T): T = withStateLock { block(load()) }

    private fun update(transform: (HsgqStateFile) -> HsgqStateFile) {
        withStateLock {
            val current = load()
            val next = transform(current)
            if (next != current) persist(next)
        }
    }

    private fun load(): HsgqStateFile {
        if (!Files.exists(absoluteFile)) return HsgqStateFile()
        return try {
            mapper.readValue(Files.readString(absoluteFile), HsgqStateFile::class.java)
        } catch (failure: Exception) {
            throw HsgqStatePersistenceException("HSGQ_STATE_CORRUPT", failure)
        }
    }

    private fun persist(state: HsgqStateFile) {
        val parent = absoluteFile.parent
        Files.createDirectories(parent)
        setPermissions(parent, DIRECTORY_PERMISSIONS)
        val temporary = Files.createTempFile(parent, ".${absoluteFile.fileName}.", ".tmp")
        try {
            setPermissions(temporary, FILE_PERMISSIONS)
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val bytes = ByteBuffer.wrap(mapper.writeValueAsBytes(state))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try {
                Files.move(temporary, absoluteFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, absoluteFile, StandardCopyOption.REPLACE_EXISTING)
            }
            setPermissions(absoluteFile, FILE_PERMISSIONS)
            runCatching { FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) } }
        } catch (failure: Exception) {
            throw HsgqStatePersistenceException("HSGQ_STATE_WRITE_FAILED", failure)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withStateLock(block: () -> T): T =
        withFileLock(absoluteFile.resolveSibling("${absoluteFile.fileName}.lock"), block)

    private fun <T> withFileLock(lockPath: Path, block: () -> T): T {
        Files.createDirectories(lockPath.parent)
        setPermissions(lockPath.parent, DIRECTORY_PERMISSIONS)
        return processLocks.computeIfAbsent(lockPath) { ReentrantLock() }.withLock {
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

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, permissions)
    }

    private data class HsgqStateFile(
        val fences: Map<String, Long> = emptyMap(),
        val results: Map<String, HsgqPersistedResult> = emptyMap(),
        val snapshots: Map<String, HsgqProvisioningSnapshot> = emptyMap(),
    )

    private companion object {
        val processLocks = ConcurrentHashMap<Path, ReentrantLock>()
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
