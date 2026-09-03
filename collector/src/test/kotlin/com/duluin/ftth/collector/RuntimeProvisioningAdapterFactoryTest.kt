package com.duluin.ftth.collector

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.duluin.ftth.contract.NasTarget

class RuntimeProvisioningAdapterFactoryTest {
    @Test
    fun `real mode exposes all provisional runtime vendors`() {
        val registries = RuntimeProvisioningAdapterFactory.create(false, createTempDirectory())

        assertEquals(setOf("MIKROTIK", "CISCO", "JUNIPER"), registries.nas.supportedVendors)
        assertEquals(setOf("HSGQ", "HUAWEI", "ZTE"), registries.olt.supportedVendors)
        assertTrue(requireNotNull(registries.nas.forVendor("CISCO")).capabilityReport(target()).operationClasses.isEmpty())
    }

    @Test
    fun `explicit simulator mode exposes only simulator scoped vendor adapters`() {
        val mode = CollectorRuntimeMode.resolve("development", simulatorRequested = true)
        val registries = RuntimeProvisioningAdapterFactory.create(mode.simulatorEnabled, createTempDirectory())

        assertEquals(setOf("MIKROTIK", "CISCO", "JUNIPER"), registries.nas.supportedVendors)
        assertEquals(setOf("HSGQ", "HUAWEI", "ZTE"), registries.olt.supportedVendors)
        assertTrue(requireNotNull(registries.nas.forVendor("CISCO")).capabilityReport(target()).operationClasses.isNotEmpty())
    }

    private fun target() = NasTarget("nas-1", "Simulator", "CISCO", "127.0.0.1", "SIMULATOR", emptyList())
}
