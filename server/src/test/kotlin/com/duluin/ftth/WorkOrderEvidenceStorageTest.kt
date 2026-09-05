package com.duluin.ftth

import com.duluin.ftth.common.storage.DeleteGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertThrows

class WorkOrderEvidenceStorageTest {
    @Test
    fun `tenant scoped listing and conditional delete never cross prefixes`() {
        val storage = InMemoryObjectStorage()
        storage.put("tenant-a/wo/revision", "image/png", byteArrayOf(1, 2, 3))
        storage.put("tenant-b/wo/revision", "image/png", byteArrayOf(4))

        assertThat(storage.list("tenant-a", "tenant-a/wo/").objects).extracting<String> { it.key }
            .containsExactly("tenant-a/wo/revision")
        assertThat(storage.deleteIfMatch("tenant-a", "tenant-a/wo/revision", DeleteGuard(etag = "wrong"))).isFalse()
        assertThat(storage.head("tenant-a", "tenant-a/wo/revision").size).isEqualTo(3)
        assertThrows<IllegalArgumentException> { storage.head("tenant-a", "tenant-b/wo/revision") }
    }

    @Test
    fun `conditional delete reports conflict when a concurrent commit replaces the listed object`() {
        val storage = InMemoryObjectStorage()
        storage.put("tenant-a/wo/revision", "image/png", byteArrayOf(1))
        val listed = storage.head("tenant-a", "tenant-a/wo/revision")
        val replacementCommitted = CountDownLatch(1)
        val deletionAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val replacement = executor.submit {
            replacementCommitted.await(5, TimeUnit.SECONDS)
            storage.put("tenant-a/wo/revision", "image/png", byteArrayOf(2))
            deletionAttempted.countDown()
        }
        val deletion = executor.submit<Boolean> {
            replacementCommitted.countDown()
            deletionAttempted.await(5, TimeUnit.SECONDS)
            storage.deleteIfMatch("tenant-a", "tenant-a/wo/revision", DeleteGuard(etag = listed.sha256))
        }

        replacement.get(5, TimeUnit.SECONDS)
        assertThat(deletion.get(5, TimeUnit.SECONDS)).isFalse()
        assertThat(storage.head("tenant-a", "tenant-a/wo/revision").size).isEqualTo(1)
        executor.shutdownNow()
    }
}
