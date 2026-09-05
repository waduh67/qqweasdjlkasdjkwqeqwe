package com.duluin.ftth.fieldservice

import com.duluin.ftth.fieldservice.sync.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class OfflineSyncProtocolTest {
    private val tenant = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val session = UUID.randomUUID()
    private val now = Instant.parse("2026-09-04T12:00:00Z")

    private fun operation(payload: String = "check-in", occurredAt: Instant = now, revision: Long = 0, operationKey: String = "op-1", deviceId: String = "android-1") = SyncOperation(
        tenant, actor, deviceId, session, "visit.check-in", operationKey, payload, revision, occurredAt,
    )

    @Test
    fun `same key replay returns original authoritative receipt and different payload conflicts`() {
        val service = OfflineSyncService(InMemorySyncOperationStore(), { now })
        val first = service.apply(operation()) { SyncReason.ACCEPTED }
        val replay = service.apply(operation()) { error("replay must not mutate") }
        val conflict = service.apply(operation("tampered")) { error("conflict must not mutate") }

        assertThat(first.state).isEqualTo(SyncState.ACKNOWLEDGED)
        assertThat(replay).isEqualTo(first)
        assertThat(conflict.state).isEqualTo(SyncState.CONFLICT)
        assertThat(conflict.reason).isEqualTo(SyncReason.PAYLOAD_CONFLICT)
        assertThat(conflict.manualRepairRequired).isTrue()
    }

    @Test
    fun `expired events remain auditable and are terminal rejects`() {
        val store = InMemorySyncOperationStore()
        val service = OfflineSyncService(store, { now })
        val receipt = service.apply(operation(occurredAt = now.minus(73, ChronoUnit.HOURS))) { error("expired event must not mutate") }

        assertThat(receipt.state).isEqualTo(SyncState.REJECTED)
        assertThat(receipt.reason).isEqualTo(SyncReason.EXPIRED_EVENT)
        assertThat(store.all()).hasSize(1)
        assertThat(store.all().single().receipt).isEqualTo(receipt)
    }

    @Test
    fun `stale and reordered events are conflict outcomes without overwrite`() {
        val service = OfflineSyncService(InMemorySyncOperationStore(), { now })
        val first = service.apply(operation(revision = 0)) { SyncReason.ACCEPTED }
        val stale = operation("next", revision = 0, operationKey = "op-2")
        val reordered = operation("reordered", revision = 2, operationKey = "op-3")

        assertThat(service.apply(stale) { throw ConflictSyncFailure(SyncReason.STALE_REVISION) }.reason).isEqualTo(SyncReason.STALE_REVISION)
        assertThat(service.apply(reordered) { throw ConflictSyncFailure(SyncReason.REORDERED_EVENT) }.reason).isEqualTo(SyncReason.REORDERED_EVENT)
        assertThat(first.state).isEqualTo(SyncState.ACKNOWLEDGED)
    }

    @Test
    fun `revoked assignment and expired session remain retryable or terminal by server decision`() {
        val service = OfflineSyncService(InMemorySyncOperationStore(), { now })
        val revoked = service.apply(operation()) { throw RejectedSyncFailure(SyncReason.ASSIGNMENT_REVOKED) }
        val expired = service.apply(operation("session", revision = 1, operationKey = "op-2")) { throw RejectedSyncFailure(SyncReason.SESSION_EXPIRED) }

        assertThat(revoked.state).isEqualTo(SyncState.REJECTED)
        assertThat(revoked.terminal).isTrue()
        assertThat(expired.reason).isEqualTo(SyncReason.SESSION_EXPIRED)
        assertThat(expired.manualRepairRequired).isTrue()
    }

    @Test
    fun `retry is bounded and eventually requires manual repair`() {
        val service = OfflineSyncService(InMemorySyncOperationStore(), { now }, maxAttempts = 2)
        val first = service.apply(operation()) { throw RetryableSyncFailure(SyncReason.PARTIAL_UPLOAD) }
        val second = service.retry(operation()) { throw RetryableSyncFailure(SyncReason.PARTIAL_UPLOAD) }
        val third = service.retry(operation()) { error("terminal retry must not mutate") }

        assertThat(first.retryable).isTrue()
        assertThat(second.reason).isEqualTo(SyncReason.MANUAL_REPAIR_REQUIRED)
        assertThat(second.manualRepairRequired).isTrue()
        assertThat(third).isEqualTo(second)
    }

    @Test
    fun `hash binds actor device session and canonical operation fields`() {
        val original = operation()
        assertThat(original.payloadHash).isEqualTo(canonicalHash(tenant, actor, "android-1", session, "visit.check-in", "op-1", "check-in", 0))
        assertThat(original.payloadHash).isNotEqualTo(operation(deviceId = "other-device").payloadHash)
        assertThatThrownBy { OfflineSyncService(InMemorySyncOperationStore(), { now }).apply(original.copy(payloadHash = "bad")) { SyncReason.ACCEPTED } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `partial upload reconciles, rejects invalid and never commits orphan bytes`() {
        val bytes = byteArrayOf(1, 2, 3)
        val store = InMemoryResumableAttachmentStore(maxBytes = 3)
        val manifest = AttachmentManifest(tenant, "$tenant/wo/evidence/1", "image/png", 3, sha256(bytes))
        val upload = store.begin(manifest)
        store.uploadPart(upload, 1, byteArrayOf(1))
        assertThat(store.complete(upload).state).isEqualTo(AttachmentState.ORPHAN_OBJECT)
        store.uploadPart(upload, 2, byteArrayOf(2, 3))
        assertThat(store.reconcile(upload).state).isEqualTo(AttachmentState.COMMITTED)

        val invalid = store.begin(manifest.copy(sha256 = "wrong"))
        store.uploadPart(invalid, 1, bytes)
        assertThat(store.complete(invalid).state).isEqualTo(AttachmentState.ORPHAN_OBJECT)
        assertThat(store.abort(invalid).state).isEqualTo(AttachmentState.REJECTED)
        assertThatThrownBy { store.begin(manifest.copy(sizeBytes = 4)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
