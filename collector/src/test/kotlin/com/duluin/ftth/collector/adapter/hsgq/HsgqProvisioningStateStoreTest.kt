package com.duluin.ftth.collector.adapter.hsgq

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HsgqProvisioningStateStoreTest {
    @Test
    fun `snapshot survives state store restart`() {
        val directory = Files.createTempDirectory("hsgq-state-test")
        val stateFile = directory.resolve("state.json")
        try {
            val state = HsgqDeviceState(
                model = "HSGQ-E04I",
                firmware = "V1.2.3",
                managementVlanId = 100,
                managementInterface = "MGMT0",
                subscriberBindings = emptySet(),
                taggedUplinks = emptySet(),
            )
            val snapshot = HsgqProvisioningSnapshot("b".repeat(64), state, "intent-digest")
            FileHsgqProvisioningStateStore(stateFile).saveSnapshotIfAbsent("plan-9:1:step-hsgq", snapshot)

            val restarted = FileHsgqProvisioningStateStore(stateFile)

            assertEquals(snapshot, restarted.snapshot("plan-9:1:step-hsgq"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
