package com.duluin.ftth.collector

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectorRuntimeModeTest {
    @Test
    fun `production rejects simulator registration`() {
        assertFailsWith<IllegalArgumentException> {
            CollectorRuntimeMode.resolve("production", simulatorRequested = true)
        }
    }

    @Test
    fun `simulator can be selected only outside production`() {
        assertTrue(CollectorRuntimeMode.resolve("development", simulatorRequested = true).simulatorEnabled)
        assertFalse(CollectorRuntimeMode.resolve("production", simulatorRequested = false).simulatorEnabled)
    }
}
