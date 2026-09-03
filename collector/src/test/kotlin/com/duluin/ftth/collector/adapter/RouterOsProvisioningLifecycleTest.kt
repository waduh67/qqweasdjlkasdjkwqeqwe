package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningTarget
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RouterOsProvisioningLifecycleTest {
    private lateinit var fixture: StatefulRouterOsFixture
    private lateinit var adapter: RouterOsProvisioningAdapter

    @BeforeTest
    fun startFixture() {
        fixture = StatefulRouterOsFixture()
        adapter = RouterOsProvisioningAdapter(
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            allowInsecureHttpForTests = true,
            stateStore = InMemoryRouterOsProvisioningStateStore(),
        )
    }

    @AfterTest
    fun stopFixture() = fixture.close()

    @Test
    fun `observation reports changed live vlan without device mutation`() {
        fixture.add(
            "/interface/bridge/vlan",
            mapOf(
                ".id" to "*OBS", "bridge" to "br-service", "vlan-ids" to "110", "tagged" to "ether1",
                "untagged" to "", "current-tagged" to "ether1", "current-untagged" to "", "comment" to "external",
            ),
        )
        val first = adapter.execute(
            fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, key = "observe-1").copy(observationOnly = true),
        )
        fixture.mutate("/interface/bridge/vlan", "*OBS", "vlan-ids", "120")
        val writesBefore = fixture.requests.count { !it.startsWith("GET ") }

        val changed = adapter.execute(
            fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, key = "observe-2").copy(observationOnly = true),
        )

        assertEquals(listOf(110), first.verification!!.state.vlanIds)
        assertEquals(listOf(120), changed.verification!!.state.vlanIds)
        assertEquals(writesBefore, fixture.requests.count { !it.startsWith("GET ") })
    }

    @Test
    fun `applies prerequisites before filtering verifies replay and compensates owned ids`() {
        fixture.add(
            "/interface/bridge",
            mapOf(
                ".id" to "*1",
                "name" to "br-service",
                "vlan-filtering" to "no",
                "comment" to "ftth:t1:i1:bridge",
            ),
        )
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        assertTrue(preflight.success)
        val beforeHash = assertNotNull(preflight.preflight).preconditionHash

        val applyCommand = command(
            phase = ProvisioningCommandPhase.APPLY,
            expectedHash = beforeHash,
            key = "plan-1:1:step-1:apply",
        )
        val applied = adapter.execute(fixture.target(), applyCommand)

        assertTrue(applied.success)
        assertTrue(assertNotNull(applied.apply).changed)
        assertTrue(assertNotNull(applied.verification).matchesExpected)
        val mutations = fixture.requests.filterNot { it.startsWith("GET ") }
        assertTrue(mutations.last().startsWith("PATCH /rest/interface/pppoe-server/server/"))
        assertTrue(fixture.rows("/interface/bridge/vlan").single()["comment"]!!.startsWith("ftth:t1:i1:"))
        assertEquals("ether1,br-service", fixture.rows("/interface/bridge/vlan").single()["current-tagged"])
        assertEquals("ether2", fixture.rows("/interface/bridge/vlan").single()["current-untagged"])
        assertEquals("svc-110", fixture.rows("/interface/pppoe-server/server").single()["interface"])
        assertEquals("drop", fixture.rows("/ip/firewall/filter").single()["action"])

        val countAfterApply = fixture.requests.size
        assertEquals(applied, adapter.execute(fixture.target(), applyCommand))
        assertEquals(countAfterApply, fixture.requests.size, "duplicate delivery must not call RouterOS again")

        fixture.returnNotFoundAfterDeleting(
            "/interface/list/member",
            fixture.rows("/interface/list/member").single().getValue(".id"),
        )

        val rollback = adapter.execute(
            fixture.target(),
            command(
                phase = ProvisioningCommandPhase.ROLLBACK,
                expectedHash = applied.verification!!.stateHash,
                key = "plan-1:1:step-1:rollback",
                fencingEpoch = 2,
            ),
        )
        assertTrue(rollback.success, "$rollback\n${fixture.requests.joinToString("\n")}")
        assertTrue(assertNotNull(rollback.rollback).success)
        assertFalse(fixture.rows("/interface/bridge").single()["vlan-filtering"].toBoolean())
        assertTrue(fixture.allRows().none { it["comment"]?.startsWith("ftth:t1:i1:") == true && it[".id"] != "*1" })

        val deleteCount = fixture.requests.count { it.startsWith("DELETE ") }
        val repeatedRollback = adapter.execute(
            fixture.target(),
            command(
                phase = ProvisioningCommandPhase.ROLLBACK,
                expectedHash = rollback.rollback!!.resultingStateHash,
                key = "plan-1:1:step-1:rollback-retry",
                fencingEpoch = 3,
            ),
        )
        assertTrue(repeatedRollback.success)
        assertEquals(rollback.rollback!!.resultingStateHash, repeatedRollback.rollback!!.resultingStateHash)
        assertEquals(deleteCount, fixture.requests.count { it.startsWith("DELETE ") })
    }

    @Test
    fun `empty preflight reports mismatch and apply is still required`() {
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        assertTrue(preflight.success)
        assertEquals(ProvisioningCommandPhase.PREFLIGHT, preflight.phase)
        assertFalse(preflight.verification!!.matchesExpected)
        assertEquals(null, preflight.apply)

        val applied = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "empty-device-apply",
            ),
        )
        assertTrue(applied.success)
        assertTrue(applied.apply!!.changed)
        val requestsAfterApply = fixture.requests.size
        assertEquals(applied, adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "empty-device-apply",
            ),
        ))
        assertEquals(requestsAfterApply, fixture.requests.size)

        val rollback = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.ROLLBACK,
                expectedHash = applied.verification!!.stateHash,
                key = "empty-device-rollback",
                fencingEpoch = 2,
            ),
        )
        assertTrue(rollback.success, rollback.toString())
        assertTrue(fixture.allRows().isEmpty())
    }

    @Test
    fun `file state rehydrates snapshot and compensates after adapter reconstruction`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        withTempStore { stateFile ->
            val first = adapterWith(FileRouterOsProvisioningStateStore(stateFile))
            val preflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 7))
            val applied = first.execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.APPLY,
                    expectedHash = preflight.preflight!!.preconditionHash,
                    key = "restart-apply",
                    fencingEpoch = 7,
                ),
            )

            val reconstructed = adapterWith(FileRouterOsProvisioningStateStore(stateFile))
            val rollback = reconstructed.execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.ROLLBACK,
                    expectedHash = applied.verification!!.stateHash,
                    key = "restart-rollback",
                    fencingEpoch = 8,
                ),
            )

            assertTrue(rollback.success, rollback.toString())
            assertTrue(fixture.allRows().none { it["comment"]?.startsWith("ftth:t1:i1:") == true && it[".id"] != "*1" })
        }
    }

    @Test
    fun `file state rejects stale fence after adapter reconstruction`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        withTempStore { stateFile ->
            val command = command(ProvisioningCommandPhase.PREFLIGHT, key = "epoch-9", fencingEpoch = 9)
            val firstResult = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(
                fixture.target(),
                command,
            )
            val requestsBefore = fixture.requests.size
            val reconstructed = adapterWith(FileRouterOsProvisioningStateStore(stateFile))

            assertEquals(firstResult, reconstructed.execute(fixture.target(), command))
            assertEquals(requestsBefore, fixture.requests.size, "durable idempotency replay must perform no HTTP")

            val stale = reconstructed.execute(
                fixture.target(),
                command(ProvisioningCommandPhase.PREFLIGHT, key = "epoch-8", fencingEpoch = 8),
            )

            assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, stale.errorCode)
            assertEquals(requestsBefore, fixture.requests.size)
            val persisted = Files.readString(stateFile)
            assertFalse(persisted.contains("provisioner"))
            assertFalse(persisted.contains("test-only"))
            assertFalse(persisted.contains("Authorization", ignoreCase = true))
            if (Files.getFileStore(stateFile).supportsFileAttributeView("posix")) {
                assertEquals(
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(stateFile),
                )
                assertEquals(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ),
                    Files.getPosixFilePermissions(stateFile.parent),
                )
            }
        }
    }

    @Test
    fun `cached delivery key rejects changed command and stale fence before replay`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val original = command(ProvisioningCommandPhase.PREFLIGHT, key = "bound-key", fencingEpoch = 9)
        val first = adapter.execute(fixture.target(), original)
        val requestsAfterFirst = fixture.requests.size

        val collision = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                key = "bound-key",
                fencingEpoch = 20,
                payloadOverrides = mapOf("vlanId" to "111"),
            ),
        )
        val stale = adapter.execute(fixture.target(), original.copy(fencingEpoch = 8))
        val legitimate = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.PREFLIGHT, key = "legitimate-epoch-10", fencingEpoch = 10),
        )

        assertTrue(first.success)
        assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, collision.errorCode)
        assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, stale.errorCode)
        assertTrue(legitimate.success)
        assertTrue(fixture.requests.size > requestsAfterFirst)

        val changedIntent = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                key = "changed-intent-new-delivery",
                fencingEpoch = 11,
                payloadOverrides = mapOf("vlanId" to "111"),
            ),
        )
        assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, changedIntent.errorCode)
    }

    @Test
    fun `concurrent duplicate apply executes one mutation sequence`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        val apply = command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "concurrent-apply")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<ProvisioningStepResult> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    adapter.execute(fixture.target(), apply)
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }

            assertTrue(results.all { it.success })
            assertEquals(results.first(), results.last())
            assertEquals(1, fixture.rows("/interface/vlan").size)
            assertEquals(1, fixture.rows("/interface/pppoe-server/server").size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ambiguous create timeout reconciles planned journal on duplicate delivery`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        fixture.timeoutAfterApplying("PUT", "/interface/bridge/port")
        val apply = command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "ambiguous-apply")

        val timedOut = adapter.execute(fixture.target(), apply)
        val resumed = adapter.execute(fixture.target(), apply)

        assertEquals(ProvisioningErrorCode.TIMEOUT, timedOut.errorCode)
        assertTrue(resumed.success)
        assertEquals(2, fixture.rows("/interface/bridge/port").size)
    }

    @Test
    fun `dropped response after mutation reconciles instead of poisoning retry`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        fixture.dropResponseAfterApplying("PUT", "/interface/bridge/port")
        val apply = command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "dropped-response-apply")

        val timedOut = adapter.execute(fixture.target(), apply)
        val resumed = adapter.execute(fixture.target(), apply)

        assertEquals(ProvisioningErrorCode.TIMEOUT, timedOut.errorCode)
        assertTrue(resumed.success)
        assertEquals(2, fixture.rows("/interface/bridge/port").size)
    }

    @Test
    fun `late preflight preserves applied snapshot for rollback`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        withTempStore { stateFile ->
            val first = adapterWith(FileRouterOsProvisioningStateStore(stateFile))
            val firstPreflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
            val applied = first.execute(
                fixture.target(),
                command(ProvisioningCommandPhase.APPLY, firstPreflight.preflight!!.preconditionHash, "late-preflight-apply"),
            )
            val reconstructed = adapterWith(FileRouterOsProvisioningStateStore(stateFile))
            val late = reconstructed.execute(
                fixture.target(),
                command(ProvisioningCommandPhase.PREFLIGHT, key = "late-preflight", fencingEpoch = 2),
            )
            val rolledBack = reconstructed.execute(
                fixture.target(),
                command(ProvisioningCommandPhase.ROLLBACK, applied.verification!!.stateHash, "late-preflight-rollback", 3),
            )

            assertEquals(firstPreflight.preflight!!.preconditionHash, late.preflight!!.preconditionHash)
            assertTrue(rolledBack.success, rolledBack.toString())
        }
    }

    @Test
    fun `crash during compensation resumes from durable inverse progress`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        withTempStore { stateFile ->
            val store = FileRouterOsProvisioningStateStore(stateFile)
            val first = adapterWith(store)
            val preflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 40))
            val applied = first.execute(
                fixture.target(),
                command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "comp-crash-apply", 40),
            )
            var crashed = false
            val crashing = adapterWith(FileRouterOsProvisioningStateStore(stateFile), afterCompensationHttpSuccess = {
                if (!crashed && it.kind == "CREATED") {
                    crashed = true
                    throw SimulatedCollectorCrash()
                }
            })
            val rollback = command(
                ProvisioningCommandPhase.ROLLBACK,
                applied.verification!!.stateHash,
                "comp-crash-rollback",
                41,
            )

            assertFailsWith<SimulatedCollectorCrash> { crashing.execute(fixture.target(), rollback) }
            val resumed = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(fixture.target(), rollback)

            assertTrue(resumed.success)
            assertTrue(fixture.allRows().none { it["comment"]?.startsWith("ftth:t1:i1:") == true && it[".id"] != "*1" })
        }
    }

    @Test
    fun `crash after compensation patch resumes without overwriting drift`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/interface/bridge/port",
            mapOf(
                ".id" to "*A",
                "bridge" to "br-service",
                "interface" to "ether2",
                "pvid" to "1",
                "ingress-filtering" to "no",
                "frame-types" to "admit-all",
                "comment" to "ftth:t1:i1:port:ether2",
            ),
        )
        withTempStore { stateFile ->
            val first = adapterWith(FileRouterOsProvisioningStateStore(stateFile))
            val preflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 50))
            val applied = first.execute(
                fixture.target(),
                command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "patch-crash-apply", 50),
            )
            var crashed = false
            val crashing = adapterWith(FileRouterOsProvisioningStateStore(stateFile), afterCompensationHttpSuccess = {
                if (!crashed && it.kind == "UPDATED" && it.endpoint == "/interface/bridge/port" && it.id == "*A") {
                    crashed = true
                    throw SimulatedCollectorCrash()
                }
            })
            val rollback = command(
                ProvisioningCommandPhase.ROLLBACK,
                applied.verification!!.stateHash,
                "patch-crash-rollback",
                51,
            )

            assertFailsWith<SimulatedCollectorCrash> { crashing.execute(fixture.target(), rollback) }
            val resumed = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(fixture.target(), rollback)

            assertTrue(resumed.success, resumed.toString())
            assertEquals("1", fixture.rows("/interface/bridge/port").single { it[".id"] == "*A" }["pvid"])
        }
    }

    @Test
    fun `crash after create http success resumes and rolls back from planned mutation`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        withTempStore { stateFile ->
            var crash = true
            val first = adapterWith(FileRouterOsProvisioningStateStore(stateFile)) { mutation ->
                if (crash && mutation.kind == "CREATED" && mutation.endpoint == "/interface/bridge/port") {
                    assertEquals(PersistedRouterOsMutation.MUTATION_PLANNED, mutation.status)
                    assertTrue(mutation.mutationId.isNotBlank() && mutation.order > 0)
                    assertTrue(Files.readString(stateFile).contains("\"status\":\"PLANNED\""))
                    crash = false
                    throw SimulatedCollectorCrash()
                }
            }
            val preflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 11))

            assertFailsWith<SimulatedCollectorCrash> {
                first.execute(
                    fixture.target(),
                    command(
                        ProvisioningCommandPhase.APPLY,
                        expectedHash = preflight.preflight!!.preconditionHash,
                        key = "interrupted-apply",
                        fencingEpoch = 11,
                    ),
                )
            }

            val resumed = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.APPLY,
                    expectedHash = preflight.preflight!!.preconditionHash,
                    key = "interrupted-apply",
                    fencingEpoch = 11,
                ),
            )
            assertTrue(resumed.success)
            assertTrue(resumed.verification!!.matchesExpected)
            val rollback = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.ROLLBACK,
                    expectedHash = resumed.verification!!.stateHash,
                    key = "interrupted-create-rollback",
                    fencingEpoch = 12,
                ),
            )
            assertTrue(rollback.success)
        }
    }

    @Test
    fun `crash after update http success resumes and rolls back from planned mutation`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/interface/bridge/port",
            mapOf(
                ".id" to "*A",
                "bridge" to "br-service",
                "interface" to "ether2",
                "pvid" to "1",
                "ingress-filtering" to "no",
                "frame-types" to "admit-all",
                "comment" to "ftth:t1:i1:port:ether2",
            ),
        )
        withTempStore { stateFile ->
            var crash = true
            val first = adapterWith(FileRouterOsProvisioningStateStore(stateFile)) { mutation ->
                if (crash && mutation.kind == "UPDATED" && mutation.endpoint == "/interface/bridge/port") {
                    assertEquals(PersistedRouterOsMutation.MUTATION_PLANNED, mutation.status)
                    assertTrue(mutation.id != null && mutation.before.isNotEmpty() && mutation.expectedAfter.isNotEmpty())
                    assertTrue(Files.readString(stateFile).contains("\"status\":\"PLANNED\""))
                    crash = false
                    throw SimulatedCollectorCrash()
                }
            }
            val preflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 21))
            val apply = command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "interrupted-update",
                fencingEpoch = 21,
            )

            assertFailsWith<SimulatedCollectorCrash> { first.execute(fixture.target(), apply) }
            val resumed = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(fixture.target(), apply)
            assertTrue(resumed.success)
            val rollback = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.ROLLBACK,
                    expectedHash = resumed.verification!!.stateHash,
                    key = "interrupted-update-rollback",
                    fencingEpoch = 22,
                ),
            )
            assertTrue(rollback.success)
            assertEquals("1", fixture.rows("/interface/bridge/port").single { it[".id"] == "*A" }["pvid"])
        }
    }

    @Test
    fun `crash after bridge activation http success resumes and rolls back from planned mutation`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = false))
        withTempStore { stateFile ->
            var crash = true
            val first = adapterWith(FileRouterOsProvisioningStateStore(stateFile)) { mutation ->
                if (crash && mutation.kind == "UPDATED" && mutation.endpoint == "/interface/bridge") {
                    assertEquals(PersistedRouterOsMutation.MUTATION_PLANNED, mutation.status)
                    assertTrue(mutation.id != null && mutation.expectedAfter["vlan-filtering"] == "yes")
                    assertTrue(Files.readString(stateFile).contains("\"status\":\"PLANNED\""))
                    crash = false
                    throw SimulatedCollectorCrash()
                }
            }
            val preflight = first.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 31))
            val apply = command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "interrupted-bridge-activation",
                fencingEpoch = 31,
            )

            assertFailsWith<SimulatedCollectorCrash> { first.execute(fixture.target(), apply) }
            assertEquals("yes", fixture.rows("/interface/pppoe-server/server").single()["disabled"])
            val resumed = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(fixture.target(), apply)
            assertTrue(resumed.success)
            assertEquals("no", fixture.rows("/interface/pppoe-server/server").single()["disabled"])
            assertEquals(
                "PATCH /rest/interface/pppoe-server/server/${fixture.rows("/interface/pppoe-server/server").single().getValue(".id")}",
                fixture.requests.filterNot { it.startsWith("GET ") }.last(),
            )
            val rollback = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.ROLLBACK,
                    expectedHash = resumed.verification!!.stateHash,
                    key = "interrupted-bridge-rollback",
                    fencingEpoch = 32,
                ),
            )
            assertTrue(rollback.success)
            assertEquals("no", fixture.rows("/interface/bridge").single()["vlan-filtering"])
        }
    }

    @Test
    fun `new bridge rejects boolean management proof without source evidence`() {
        val result = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                payloadOverrides = mapOf("managementSourceId" to "", "managementSourceType" to ""),
            ),
        )

        assertEquals(ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `existing filtering bridge adds vlan allowance before restrictive port changes`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/interface/bridge/port",
            mapOf(
                ".id" to "*A",
                "bridge" to "br-service",
                "interface" to "ether2",
                "pvid" to "1",
                "ingress-filtering" to "no",
                "frame-types" to "admit-all",
                "comment" to "ftth:t1:i1:port:ether2",
            ),
        )
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        val result = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "safe-order-apply",
            ),
        )

        assertTrue(result.success)
        val mutations = fixture.requests.filterNot { it.startsWith("GET ") }
        val vlanAllowance = mutations.indexOfFirst { it == "PUT /rest/interface/bridge/vlan" }
        val restrictivePort = mutations.indexOfFirst { it == "PATCH /rest/interface/bridge/port/*A" }
        assertTrue(vlanAllowance in 0 until restrictivePort, mutations.joinToString())
    }

    @Test
    fun `updates every mutable resource replays once and compensates exact ids`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = false))
        fixture.add(
            "/interface/bridge/port",
            mapOf(
                ".id" to "*T", "bridge" to "br-service", "interface" to "ether1", "pvid" to "7",
                "ingress-filtering" to "no", "frame-types" to "admit-all", "comment" to "ftth:t1:i1:port:ether1",
            ),
        )
        fixture.add(
            "/interface/bridge/port",
            mapOf(
                ".id" to "*A", "bridge" to "br-service", "interface" to "ether2", "pvid" to "7",
                "ingress-filtering" to "no", "frame-types" to "admit-all", "comment" to "ftth:t1:i1:port:ether2",
            ),
        )
        fixture.add(
            "/interface/bridge/vlan",
            mapOf(
                ".id" to "*BV", "bridge" to "br-service", "vlan-ids" to "110", "tagged" to "br-service",
                "untagged" to "", "current-tagged" to "br-service", "current-untagged" to "",
                "comment" to "ftth:t1:i1:bridge-vlan:110",
            ),
        )
        fixture.add(
            "/interface/vlan",
            mapOf(
                ".id" to "*V", "name" to "svc-110", "interface" to "br-service", "vlan-id" to "111",
                "comment" to "ftth:t1:i1:vlan:110",
            ),
        )
        fixture.add(
            "/interface/pppoe-server/server",
            mapOf(
                ".id" to "*P", "interface" to "svc-110", "disabled" to "yes", "service-name" to "old",
                "pppoe-over-vlan-range" to "", "comment" to "ftth:t1:i1:pppoe:110",
            ),
        )
        fixture.add(
            "/ip/pool",
            mapOf(".id" to "*POOL", "name" to "ftth-110", "ranges" to "100.64.110.2-100.64.110.10", "comment" to "ftth:t1:i1:pool:110"),
        )
        fixture.add("/interface/list", mapOf(".id" to "*L", "name" to "FTTH-CUSTOMER", "comment" to "ftth:t1:i1:list:customer"))
        fixture.add(
            "/interface/list/member",
            mapOf(".id" to "*M", "list" to "FTTH-CUSTOMER", "interface" to "svc-110", "comment" to "ftth:t1:i1:list-member:110"),
        )
        fixture.add(
            "/ip/firewall/filter",
            mapOf(
                ".id" to "*F", "chain" to "input", "action" to "accept", "in-interface-list" to "FTTH-CUSTOMER",
                "out-interface-list" to "FTTH-CUSTOMER", "disabled" to "no",
                "comment" to "ftth:t1:i1:firewall:deny-inter-vlan",
            ),
        )
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        val apply = command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "all-update-apply")

        val applied = adapter.execute(fixture.target(), apply)
        val requestCount = fixture.requests.size
        val replayed = adapter.execute(fixture.target(), apply)
        val updatedPaths = fixture.requests.filter { it.startsWith("PATCH ") }.toSet()

        assertTrue(applied.success, applied.toString())
        assertEquals(applied, replayed)
        assertEquals(requestCount, fixture.requests.size)
        assertTrue(
            setOf(
                "PATCH /rest/interface/bridge/*1",
                "PATCH /rest/interface/bridge/port/*T",
                "PATCH /rest/interface/bridge/port/*A",
                "PATCH /rest/interface/bridge/vlan/*BV",
                "PATCH /rest/interface/vlan/*V",
                "PATCH /rest/interface/pppoe-server/server/*P",
                "PATCH /rest/ip/pool/*POOL",
                "PATCH /rest/ip/firewall/filter/*F",
            ).all(updatedPaths::contains),
            updatedPaths.joinToString(),
        )

        val rollback = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.ROLLBACK, applied.verification!!.stateHash, "all-update-rollback", 2),
        )
        assertTrue(rollback.success, rollback.toString())
        assertEquals("7", fixture.rows("/interface/bridge/port").single { it[".id"] == "*T" }["pvid"])
        assertEquals("111", fixture.rows("/interface/vlan").single()["vlan-id"])
        assertEquals("old", fixture.rows("/interface/pppoe-server/server").single()["service-name"])
        assertEquals("accept", fixture.rows("/ip/firewall/filter").single()["action"])
        assertEquals("FTTH-CUSTOMER", fixture.rows("/interface/list").single()["name"])
        assertEquals("svc-110", fixture.rows("/interface/list/member").single()["interface"])
    }

    @Test
    fun `verification detects pppoe vlan range drift`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                payloadOverrides = mapOf("pppoeInterface" to "br-service", "vlanInterface" to "svc-110", "pppoeVlanRange" to "111-120"),
            ),
        )
        val applied = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "range-apply",
                payloadOverrides = mapOf("pppoeInterface" to "br-service", "vlanInterface" to "svc-110", "pppoeVlanRange" to "111-120"),
            ),
        )
        assertTrue(applied.success)
        val serverId = fixture.rows("/interface/pppoe-server/server").single().getValue(".id")
        fixture.mutate("/interface/pppoe-server/server", serverId, "pppoe-over-vlan-range", "121-130")

        val verify = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.VERIFY,
                key = "range-verify",
                fencingEpoch = 2,
                payloadOverrides = mapOf("pppoeInterface" to "br-service", "vlanInterface" to "svc-110", "pppoeVlanRange" to "111-120"),
            ),
        )
        assertEquals(ProvisioningErrorCode.VERIFICATION_MISMATCH, verify.errorCode)
    }

    @Test
    fun `updated row unrelated mutable drift blocks rollback`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/interface/bridge/port",
            mapOf(
                ".id" to "*A",
                "bridge" to "br-service",
                "interface" to "ether2",
                "pvid" to "1",
                "ingress-filtering" to "no",
                "frame-types" to "admit-all",
                "horizon" to "none",
                "comment" to "ftth:t1:i1:port:ether2",
            ),
        )
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        val applied = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "updated-apply"),
        )
        assertTrue(applied.success)
        fixture.mutate("/interface/bridge/port", "*A", "horizon", "5")
        val observation = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.VERIFY, key = "updated-drift-observe", fencingEpoch = 2),
        )

        val rollback = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.ROLLBACK,
                expectedHash = observation.verification!!.stateHash,
                key = "updated-drift-rollback",
                fencingEpoch = 3,
            ),
        )
        assertEquals(ProvisioningErrorCode.ROLLBACK_CONFLICT, rollback.errorCode)
        assertEquals("5", fixture.rows("/interface/bridge/port").single { it[".id"] == "*A" }["horizon"])
    }

    @Test
    fun `rejects vlan interface overlap with pppoe vlan range before mutation`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/interface/vlan",
            mapOf(".id" to "*4", "name" to "existing-110", "interface" to "br-service", "vlan-id" to "110"),
        )
        fixture.add(
            "/interface/pppoe-server/server",
            mapOf(".id" to "*5", "interface" to "br-service", "disabled" to "no", "pppoe-over-vlan-range" to "100-120"),
        )

        val result = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        assertFalse(result.success)
        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })

    }

    @Test
    fun `rejects desired vlan interface inside pppoe over vlan range`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))

        val result = adapter.execute(
            fixture.target(),
            command(
                phase = ProvisioningCommandPhase.PREFLIGHT,
                payloadOverrides = mapOf("pppoeInterface" to "br-service", "pppoeVlanRange" to "100-120"),
            ),
        )

        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })

    }

    @Test
    fun `rejects vlan interface used as vlan parent before mutation`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/interface/vlan",
            mapOf(".id" to "*outer", "name" to "outer-100", "interface" to "ether1", "vlan-id" to "100"),
        )

        val result = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.PREFLIGHT, payloadOverrides = mapOf("vlanParent" to "outer-100")),
        )

        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })

        val selfParent = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                key = "self-parent",
                fencingEpoch = 2,
                payloadOverrides = mapOf("vlanParent" to "svc-110"),
            ),
        )
        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, selfParent.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `rejects qinq translation and native tagging before discovery`() {
        listOf("QINQ", "TRANSLATION", "NATIVE").forEachIndexed { index, tagging ->
            val result = adapter.execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.PREFLIGHT,
                    key = "unsupported-tagging-$tagging",
                    fencingEpoch = index.toLong() + 1,
                    payloadOverrides = mapOf("tagging" to tagging),
                ),
            )
            assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode, tagging)
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `verification detects trunk pvid drift`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        val applied = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "trunk-pvid-apply"),
        )
        assertTrue(applied.success)
        val trunk = fixture.rows("/interface/bridge/port").single { it["interface"] == "ether1" }
        fixture.mutate("/interface/bridge/port", trunk.getValue(".id"), "pvid", "99")

        val verified = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.VERIFY, key = "trunk-pvid-verify", fencingEpoch = 2),
        )

        assertEquals(ProvisioningErrorCode.VERIFICATION_MISMATCH, verified.errorCode)
    }

    @Test
    fun `verification rejects firewall rule narrowed by semantic predicate`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        val applied = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.APPLY, preflight.preflight!!.preconditionHash, "firewall-apply"),
        )
        assertTrue(applied.success)
        val firewall = fixture.rows("/ip/firewall/filter").single()
        fixture.mutate("/ip/firewall/filter", firewall.getValue(".id"), "protocol", "tcp")

        val verified = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.VERIFY, key = "firewall-verify", fencingEpoch = 2),
        )

        assertEquals(ProvisioningErrorCode.VERIFICATION_MISMATCH, verified.errorCode)
    }

    @Test
    fun `preflight rejects owned firewall with hidden narrowing predicate before mutation`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        fixture.add(
            "/ip/firewall/filter",
            mapOf(
                ".id" to "*F", "chain" to "forward", "action" to "drop",
                "in-interface-list" to "FTTH-CUSTOMER", "out-interface-list" to "FTTH-CUSTOMER",
                "disabled" to "no", "protocol" to "tcp", "comment" to "ftth:t1:i1:firewall:deny-inter-vlan",
            ),
        )

        val result = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        assertEquals(ProvisioningErrorCode.VALIDATION_FAILED, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `legacy boolean alone cannot authorize bridge activation`() {
        val result = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                payloadOverrides = mapOf("managementSourceId" to "", "managementSourceType" to ""),
            ),
        )

        assertEquals(ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `already desired unowned bridge is never adopted`() {
        fixture.add(
            "/interface/bridge",
            mapOf(".id" to "*1", "name" to "br-service", "vlan-filtering" to "yes", "comment" to "operator-owned"),
        )

        val result = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        assertEquals(ProvisioningErrorCode.PROTECTED_RESOURCE, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `malformed or unsupported management evidence cannot authorize activation`() {
        listOf(
            mapOf("managementSourceId" to "not-a-uuid", "managementSourceType" to "TOPOLOGY_OBSERVATION"),
            mapOf(
                "managementSourceId" to "0199386e-9718-7000-8000-000000000201",
                "managementSourceType" to "CALLER_ASSERTION",
            ),
        ).forEachIndexed { index, overrides ->
            val result = adapter.execute(
                fixture.target(),
                command(
                    ProvisioningCommandPhase.PREFLIGHT,
                    key = "invalid-management-evidence-$index",
                    fencingEpoch = index.toLong() + 1,
                    payloadOverrides = overrides,
                ),
            )
            assertEquals(ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN, result.errorCode)
        }
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `refuses unknown brownfield bridge activation with unmodelled management port`() {
        fixture.add(
            "/interface/bridge",
            mapOf(".id" to "*1", "name" to "br-service", "vlan-filtering" to "no"),
        )
        fixture.add(
            "/interface/bridge/port",
            mapOf(".id" to "*A", "bridge" to "br-service", "interface" to "mgmt", "pvid" to "99"),
        )

        val result = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        assertFalse(result.success)
        assertEquals(ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `refuses apply after precondition drift and rejects stale fencing token`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT, fencingEpoch = 5))
        fixture.add(
            "/interface/list",
            mapOf(".id" to "*drift", "name" to "manual-list", "comment" to "operator-owned"),
        )

        val drift = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "drifted-apply",
                fencingEpoch = 5,
            ),
        )
        val staleFence = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.PREFLIGHT, key = "stale-fence", fencingEpoch = 4),
        )

        assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, drift.errorCode)
        assertEquals(ProvisioningErrorCode.STALE_PRECONDITION, staleFence.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `production adapter rejects insecure HTTP before network access`() {
        val production = RouterOsProvisioningAdapter(
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            stateStore = InMemoryRouterOsProvisioningStateStore(),
        )

        val result = production.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))

        assertFalse(result.success)
        assertEquals(ProvisioningErrorCode.INSECURE_TRANSPORT, result.errorCode)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun `rejects mutation touching a protected management interface`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))

        val result = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                payloadOverrides = mapOf("protectedInterfaces" to "mgmt,ether2"),
            ),
        )

        assertEquals(ProvisioningErrorCode.PROTECTED_RESOURCE, result.errorCode)
        assertTrue(fixture.requests.none { !it.startsWith("GET ") })
    }

    @Test
    fun `compensation refuses to delete a resource whose ownership comment drifted`() {
        fixture.add("/interface/bridge", ownedBridge(filtering = true))
        val preflight = adapter.execute(fixture.target(), command(ProvisioningCommandPhase.PREFLIGHT))
        val applied = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.APPLY,
                expectedHash = preflight.preflight!!.preconditionHash,
                key = "apply-before-owner-drift",
            ),
        )
        val memberId = fixture.rows("/interface/list/member").single().getValue(".id")
        fixture.mutate("/interface/list/member", memberId, "comment", "operator-owned")
        val observation = adapter.execute(
            fixture.target(),
            command(ProvisioningCommandPhase.VERIFY, key = "verify-owner-drift", fencingEpoch = 2),
        )
        val deletesBefore = fixture.requests.count { it.startsWith("DELETE ") }

        val rollback = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.ROLLBACK,
                expectedHash = observation.verification!!.stateHash,
                key = "rollback-owner-drift",
                fencingEpoch = 3,
            ),
        )

        assertTrue(applied.success)
        assertEquals(ProvisioningErrorCode.ROLLBACK_CONFLICT, rollback.errorCode)
        assertEquals(deletesBefore, fixture.requests.count { it.startsWith("DELETE ") })
        assertEquals("operator-owned", fixture.rows("/interface/list/member").single()["comment"])
    }

    private fun command(
        phase: ProvisioningCommandPhase,
        expectedHash: String? = null,
        key: String = "plan-1:1:step-1:${phase.name.lowercase()}",
        fencingEpoch: Long = 1,
        payloadOverrides: Map<String, String> = emptyMap(),
    ) = ProvisioningPlanStepCommand(
        planId = "plan-1",
        revision = 1,
        stepId = "step-1",
        phase = phase,
        operationClass = "ENSURE_PPPOE_TERMINATION",
        idempotencyKey = key,
        fencingEpoch = fencingEpoch,
        expectedPreconditionHash = expectedHash,
        deadline = NOW.plusSeconds(30),
        target = ProvisioningTarget("router-1", "BRAS", "127.0.0.1", "HTTPS_REST"),
        payload = ProvisioningPayload(
            mapOf(
                "tenantId" to "t1",
                "intentId" to "i1",
                "bridge" to "br-service",
                "vlanId" to "110",
                "trunkPorts" to "ether1",
                "accessPorts" to "ether2",
                "vlanInterface" to "svc-110",
                "vlanParent" to "br-service",
                "pppoeInterface" to "svc-110",
                "pppoeServiceName" to "ftth-110",
                "poolName" to "ftth-110",
                "poolRanges" to "100.64.110.2-100.64.110.254",
                "interfaceList" to "FTTH-CUSTOMER",
                "firewallChain" to "forward",
                "managementPathProven" to "true",
                "managementSourceId" to "0199386e-9718-7000-8000-000000000201",
                "managementSourceType" to "TOPOLOGY_OBSERVATION",
                "protectedInterfaces" to "mgmt",
                "protectedVlanIds" to "99",
            ) + payloadOverrides,
        ),
    )

    private fun ownedBridge(filtering: Boolean) = mapOf(
        ".id" to "*1",
        "name" to "br-service",
        "vlan-filtering" to if (filtering) "yes" else "no",
        "comment" to "ftth:t1:i1:bridge",
    )

    private fun adapterWith(
        store: RouterOsProvisioningStateStore,
        afterCompensationHttpSuccess: (PersistedRouterOsMutation) -> Unit = {},
        afterMutationHttpSuccess: (PersistedRouterOsMutation) -> Unit = {},
    ) = RouterOsProvisioningAdapter(
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        allowInsecureHttpForTests = true,
        stateStore = store,
        afterMutationHttpSuccess = afterMutationHttpSuccess,
        afterCompensationHttpSuccess = afterCompensationHttpSuccess,
    )

    private fun withTempStore(block: (java.nio.file.Path) -> Unit) {
        val directory = createTempDirectory("routeros-state-test")
        try {
            block(directory.resolve("routeros-provisioning.json"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class SimulatedCollectorCrash : Error("simulated collector crash")


    private class StatefulRouterOsFixture : AutoCloseable {
        private val mapper = JsonMapper.builder().build()
        private val ids = AtomicInteger(16)
        private val resources = linkedMapOf<String, MutableList<MutableMap<String, String>>>()
        private val deleteAsNotFound = mutableSetOf<Pair<String, String>>()
        private val timeoutAfterMutation = mutableSetOf<Pair<String, String>>()
        private val dropAfterMutation = mutableSetOf<Pair<String, String>>()
        val requests = mutableListOf<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            ENDPOINTS.forEach { resources[it] = mutableListOf() }
            server.createContext("/rest", ::handle)
            server.start()
        }

        fun target() = NasTarget(
            nasId = "router-1",
            name = "router-1",
            vendor = "MIKROTIK",
            host = "127.0.0.1",
            adapterType = "ROUTER_OS",
            apiUsername = "provisioner",
            apiSecret = "test-only",
            apiPort = server.address.port,
            apiUseTls = false,
        )

        fun add(endpoint: String, row: Map<String, String>) {
            resources.getValue(endpoint) += row.toMutableMap()
        }

        fun rows(endpoint: String): List<Map<String, String>> = resources.getValue(endpoint).map(Map<String, String>::toMap)
        fun allRows(): List<Map<String, String>> = resources.values.flatten()
        fun returnNotFoundAfterDeleting(endpoint: String, id: String) {
            deleteAsNotFound += endpoint to id
        }
        fun timeoutAfterApplying(method: String, endpoint: String) {
            timeoutAfterMutation += method to endpoint
        }
        fun dropResponseAfterApplying(method: String, endpoint: String) {
            dropAfterMutation += method to endpoint
        }
        fun mutate(endpoint: String, id: String, key: String, value: String) {
            resources.getValue(endpoint).single { it[".id"] == id }[key] = value
        }
        override fun close() = server.stop(0)

        private fun handle(exchange: HttpExchange) {
            val method = exchange.requestMethod
            val fullPath = exchange.requestURI.path
            requests += "$method $fullPath"
            if (fullPath == "/rest/system/resource") {
                respond(exchange, listOf(mapOf("platform" to "MikroTik", "board-name" to "CCR2004", "version" to "7.20.1")))
                return
            }
            val relative = fullPath.removePrefix("/rest")
            val endpoint = ENDPOINTS.firstOrNull { relative == it || relative.startsWith("$it/") }
            if (endpoint == null) {
                respond(exchange, mapOf("error" to "404"), 404)
                return
            }
            val id = relative.removePrefix(endpoint).removePrefix("/").ifBlank { null }
            when (method) {
                "GET" -> if (id == null) {
                    respond(exchange, resources.getValue(endpoint))
                } else {
                    val row = resources.getValue(endpoint).singleOrNull { it[".id"] == id }
                    if (row == null) respond(exchange, mapOf("error" to "404"), 404) else respond(exchange, row)
                }
                "PUT" -> {
                    val row = body(exchange).toMutableMap()
                    row[".id"] = "*${ids.getAndIncrement().toString(16).uppercase()}"
                    if (endpoint == "/interface/bridge/vlan") {
                        row["current-tagged"] = row["tagged"].orEmpty()
                        row["current-untagged"] = row["untagged"].orEmpty()
                    }
                    resources.getValue(endpoint) += row
                    if (dropAfterMutation.remove(method to endpoint)) {
                        exchange.close()
                    } else if (timeoutAfterMutation.remove(method to endpoint)) {
                        respond(exchange, mapOf("error" to "timeout"), 408)
                    } else {
                        respond(exchange, row)
                    }
                }
                "PATCH" -> {
                    val row = resources.getValue(endpoint).singleOrNull { it[".id"] == id }
                    if (row == null) respond(exchange, mapOf("error" to "404"), 404)
                    else {
                        row.putAll(body(exchange))
                        if (endpoint == "/interface/bridge/vlan") {
                            row["current-tagged"] = row["tagged"].orEmpty()
                            row["current-untagged"] = row["untagged"].orEmpty()
                        }
                        if (dropAfterMutation.remove(method to endpoint)) {
                            exchange.close()
                        } else if (timeoutAfterMutation.remove(method to endpoint)) {
                            respond(exchange, mapOf("error" to "timeout"), 408)
                        } else {
                            respond(exchange, row)
                        }
                    }
                }
                "DELETE" -> {
                    val removed = resources.getValue(endpoint).removeIf { it[".id"] == id }
                    val notFoundRace = id != null && deleteAsNotFound.remove(endpoint to id)
                    respond(
                        exchange,
                        if (removed && !notFoundRace) emptyMap<String, String>() else mapOf("error" to "404"),
                        if (removed && !notFoundRace) 200 else 404,
                    )
                }
                else -> respond(exchange, mapOf("error" to "405"), 405)
            }
        }

        private fun body(exchange: HttpExchange): Map<String, String> = mapper.readValue(
            exchange.requestBody,
            object : TypeReference<Map<String, String>>() {},
        )

        private fun respond(exchange: HttpExchange, body: Any, status: Int = 200) {
            val bytes = mapper.writeValueAsBytes(body)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        companion object {
            val ENDPOINTS = listOf(
                "/interface/bridge/port",
                "/interface/bridge/vlan",
                "/interface/pppoe-server/server",
                "/interface/list/member",
                "/ip/firewall/filter",
                "/interface/bridge",
                "/interface/vlan",
                "/interface/list",
                "/ip/pool",
            )
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-02T10:00:00Z")
    }
}
