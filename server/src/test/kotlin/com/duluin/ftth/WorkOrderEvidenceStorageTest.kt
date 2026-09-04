package com.duluin.ftth

import com.duluin.ftth.common.storage.DeleteGuard
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}
