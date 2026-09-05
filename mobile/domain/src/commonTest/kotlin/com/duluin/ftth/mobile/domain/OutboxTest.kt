package com.duluin.ftth.mobile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class OutboxTest {
    @Test
    fun replayIsIdempotentAndConflictIsStable() {
        val outbox = InMemoryOutbox()
        val operation = OutboxOperation("visit-1:check-in", "check-in")
        assertEquals(EnqueueResult.Accepted, outbox.enqueue(operation))
        assertEquals(EnqueueResult.Replayed, outbox.enqueue(operation))
        assertEquals(EnqueueResult.Conflict, outbox.enqueue(operation.copy(payload = "different")))
    }
}
