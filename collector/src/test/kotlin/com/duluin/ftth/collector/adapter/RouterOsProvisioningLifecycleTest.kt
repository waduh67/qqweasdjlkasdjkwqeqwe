package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
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
        assertEquals("PATCH /rest/interface/bridge/*1", mutations.last())
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
        assertTrue(rollback.success)
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

            assertTrue(rollback.success)
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
            val resumed = adapterWith(FileRouterOsProvisioningStateStore(stateFile)).execute(fixture.target(), apply)
            assertTrue(resumed.success)
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
    fun `new bridge rejects boolean management proof without protected model`() {
        val result = adapter.execute(
            fixture.target(),
            command(
                ProvisioningCommandPhase.PREFLIGHT,
                payloadOverrides = mapOf("protectedInterfaces" to "", "protectedVlanIds" to ""),
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
        afterMutationHttpSuccess: (PersistedRouterOsMutation) -> Unit = {},
    ) = RouterOsProvisioningAdapter(
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
        allowInsecureHttpForTests = true,
        stateStore = store,
        afterMutationHttpSuccess = afterMutationHttpSuccess,
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
                    respond(exchange, row)
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
                        respond(exchange, row)
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
