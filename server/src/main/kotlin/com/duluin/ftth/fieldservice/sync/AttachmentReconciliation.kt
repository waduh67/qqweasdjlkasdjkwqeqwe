package com.duluin.ftth.fieldservice.sync

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AttachmentManifest(
    val tenantId: UUID,
    val objectKey: String,
    val contentType: String,
    val sizeBytes: Long,
    val sha256: String,
)

enum class AttachmentState { PENDING, UPLOADING, COMMITTED, ORPHAN_OBJECT, REJECTED }

data class AttachmentReceipt(val uploadId: UUID, val state: AttachmentState, val reason: SyncReason?, val retryable: Boolean, val terminal: Boolean)

interface ResumableAttachmentStore {
    fun begin(manifest: AttachmentManifest): UUID
    fun uploadPart(uploadId: UUID, partNumber: Int, bytes: ByteArray)
    fun complete(uploadId: UUID): AttachmentReceipt
    fun abort(uploadId: UUID): AttachmentReceipt
    fun reconcile(uploadId: UUID): AttachmentReceipt
}

class InMemoryResumableAttachmentStore(private val maxBytes: Long = 10L * 1024 * 1024, private val maxAttempts: Int = 3) : ResumableAttachmentStore {
    private data class Upload(val manifest: AttachmentManifest, val parts: MutableMap<Int, ByteArray>, var attempts: Int = 0, var state: AttachmentState = AttachmentState.PENDING)
    private val uploads = ConcurrentHashMap<UUID, Upload>()

    override fun begin(manifest: AttachmentManifest): UUID {
        require(manifest.sizeBytes in 1..maxBytes) { "Attachment size is outside the permitted bound" }
        require(manifest.objectKey.startsWith("${manifest.tenantId}/")) { "Cross-tenant attachment key" }
        return UUID.randomUUID().also { uploads[it] = Upload(manifest, linkedMapOf(), state = AttachmentState.UPLOADING) }
    }

    override fun uploadPart(uploadId: UUID, partNumber: Int, bytes: ByteArray) {
        require(partNumber > 0)
        val upload = uploads[uploadId] ?: error("Unknown upload")
        check(upload.state == AttachmentState.UPLOADING || upload.state == AttachmentState.ORPHAN_OBJECT) { "Upload is not resumable" }
        upload.state = AttachmentState.UPLOADING
        upload.parts[partNumber] = bytes.copyOf()
    }

    override fun complete(uploadId: UUID): AttachmentReceipt {
        val upload = uploads[uploadId] ?: error("Unknown upload")
        if (upload.state == AttachmentState.COMMITTED || upload.state == AttachmentState.REJECTED) return receipt(uploadId, upload)
        upload.attempts++
        val bytes = upload.parts.toSortedMap().values.fold(ByteArray(0)) { result, part -> result + part }
        if (bytes.size.toLong() != upload.manifest.sizeBytes || sha256(bytes) != upload.manifest.sha256) {
            upload.state = if (upload.attempts >= maxAttempts) AttachmentState.REJECTED else AttachmentState.ORPHAN_OBJECT
            return receipt(uploadId, upload, if (upload.state == AttachmentState.REJECTED) SyncReason.MANUAL_REPAIR_REQUIRED else SyncReason.PARTIAL_UPLOAD)
        }
        upload.state = AttachmentState.COMMITTED
        return receipt(uploadId, upload)
    }

    override fun abort(uploadId: UUID): AttachmentReceipt {
        val upload = uploads[uploadId] ?: error("Unknown upload")
        upload.state = AttachmentState.REJECTED
        return receipt(uploadId, upload, SyncReason.RETRY_EXHAUSTED)
    }

    override fun reconcile(uploadId: UUID): AttachmentReceipt = complete(uploadId)

    private fun receipt(id: UUID, upload: Upload, reason: SyncReason? = null) = AttachmentReceipt(id, upload.state, reason, upload.state == AttachmentState.ORPHAN_OBJECT, upload.state == AttachmentState.COMMITTED || upload.state == AttachmentState.REJECTED)
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
