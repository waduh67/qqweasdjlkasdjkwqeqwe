package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningStepResult
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RouterOsProvisioningStateStoreTest {
    @Test
    fun `cached result is bound to exact command digest`() {
        val store = InMemoryRouterOsProvisioningStateStore()
        store.saveResult("delivery-1", "digest-a", failedResult())

        assertIs<RouterOsResultLookup.Hit>(store.result("delivery-1", "digest-a"))
        assertIs<RouterOsResultLookup.Conflict>(store.result("delivery-1", "digest-b"))
    }

    @Test
    fun `file execution reservation serializes independent store instances`() {
        withTempStore { stateFile ->
            val first = FileRouterOsProvisioningStateStore(stateFile)
            val second = FileRouterOsProvisioningStateStore(stateFile)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val firstRun = executor.submit {
                    first.withExecutionLock("router-1") {
                        maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        active.decrementAndGet()
                    }
                }
                entered.await(5, TimeUnit.SECONDS)
                val secondRun = executor.submit {
                    second.withExecutionLock("router-1") {
                        maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        active.decrementAndGet()
                    }
                }
                Thread.sleep(100)
                assertEquals(1, maximum.get())
                release.countDown()
                firstRun.get(5, TimeUnit.SECONDS)
                secondRun.get(5, TimeUnit.SECONDS)
                assertEquals(1, maximum.get())
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `corrupt state without valid backup fails closed`() {
        withTempStore { stateFile ->
            Files.writeString(stateFile, "{not-json")

            assertFailsWith<RouterOsStateCorruptionException> {
                FileRouterOsProvisioningStateStore(stateFile)
            }
        }
    }

    @Test
    fun `corrupt primary recovers the last durable backup`() {
        withTempStore { stateFile ->
            val store = FileRouterOsProvisioningStateStore(stateFile)
            assertEquals(true, store.acceptFence("router-1", 5))
            assertEquals(true, store.acceptFence("router-1", 6))
            Files.writeString(stateFile, "{not-json")

            val recovered = FileRouterOsProvisioningStateStore(stateFile)

            assertEquals(false, recovered.acceptFence("router-1", 5))
        }
    }

    @Test
    fun `missing primary recovers current backup instead of empty state`() {
        withTempStore { stateFile ->
            val store = FileRouterOsProvisioningStateStore(stateFile)
            assertEquals(true, store.acceptFence("router-1", 5))
            assertEquals(true, store.acceptFence("router-1", 6))
            Files.delete(stateFile)

            val recovered = FileRouterOsProvisioningStateStore(stateFile)

            assertEquals(false, recovered.acceptFence("router-1", 5))
        }
    }

    @Test
    fun `legacy state without command bindings remains readable and fenced`() {
        withTempStore { stateFile ->
            Files.writeString(
                stateFile,
                """{"highestFenceByDevice":{"router-1":5},"results":{},"snapshots":{}}""",
            )

            val store = FileRouterOsProvisioningStateStore(stateFile)

            assertEquals(false, store.acceptFence("router-1", 4))
            assertIs<RouterOsResultLookup.Missing>(store.result("legacy", "digest"))
        }
    }

    private fun failedResult() = ProvisioningStepResult(
        planId = "plan-1",
        revision = 1,
        stepId = "step-1",
        targetId = "router-1",
        operationClass = "ENSURE_PPPOE_TERMINATION",
        idempotencyKey = "delivery-1",
        fencingEpoch = 1,
        phase = ProvisioningCommandPhase.APPLY,
        success = false,
        completedAt = Instant.parse("2026-09-02T10:00:00Z"),
        errorCode = ProvisioningErrorCode.TIMEOUT,
    )

    private fun withTempStore(block: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("routeros-store-test")
        try {
            block(directory.resolve("state.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
