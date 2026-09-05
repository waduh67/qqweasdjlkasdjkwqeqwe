package com.duluin.ftth.simulator.network

import kotlin.test.Test
import kotlin.test.assertEquals

class DeterministicFaultsTest {
    @Test
    fun `delayed and lost acknowledgements use a bounded deterministic queue`() {
        val queue = DeterministicAckQueue()
        queue.enqueue("lost", delayTicks = 0, disposition = AckDisposition.LOST)
        queue.enqueue("delayed", delayTicks = 2, disposition = AckDisposition.DELIVER)

        assertEquals(emptyList(), queue.advance())
        assertEquals(listOf("delayed"), queue.advance())
        assertEquals(2, queue.tick)
        assertEquals(1, queue.lostCount)
    }
}
