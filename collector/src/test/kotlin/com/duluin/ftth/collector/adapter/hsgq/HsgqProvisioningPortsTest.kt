package com.duluin.ftth.collector.adapter.hsgq

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HsgqProvisioningPortsTest {
    @Test
    fun `credential diagnostic rendering redacts secret`() {
        val credentials = HsgqCredentials("operator", "synthetic-secret")

        assertTrue(credentials.toString().contains("operator"))
        assertFalse(credentials.toString().contains("synthetic-secret"))
    }
}
