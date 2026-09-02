package com.duluin.ftth.collector.adapter

import com.duluin.ftth.contract.DeviceCapabilityReport
import com.duluin.ftth.contract.DeviceFingerprint
import com.duluin.ftth.contract.NasTarget
import com.duluin.ftth.contract.ProvisioningApplyResult
import com.duluin.ftth.contract.ProvisioningCommandPhase
import com.duluin.ftth.contract.ProvisioningErrorCode
import com.duluin.ftth.contract.ProvisioningPayload
import com.duluin.ftth.contract.ProvisioningPlanStepCommand
import com.duluin.ftth.contract.ProvisioningPreflightSnapshot
import com.duluin.ftth.contract.ProvisioningRollbackResult
import com.duluin.ftth.contract.ProvisioningResultState
import com.duluin.ftth.contract.ProvisioningStepResult
import com.duluin.ftth.contract.ProvisioningVerificationObservation
import com.duluin.ftth.contract.deliveryKey
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.Base64

data class RouterOsBridge(
    val id: String,
    val name: String,
    val vlanFiltering: Boolean,
    val comment: String?,
)

data class RouterOsBridgePort(
    val id: String,
    val bridge: String,
    val interfaceName: String,
    val pvid: Int,
    val ingressFiltering: Boolean,
    val frameTypes: String,
    val comment: String?,
)

data class RouterOsBridgeVlan(
    val id: String,
    val bridge: String,
    val vlanIds: Set<Int>,
    val tagged: Set<String>,
    val untagged: Set<String>,
    val currentTagged: Set<String>,
    val currentUntagged: Set<String>,
    val comment: String?,
)

data class RouterOsVlanInterface(
    val id: String,
    val name: String,
    val interfaceName: String,
    val vlanId: Int,
    val comment: String?,
)

data class RouterOsPppoeServer(
    val id: String,
    val interfaceName: String,
    val disabled: Boolean,
    val serviceName: String,
    val vlanRange: Set<Int>,
    val comment: String?,
)

data class RouterOsIpPool(val id: String, val name: String, val ranges: String, val comment: String?)
data class RouterOsInterfaceList(val id: String, val name: String, val comment: String?)
data class RouterOsInterfaceListMember(
    val id: String,
    val list: String,
    val interfaceName: String,
    val comment: String?,
)

data class RouterOsFirewallRule(
    val id: String,
    val chain: String,
    val action: String,
    val inInterfaceList: String?,
    val outInterfaceList: String?,
    val comment: String?,
    val disabled: Boolean,
)

data class RouterOsNormalizedState(
    val bridges: List<RouterOsBridge>,
    val bridgePorts: List<RouterOsBridgePort>,
    val bridgeVlans: List<RouterOsBridgeVlan>,
    val vlanInterfaces: List<RouterOsVlanInterface>,
    val pppoeServers: List<RouterOsPppoeServer>,
    val ipPools: List<RouterOsIpPool>,
    val interfaceLists: List<RouterOsInterfaceList>,
    val interfaceListMembers: List<RouterOsInterfaceListMember>,
    val firewallRules: List<RouterOsFirewallRule>,
)

/**
 * RouterOS provisioning uses a dedicated, normally validating HTTPS client. It is
 * intentionally separate from [MikrotikRouterOsAdapter], whose legacy polling path
 * retains its existing transport behavior.
 */
class RouterOsProvisioningAdapter(
    private val http: HttpClient = HttpClient.newBuilder().build(),
    private val requestTimeout: Duration = Duration.ofSeconds(15),
    private val clock: Clock = Clock.systemUTC(),
    private val allowInsecureHttpForTests: Boolean = false,
    private val stateStore: RouterOsProvisioningStateStore,
    private val afterMutationHttpSuccess: (PersistedRouterOsMutation) -> Unit = {},
) : ProvisioningAdapter {
    override val vendor: String = "MIKROTIK"
    private val mapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun discover(target: NasTarget): RouterOsNormalizedState = RouterOsNormalizedState(
        bridges = rows(target, "/interface/bridge").map { row ->
            RouterOsBridge(row.id(), row.required("name"), row.boolean("vlan-filtering"), row["comment"])
        },
        bridgePorts = rows(target, "/interface/bridge/port").map { row ->
            RouterOsBridgePort(
                row.id(),
                row.required("bridge"),
                row.required("interface"),
                row.int("pvid", 1),
                row.boolean("ingress-filtering"),
                row["frame-types"] ?: "admit-all",
                row["comment"],
            )
        },
        bridgeVlans = rows(target, "/interface/bridge/vlan").map { row ->
            RouterOsBridgeVlan(
                row.id(),
                row.required("bridge"),
                parseVlanSet(row["vlan-ids"]),
                csv(row["tagged"]),
                csv(row["untagged"]),
                csv(row["current-tagged"]),
                csv(row["current-untagged"]),
                row["comment"],
            )
        },
        vlanInterfaces = rows(target, "/interface/vlan").map { row ->
            RouterOsVlanInterface(
                row.id(),
                row.required("name"),
                row.required("interface"),
                row.int("vlan-id"),
                row["comment"],
            )
        },
        pppoeServers = rows(target, "/interface/pppoe-server/server").map { row ->
            RouterOsPppoeServer(
                row.id(),
                row.required("interface"),
                row.boolean("disabled"),
                row["service-name"].orEmpty(),
                parseVlanSet(row["pppoe-over-vlan-range"]),
                row["comment"],
            )
        },
        ipPools = rows(target, "/ip/pool").map { row ->
            RouterOsIpPool(row.id(), row.required("name"), row.required("ranges"), row["comment"])
        },
        interfaceLists = rows(target, "/interface/list").map { row ->
            RouterOsInterfaceList(row.id(), row.required("name"), row["comment"])
        },
        interfaceListMembers = rows(target, "/interface/list/member").map { row ->
            RouterOsInterfaceListMember(row.id(), row.required("list"), row.required("interface"), row["comment"])
        },
        firewallRules = rows(target, "/ip/firewall/filter").map { row ->
            RouterOsFirewallRule(
                row.id(),
                row.required("chain"),
                row.required("action"),
                row["in-interface-list"],
                row["out-interface-list"],
                row["comment"],
                row.boolean("disabled"),
            )
        },
    )

    override fun capabilityReport(target: NasTarget): DeviceCapabilityReport {
        val resource = rows(target, "/system/resource").singleOrNull()
            ?: throw RouterOsProvisioningException("ROUTEROS_FINGERPRINT_UNAVAILABLE")
        return DeviceCapabilityReport(
            targetId = target.nasId,
            fingerprint = DeviceFingerprint(
                vendor = resource["platform"] ?: "MikroTik",
                model = resource.required("board-name"),
                firmware = resource.required("version"),
                transport = "HTTPS_REST",
            ),
            capabilities = SUPPORTED_CAPABILITIES + "CERTIFICATION_PROVISIONAL",
            reportedAt = clock.instant(),
            operationClasses = SUPPORTED_OPERATIONS,
        )
    }

    override fun execute(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val deliveryKey = command.deliveryKey()
        stateStore.result(deliveryKey)?.let { return it }
        val result = try {
            requireCommandTarget(target, command)
            checkDeadline(command)
            checkFence(command)
            when (command.phase) {
                ProvisioningCommandPhase.PREFLIGHT -> preflight(target, command)
                ProvisioningCommandPhase.APPLY -> apply(target, command)
                ProvisioningCommandPhase.VERIFY -> verify(target, command)
                ProvisioningCommandPhase.ROLLBACK -> rollback(target, command)
            }
        } catch (failure: RouterOsCommandException) {
            failed(command, failure.code)
        } catch (failure: RouterOsHttpException) {
            failed(command, if (failure.status == 408) ProvisioningErrorCode.TIMEOUT else ProvisioningErrorCode.MANUAL_RECONCILIATION)
        } catch (failure: RouterOsProvisioningException) {
            val code = if (failure.message == "INSECURE_TRANSPORT") {
                ProvisioningErrorCode.INSECURE_TRANSPORT
            } else {
                ProvisioningErrorCode.MANUAL_RECONCILIATION
            }
            failed(command, code)
        } catch (_: java.net.http.HttpTimeoutException) {
            failed(command, ProvisioningErrorCode.TIMEOUT)
        } catch (_: Exception) {
            failed(command, ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
        stateStore.saveResult(deliveryKey, result)
        return stateStore.result(deliveryKey) ?: result
    }

    private fun preflight(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val desired = desired(command)
        val state = discover(target)
        validate(state, desired)
        val hash = stateHash(state)
        stateStore.saveSnapshot(stepKey(command), PersistedRouterOsSnapshot(hash, state))
        val observation = observation(state, matches = matchesDesired(state, desired))
        return success(
            command,
            preflight = ProvisioningPreflightSnapshot(
                capturedAt = clock.instant(),
                preconditionHash = hash,
                state = statePayload(state),
            ),
            verification = observation,
        )
    }

    private fun apply(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val desired = desired(command)
        val before = discover(target)
        validate(before, desired)
        val beforeHash = stateHash(before)
        val key = stepKey(command)
        var snapshot = stateStore.snapshot(key)
        if (snapshot == null) {
            requirePrecondition(command, beforeHash)
            snapshot = PersistedRouterOsSnapshot(beforeHash, before)
            stateStore.saveSnapshot(key, snapshot)
        } else {
            if (command.expectedPreconditionHash != snapshot.beforeHash) {
                throw RouterOsCommandException(ProvisioningErrorCode.STALE_PRECONDITION)
            }
            if (snapshot.afterHash != null && beforeHash == snapshot.afterHash) {
                val matches = matchesDesired(before, desired)
                if (!matches) throw RouterOsCommandException(ProvisioningErrorCode.VERIFICATION_MISMATCH)
                return success(
                    command,
                    preflight = ProvisioningPreflightSnapshot(clock.instant(), snapshot.beforeHash, statePayload(snapshot.before)),
                    apply = ProvisioningApplyResult(clock.instant(), changed = false, resultingStateHash = beforeHash),
                    verification = observation(before, matches = true),
                )
            }
            if (snapshot.mutations.isEmpty() && beforeHash != snapshot.beforeHash) {
                throw RouterOsCommandException(ProvisioningErrorCode.STALE_PRECONDITION)
            }
            reconcileJournalState(target, key, snapshot)
        }

        reconcile(target, desired, before, key)
        val after = discover(target)
        val afterHash = stateHash(after)
        val matches = matchesDesired(after, desired)
        if (!matches) throw RouterOsCommandException(ProvisioningErrorCode.VERIFICATION_MISMATCH)
        stateStore.markApplied(key, afterHash)
        snapshot = checkNotNull(stateStore.snapshot(key))
        return success(
            command,
            preflight = ProvisioningPreflightSnapshot(clock.instant(), beforeHash, statePayload(before)),
            apply = ProvisioningApplyResult(clock.instant(), snapshot.mutations.isNotEmpty(), afterHash),
            verification = observation(after, matches = true),
        )
    }

    private fun verify(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val desired = desired(command)
        val state = discover(target)
        val matches = matchesDesired(state, desired)
        if (!matches) throw RouterOsCommandException(ProvisioningErrorCode.VERIFICATION_MISMATCH)
        val hash = stateHash(state)
        return success(
            command,
            verification = observation(state, matches = true),
        )
    }

    private fun rollback(target: NasTarget, command: ProvisioningPlanStepCommand): ProvisioningStepResult {
        val snapshot = stateStore.snapshot(stepKey(command))
            ?: throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        val current = discover(target)
        val currentHash = stateHash(current)
        if (currentHash == snapshot.beforeHash) {
            return rollbackSuccess(command, snapshot.beforeHash)
        }
        requirePrecondition(command, currentHash)
        if (snapshot.afterHash != null && currentHash != snapshot.afterHash) {
            throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        }
        reconcileJournalState(target, stepKey(command), snapshot)
        val reconciled = stateStore.snapshot(stepKey(command))
            ?: throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        reconciled.mutations.sortedByDescending { it.order }.forEach { mutation -> compensateMutation(target, mutation) }
        val restored = discover(target)
        val restoredHash = stateHash(restored)
        if (restoredHash != snapshot.beforeHash) {
            throw RouterOsCommandException(ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
        return rollbackSuccess(command, restoredHash)
    }

    private fun reconcile(
        target: NasTarget,
        desired: RouterOsDesiredState,
        before: RouterOsNormalizedState,
        stepKey: String,
    ) {
        var bridge = before.bridges.singleOrNull { it.name == desired.bridge }
        if (bridge == null) {
            val created = createResource(
                target,
                stepKey,
                BRIDGES,
                mapOf("name" to desired.bridge, "vlan-filtering" to "no", "comment" to desired.comment("bridge")),
            )
            bridge = RouterOsBridge(created.id(), desired.bridge, false, created["comment"])
        }

        // Establish permissive bridge-port prerequisites before VLAN allowance. Restrictive
        // ingress/PVID/frame-type settings are deliberately applied only after the VLAN table.
        (desired.trunkPorts + desired.accessPorts).forEach { name ->
            val existing = rows(target, BRIDGE_PORTS).firstOrNull {
                it["bridge"] == desired.bridge && it["interface"] == name
            }
            if (existing == null) {
                createResource(
                    target,
                    stepKey,
                    BRIDGE_PORTS,
                    mapOf("bridge" to desired.bridge, "interface" to name, "comment" to desired.comment("port:$name")),
                )
            }
        }
        val bridgeVlanId = rows(target, BRIDGE_VLANS).firstOrNull {
            it["bridge"] == desired.bridge && desired.vlanId in parseVlanSet(it["vlan-ids"])
        }?.get(".id")
        ensureResource(
            target,
            BRIDGE_VLANS,
            bridgeVlanId,
            desired.comment("bridge-vlan:${desired.vlanId}"),
            mapOf(
                "bridge" to desired.bridge,
                "vlan-ids" to desired.vlanId.toString(),
                "tagged" to (desired.trunkPorts.sorted() + desired.bridge).joinToString(","),
                "untagged" to desired.accessPorts.sorted().joinToString(","),
            ),
            stepKey,
        )

        ensureResource(
            target,
            VLAN_INTERFACES,
            before.vlanInterfaces.firstOrNull { it.name == desired.vlanInterface }?.id,
            desired.comment("vlan:${desired.vlanId}"),
            mapOf("name" to desired.vlanInterface, "interface" to desired.vlanParent, "vlan-id" to desired.vlanId.toString()),
            stepKey,
        )
        ensureResource(
            target,
            IP_POOLS,
            before.ipPools.firstOrNull { it.name == desired.poolName }?.id,
            desired.comment("pool:${desired.vlanId}"),
            mapOf("name" to desired.poolName, "ranges" to desired.poolRanges),
            stepKey,
        )
        ensureResource(
            target,
            INTERFACE_LISTS,
            before.interfaceLists.firstOrNull { it.name == desired.interfaceList }?.id,
            desired.comment("list:customer"),
            mapOf("name" to desired.interfaceList),
            stepKey,
        )
        ensureResource(
            target,
            INTERFACE_LIST_MEMBERS,
            before.interfaceListMembers.firstOrNull {
                it.list == desired.interfaceList && it.interfaceName == desired.vlanInterface
            }?.id,
            desired.comment("list-member:${desired.vlanId}"),
            mapOf("list" to desired.interfaceList, "interface" to desired.vlanInterface),
            stepKey,
        )
        ensureResource(
            target,
            PPPOE_SERVERS,
            before.pppoeServers.firstOrNull { it.interfaceName == desired.pppoeInterface }?.id,
            desired.comment("pppoe:${desired.vlanId}"),
            mapOf(
                "interface" to desired.pppoeInterface,
                "service-name" to desired.pppoeServiceName,
                "pppoe-over-vlan-range" to desired.pppoeVlanRange.sorted().joinToString(","),
                "disabled" to "no",
            ),
            stepKey,
        )
        ensureResource(
            target,
            FIREWALL_FILTERS,
            before.firewallRules.firstOrNull { it.comment == desired.comment("firewall:deny-inter-vlan") }?.id,
            desired.comment("firewall:deny-inter-vlan"),
            mapOf(
                "chain" to desired.firewallChain,
                "action" to "drop",
                "in-interface-list" to desired.interfaceList,
                "out-interface-list" to desired.interfaceList,
                "disabled" to "no",
            ),
            stepKey,
        )

        desired.trunkPorts.forEach { name ->
            ensureResource(
                target,
                BRIDGE_PORTS,
                rows(target, BRIDGE_PORTS).single { it["bridge"] == desired.bridge && it["interface"] == name }.id(),
                desired.comment("port:$name"),
                mapOf(
                    "bridge" to desired.bridge,
                    "interface" to name,
                    "pvid" to "1",
                    "ingress-filtering" to "yes",
                    "frame-types" to "admit-only-vlan-tagged",
                ),
                stepKey,
            )
        }
        desired.accessPorts.forEach { name ->
            ensureResource(
                target,
                BRIDGE_PORTS,
                rows(target, BRIDGE_PORTS).single { it["bridge"] == desired.bridge && it["interface"] == name }.id(),
                desired.comment("port:$name"),
                mapOf(
                    "bridge" to desired.bridge,
                    "interface" to name,
                    "pvid" to desired.vlanId.toString(),
                    "ingress-filtering" to "yes",
                    "frame-types" to "admit-only-untagged-and-priority-tagged",
                ),
                stepKey,
            )
        }

        if (!bridge.vlanFiltering) {
            val beforeRow = getRow(target, BRIDGES, bridge.id)
                ?: throw RouterOsCommandException(ProvisioningErrorCode.STALE_PRECONDITION)
            updateResource(
                target,
                stepKey,
                BRIDGES,
                bridge.id,
                beforeRow,
                mapOf("vlan-filtering" to "yes"),
                bridge.comment,
            )
        }
    }

    private fun ensureResource(
        target: NasTarget,
        endpoint: String,
        existingId: String?,
        owner: String,
        desired: Map<String, String>,
        stepKey: String,
    ) {
        if (existingId == null) {
            createResource(target, stepKey, endpoint, desired + ("comment" to owner))
            return
        }
        val current = getRow(target, endpoint, existingId)
            ?: throw RouterOsCommandException(ProvisioningErrorCode.STALE_PRECONDITION)
        val changes = desired.filter { (key, value) -> current[key].orEmpty() != value }
        if (changes.isEmpty()) return
        if (current["comment"] != owner) throw RouterOsCommandException(ProvisioningErrorCode.PROTECTED_RESOURCE)
        updateResource(target, stepKey, endpoint, existingId, current, changes, owner)
    }

    private fun compensateMutation(target: NasTarget, mutation: PersistedRouterOsMutation) {
        when (mutation.kind) {
            MUTATION_CREATED -> {
                val current = locateCreatedResource(target, mutation)
                if (current == null) return
                if (current["comment"] != mutation.owner ||
                    !matchesLocator(current, mutation.locator)
                ) {
                    throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                }
                delete(target, mutation.endpoint, current.id())
            }
            MUTATION_UPDATED -> {
                val id = mutation.id ?: throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                val current = getRow(target, mutation.endpoint, id)
                if (current == null || current["comment"] != mutation.owner) {
                    throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                }
                val mutable = mutableRow(current)
                if (mutable == mutation.before && mutation.status == PersistedRouterOsMutation.MUTATION_PLANNED) return
                val expected = if (mutation.status == PersistedRouterOsMutation.MUTATION_APPLIED) {
                    mutation.after
                } else {
                    mutation.expectedAfter
                }
                if (mutable != expected) {
                    throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                }
                update(target, mutation.endpoint, id, mutation.before)
            }
            else -> throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        }
    }

    private fun createResource(
        target: NasTarget,
        stepKey: String,
        endpoint: String,
        values: Map<String, String>,
    ): Map<String, String> {
        val owner = values["comment"] ?: throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED)
        val planned = stateStore.planMutation(
            stepKey,
            PersistedRouterOsMutation(
                mutationId = mutationId(stepKey, MUTATION_CREATED, endpoint, owner, values),
                kind = MUTATION_CREATED,
                endpoint = endpoint,
                owner = owner,
                locator = values.toSortedMap(),
                expectedAfter = mutableRow(values),
            ),
        )
        locateCreatedResource(target, planned)?.let { current ->
            if (!matchesLocator(current, planned.locator)) {
                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
            }
            if (planned.status == PersistedRouterOsMutation.MUTATION_APPLIED && mutableRow(current) != planned.after) {
                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
            }
            if (planned.status == PersistedRouterOsMutation.MUTATION_PLANNED) {
                stateStore.markMutationApplied(stepKey, planned.mutationId, current.id(), mutableRow(current))
            }
            return current
        }
        if (planned.status == PersistedRouterOsMutation.MUTATION_APPLIED) {
            throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        }
        val created = create(target, endpoint, values)
        afterMutationHttpSuccess(planned)
        val id = created.id()
        val readback = getRow(target, endpoint, id)
            ?: throw RouterOsCommandException(ProvisioningErrorCode.MANUAL_RECONCILIATION)
        if (!matchesLocator(readback, planned.locator)) {
            throw RouterOsCommandException(ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
        stateStore.markMutationApplied(stepKey, planned.mutationId, id, mutableRow(readback))
        return readback
    }

    private fun updateResource(
        target: NasTarget,
        stepKey: String,
        endpoint: String,
        id: String,
        before: Map<String, String>,
        changes: Map<String, String>,
        owner: String?,
    ) {
        val beforeMutable = mutableRow(before)
        val expectedAfter = (beforeMutable + changes).toSortedMap()
        val planned = stateStore.planMutation(
            stepKey,
            PersistedRouterOsMutation(
                mutationId = mutationId(stepKey, MUTATION_UPDATED, endpoint, id, expectedAfter),
                kind = MUTATION_UPDATED,
                endpoint = endpoint,
                id = id,
                owner = owner.orEmpty(),
                locator = mapOf(".id" to id),
                before = beforeMutable,
                expectedAfter = expectedAfter,
            ),
        )
        val current = getRow(target, endpoint, id)
            ?: throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        val currentMutable = mutableRow(current)
        if (planned.status == PersistedRouterOsMutation.MUTATION_APPLIED) {
            if (currentMutable != planned.after) throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
            return
        }
        if (currentMutable == planned.expectedAfter) {
            stateStore.markMutationApplied(stepKey, planned.mutationId, id, currentMutable)
            return
        }
        if (currentMutable != planned.before) {
            throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        }
        update(target, endpoint, id, changes)
        afterMutationHttpSuccess(planned)
        val readback = getRow(target, endpoint, id)
            ?: throw RouterOsCommandException(ProvisioningErrorCode.MANUAL_RECONCILIATION)
        val after = mutableRow(readback)
        if (after != planned.expectedAfter) {
            throw RouterOsCommandException(ProvisioningErrorCode.MANUAL_RECONCILIATION)
        }
        stateStore.markMutationApplied(stepKey, planned.mutationId, id, after)
    }

    private fun reconcileJournalState(target: NasTarget, stepKey: String, snapshot: PersistedRouterOsSnapshot) {
        snapshot.mutations
            .groupBy { it.endpoint to (it.id ?: "owner:${it.owner}") }
            .map { (_, mutations) -> mutations.last() }
            .forEach { mutation ->
                when (mutation.kind) {
                    MUTATION_CREATED -> {
                        val current = locateCreatedResource(target, mutation)
                        if (current == null) {
                            if (mutation.status == PersistedRouterOsMutation.MUTATION_APPLIED) {
                                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                            }
                        } else {
                            if (!matchesLocator(current, mutation.locator)) {
                                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                            }
                            val mutable = mutableRow(current)
                            if (mutation.status == PersistedRouterOsMutation.MUTATION_APPLIED && mutable != mutation.after) {
                                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                            }
                            if (mutation.status == PersistedRouterOsMutation.MUTATION_PLANNED) {
                                stateStore.markMutationApplied(stepKey, mutation.mutationId, current.id(), mutable)
                            }
                        }
                    }
                    MUTATION_UPDATED -> {
                        val id = mutation.id ?: throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                        val current = getRow(target, mutation.endpoint, id)
                            ?: throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                        val mutable = mutableRow(current)
                        when {
                            mutation.status == PersistedRouterOsMutation.MUTATION_APPLIED && mutable != mutation.after ->
                                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                            mutation.status == PersistedRouterOsMutation.MUTATION_PLANNED && mutable == mutation.expectedAfter ->
                                stateStore.markMutationApplied(stepKey, mutation.mutationId, id, mutable)
                            mutation.status == PersistedRouterOsMutation.MUTATION_PLANNED && mutable == mutation.before -> Unit
                            mutation.status == PersistedRouterOsMutation.MUTATION_PLANNED ->
                                throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                        }
                    }
                    else -> throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
                }
            }
    }

    private fun locateCreatedResource(target: NasTarget, mutation: PersistedRouterOsMutation): Map<String, String>? {
        mutation.id?.let { id -> getRow(target, mutation.endpoint, id)?.let { return it } }
        val owned = rows(target, mutation.endpoint).filter { it["comment"] == mutation.owner }
        if (owned.size > 1) throw RouterOsCommandException(ProvisioningErrorCode.ROLLBACK_CONFLICT)
        return owned.singleOrNull()
    }

    private fun matchesLocator(row: Map<String, String>, locator: Map<String, String>): Boolean =
        locator.all { (key, value) -> row[key].orEmpty() == value }

    private fun mutationId(
        stepKey: String,
        kind: String,
        endpoint: String,
        identity: String,
        expected: Map<String, String>,
    ): String {
        val canonical = "$stepKey|$kind|$endpoint|$identity|" + expected.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun mutableRow(row: Map<String, String>): Map<String, String> = row
        .filterKeys { it != ".id" && it !in DYNAMIC_ROW_FIELDS }
        .toSortedMap()

    private fun validate(state: RouterOsNormalizedState, desired: RouterOsDesiredState) {
        if (desired.vlanId !in 2..4094 || desired.trunkPorts.isEmpty() || desired.accessPorts.isEmpty()) {
            throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED)
        }
        if ((desired.trunkPorts intersect desired.accessPorts).isNotEmpty()) {
            throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED)
        }
        if (desired.vlanId in desired.protectedVlanIds ||
            ((desired.trunkPorts + desired.accessPorts) intersect desired.protectedInterfaces).isNotEmpty()
        ) {
            throw RouterOsCommandException(ProvisioningErrorCode.PROTECTED_RESOURCE)
        }
        val overlaps = state.vlanInterfaces.any { vlan ->
            state.pppoeServers.any { server -> vlan.interfaceName == server.interfaceName && vlan.vlanId in server.vlanRange }
        }
        val desiredOverlap = desired.vlanParent == desired.pppoeInterface && desired.vlanId in desired.pppoeVlanRange
        if (overlaps || desiredOverlap || desired.pppoeVlanRange.any { it !in 1..4094 }) {
            throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED)
        }

        val bridge = state.bridges.singleOrNull { it.name == desired.bridge }
        if (bridge == null || !bridge.vlanFiltering) {
            val modelled = desired.trunkPorts + desired.accessPorts
            val unmodelledPorts = state.bridgePorts.any { it.bridge == desired.bridge && it.interfaceName !in modelled }
            val protectedModelComplete = desired.protectedInterfaces.isNotEmpty() && desired.protectedVlanIds.isNotEmpty()
            val ownedWhenPresent = bridge == null || bridge.comment == desired.comment("bridge")
            if (!ownedWhenPresent || !desired.managementPathProven || !protectedModelComplete || unmodelledPorts) {
                throw RouterOsCommandException(ProvisioningErrorCode.MANAGEMENT_PATH_UNPROVEN)
            }
        }
        rejectDuplicate(state.bridges.count { it.name == desired.bridge })
        desired.trunkPorts.forEach { name ->
            val matches = state.bridgePorts.filter { it.bridge == desired.bridge && it.interfaceName == name }
            rejectDuplicate(matches.size)
            matches.singleOrNull()?.let { port ->
                requireOwnedIfChanged(
                    port.comment,
                    desired.comment("port:$name"),
                    port.pvid == 1 && port.ingressFiltering && port.frameTypes == "admit-only-vlan-tagged",
                )
            }
        }
        desired.accessPorts.forEach { name ->
            val matches = state.bridgePorts.filter { it.bridge == desired.bridge && it.interfaceName == name }
            rejectDuplicate(matches.size)
            matches.singleOrNull()?.let { port ->
                requireOwnedIfChanged(
                    port.comment,
                    desired.comment("port:$name"),
                    port.pvid == desired.vlanId && port.ingressFiltering &&
                        port.frameTypes == "admit-only-untagged-and-priority-tagged",
                )
            }
        }
        val bridgeVlans = state.bridgeVlans.filter { it.bridge == desired.bridge && desired.vlanId in it.vlanIds }
        rejectDuplicate(bridgeVlans.size)
        bridgeVlans.singleOrNull()?.let { vlan ->
            requireOwnedIfChanged(
                vlan.comment,
                desired.comment("bridge-vlan:${desired.vlanId}"),
                vlan.vlanIds == setOf(desired.vlanId) &&
                    vlan.tagged == desired.trunkPorts + desired.bridge && vlan.untagged == desired.accessPorts,
            )
        }
        val vlanInterfaces = state.vlanInterfaces.filter { it.name == desired.vlanInterface }
        rejectDuplicate(vlanInterfaces.size)
        vlanInterfaces.singleOrNull()?.let { vlan ->
            requireOwnedIfChanged(
                vlan.comment,
                desired.comment("vlan:${desired.vlanId}"),
                vlan.interfaceName == desired.vlanParent && vlan.vlanId == desired.vlanId,
            )
        }
        val servers = state.pppoeServers.filter { it.interfaceName == desired.pppoeInterface }
        rejectDuplicate(servers.size)
        servers.singleOrNull()?.let { server ->
            requireOwnedIfChanged(
                server.comment,
                desired.comment("pppoe:${desired.vlanId}"),
                !server.disabled && server.serviceName == desired.pppoeServiceName &&
                    server.vlanRange == desired.pppoeVlanRange,
            )
        }
        val pools = state.ipPools.filter { it.name == desired.poolName }
        rejectDuplicate(pools.size)
        pools.singleOrNull()?.let { pool ->
            requireOwnedIfChanged(
                pool.comment,
                desired.comment("pool:${desired.vlanId}"),
                pool.ranges == desired.poolRanges,
            )
        }
        val lists = state.interfaceLists.filter { it.name == desired.interfaceList }
        rejectDuplicate(lists.size)
        val members = state.interfaceListMembers.filter {
            it.list == desired.interfaceList && it.interfaceName == desired.vlanInterface
        }
        rejectDuplicate(members.size)
        val firewalls = state.firewallRules.filter { it.comment == desired.comment("firewall:deny-inter-vlan") }
        rejectDuplicate(firewalls.size)
        firewalls.singleOrNull()?.let { firewall ->
            requireOwnedIfChanged(
                firewall.comment,
                desired.comment("firewall:deny-inter-vlan"),
                !firewall.disabled && firewall.chain == desired.firewallChain && firewall.action == "drop" &&
                    firewall.inInterfaceList == desired.interfaceList && firewall.outInterfaceList == desired.interfaceList,
            )
        }
    }

    private fun rejectDuplicate(count: Int) {
        if (count > 1) throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED)
    }

    private fun requireOwnedIfChanged(actualOwner: String?, expectedOwner: String, alreadyDesired: Boolean) {
        if (!alreadyDesired && actualOwner != expectedOwner) {
            throw RouterOsCommandException(ProvisioningErrorCode.PROTECTED_RESOURCE)
        }
    }

    private fun matchesDesired(state: RouterOsNormalizedState, desired: RouterOsDesiredState): Boolean {
        val bridge = state.bridges.singleOrNull { it.name == desired.bridge } ?: return false
        if (!bridge.vlanFiltering) return false
        val ports = state.bridgePorts.filter { it.bridge == desired.bridge }.associateBy { it.interfaceName }
        if (desired.trunkPorts.any { ports[it]?.let { p -> p.ingressFiltering && p.frameTypes == "admit-only-vlan-tagged" } != true }) return false
        if (desired.accessPorts.any {
                ports[it]?.let { p -> p.pvid == desired.vlanId && p.ingressFiltering && p.frameTypes == "admit-only-untagged-and-priority-tagged" } != true
            }) return false
        val bridgeVlan = state.bridgeVlans.singleOrNull { desired.vlanId in it.vlanIds && it.bridge == desired.bridge } ?: return false
        if (bridgeVlan.currentTagged != desired.trunkPorts + desired.bridge || bridgeVlan.currentUntagged != desired.accessPorts) return false
        if (state.vlanInterfaces.none { it.name == desired.vlanInterface && it.interfaceName == desired.vlanParent && it.vlanId == desired.vlanId }) return false
        if (state.pppoeServers.none {
                !it.disabled && it.interfaceName == desired.pppoeInterface &&
                    it.serviceName == desired.pppoeServiceName && it.vlanRange == desired.pppoeVlanRange
            }) return false
        if (state.ipPools.none { it.name == desired.poolName && it.ranges == desired.poolRanges }) return false
        if (state.interfaceLists.none { it.name == desired.interfaceList }) return false
        if (state.interfaceListMembers.none { it.list == desired.interfaceList && it.interfaceName == desired.vlanInterface }) return false
        return state.firewallRules.any {
            !it.disabled && it.chain == desired.firewallChain && it.action == "drop" &&
                it.inInterfaceList == desired.interfaceList && it.outInterfaceList == desired.interfaceList
        }
    }

    private fun desired(command: ProvisioningPlanStepCommand): RouterOsDesiredState {
        if (command.operationClass !in SUPPORTED_OPERATIONS) {
            throw RouterOsCommandException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        }
        val payload = command.payload.values
        fun required(value: String?) = value?.takeIf(String::isNotBlank)
            ?: throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED)
        return RouterOsDesiredState(
            tenant = required(payload.tenantId),
            intent = required(payload.intentId),
            bridge = required(payload.bridge),
            vlanId = required(payload.vlanId).toIntOrNull()
                ?: throw RouterOsCommandException(ProvisioningErrorCode.VALIDATION_FAILED),
            trunkPorts = csv(payload.trunkPorts),
            accessPorts = csv(payload.accessPorts),
            vlanInterface = required(payload.vlanInterface),
            vlanParent = required(payload.vlanParent),
            pppoeInterface = required(payload.pppoeInterface),
            pppoeServiceName = required(payload.pppoeServiceName),
            pppoeVlanRange = parseVlanSet(payload.pppoeVlanRange),
            poolName = required(payload.poolName),
            poolRanges = required(payload.poolRanges),
            interfaceList = required(payload.interfaceList),
            firewallChain = required(payload.firewallChain),
            managementPathProven = payload.managementPathProven.toBoolean(),
            protectedInterfaces = csv(payload.protectedInterfaces),
            protectedVlanIds = parseVlanSet(payload.protectedVlanIds),
        )
    }

    private fun checkFence(command: ProvisioningPlanStepCommand) {
        if (!stateStore.acceptFence(command.target.deviceId, command.fencingEpoch)) {
            throw RouterOsCommandException(ProvisioningErrorCode.STALE_PRECONDITION)
        }
    }

    private fun requireCommandTarget(target: NasTarget, command: ProvisioningPlanStepCommand) {
        if (target.nasId != command.target.deviceId || target.vendor.uppercase() != "MIKROTIK") {
            throw RouterOsCommandException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        }
        if (command.target.transport.uppercase() !in setOf("HTTPS_REST", "REST")) {
            throw RouterOsCommandException(ProvisioningErrorCode.UNSUPPORTED_CAPABILITY)
        }
    }

    private fun checkDeadline(command: ProvisioningPlanStepCommand) {
        if (!clock.instant().isBefore(command.deadline)) throw RouterOsCommandException(ProvisioningErrorCode.TIMEOUT)
    }

    private fun requirePrecondition(command: ProvisioningPlanStepCommand, currentHash: String) {
        if (command.expectedPreconditionHash == null || command.expectedPreconditionHash != currentHash) {
            throw RouterOsCommandException(ProvisioningErrorCode.STALE_PRECONDITION)
        }
    }

    private fun create(target: NasTarget, endpoint: String, values: Map<String, String>): Map<String, String> =
        mapper.readValue(send(target, "PUT", endpoint, mapper.writeValueAsString(values)), MAP_TYPE)

    private fun update(target: NasTarget, endpoint: String, id: String, values: Map<String, String>) {
        send(target, "PATCH", "$endpoint/$id", mapper.writeValueAsString(values))
    }

    private fun delete(target: NasTarget, endpoint: String, id: String) {
        try {
            send(target, "DELETE", "$endpoint/$id")
        } catch (failure: RouterOsHttpException) {
            if (failure.status != 404) throw failure
        }
    }

    private fun getRow(target: NasTarget, endpoint: String, id: String): Map<String, String>? = try {
        val body = send(target, "GET", "$endpoint/$id")
        if (body.trimStart().startsWith("[")) {
            mapper.readValue(body, LIST_TYPE).singleOrNull()
        } else {
            mapper.readValue(body, MAP_TYPE)
        }
    } catch (failure: RouterOsHttpException) {
        if (failure.status == 404) null else throw failure
    }

    private fun stateHash(state: RouterOsNormalizedState): String {
        val canonical = buildList {
            state.bridges.forEach { add("bridge|${it.id}|${it.name}|${it.vlanFiltering}|${it.comment}") }
            state.bridgePorts.forEach { add("port|${it.id}|${it.bridge}|${it.interfaceName}|${it.pvid}|${it.ingressFiltering}|${it.frameTypes}|${it.comment}") }
            state.bridgeVlans.forEach { add("bridge-vlan|${it.id}|${it.bridge}|${it.vlanIds.sorted()}|${it.tagged.sorted()}|${it.untagged.sorted()}|${it.currentTagged.sorted()}|${it.currentUntagged.sorted()}|${it.comment}") }
            state.vlanInterfaces.forEach { add("vlan|${it.id}|${it.name}|${it.interfaceName}|${it.vlanId}|${it.comment}") }
            state.pppoeServers.forEach { add("pppoe|${it.id}|${it.interfaceName}|${it.disabled}|${it.serviceName}|${it.vlanRange.sorted()}|${it.comment}") }
            state.ipPools.forEach { add("pool|${it.id}|${it.name}|${it.ranges}|${it.comment}") }
            state.interfaceLists.forEach { add("list|${it.id}|${it.name}|${it.comment}") }
            state.interfaceListMembers.forEach { add("member|${it.id}|${it.list}|${it.interfaceName}|${it.comment}") }
            state.firewallRules.forEach { add("firewall|${it.id}|${it.chain}|${it.action}|${it.inInterfaceList}|${it.outInterfaceList}|${it.comment}|${it.disabled}") }
        }.sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun statePayload(state: RouterOsNormalizedState) = ProvisioningResultState(
        managedResourceCount = ownedIds(state).size,
    )

    private fun ownedIds(state: RouterOsNormalizedState): List<String> = buildList {
        state.bridges.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.bridgePorts.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.bridgeVlans.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.vlanInterfaces.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.pppoeServers.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.ipPools.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.interfaceLists.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.interfaceListMembers.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
        state.firewallRules.filter { it.comment?.startsWith("ftth:") == true }.forEach { add(it.id) }
    }

    private fun observation(state: RouterOsNormalizedState, matches: Boolean) = ProvisioningVerificationObservation(
        observedAt = clock.instant(),
        matchesExpected = matches,
        stateHash = stateHash(state),
        state = statePayload(state),
    )

    private fun success(
        command: ProvisioningPlanStepCommand,
        preflight: ProvisioningPreflightSnapshot? = null,
        apply: ProvisioningApplyResult? = null,
        verification: ProvisioningVerificationObservation,
        rollback: ProvisioningRollbackResult? = null,
    ) = ProvisioningStepResult(
        planId = command.planId,
        revision = command.revision,
        stepId = command.stepId,
        attemptId = command.attemptId,
        targetId = command.target.deviceId,
        operationClass = command.operationClass,
        idempotencyKey = command.idempotencyKey,
        fencingEpoch = command.fencingEpoch,
        phase = command.phase,
        success = true,
        completedAt = clock.instant(),
        preflight = preflight,
        apply = apply,
        verification = verification,
        rollback = rollback,
    )

    private fun rollbackSuccess(command: ProvisioningPlanStepCommand, hash: String): ProvisioningStepResult {
        val state = discoverForRollbackResult(command, hash)
        return ProvisioningStepResult(
            planId = command.planId,
            revision = command.revision,
            stepId = command.stepId,
            attemptId = command.attemptId,
            targetId = command.target.deviceId,
            operationClass = command.operationClass,
            idempotencyKey = command.idempotencyKey,
            fencingEpoch = command.fencingEpoch,
            phase = command.phase,
            success = true,
            completedAt = clock.instant(),
            verification = ProvisioningVerificationObservation(clock.instant(), true, hash, state),
            rollback = ProvisioningRollbackResult(clock.instant(), true, hash),
        )
    }

    private fun discoverForRollbackResult(command: ProvisioningPlanStepCommand, hash: String) =
        ProvisioningResultState()

    private fun failed(command: ProvisioningPlanStepCommand, code: ProvisioningErrorCode) = ProvisioningStepResult(
        planId = command.planId,
        revision = command.revision,
        stepId = command.stepId,
        attemptId = command.attemptId,
        targetId = command.target.deviceId,
        operationClass = command.operationClass,
        idempotencyKey = command.idempotencyKey,
        fencingEpoch = command.fencingEpoch,
        phase = command.phase,
        success = false,
        completedAt = clock.instant(),
        errorCode = code,
    )

    private fun stepKey(command: ProvisioningPlanStepCommand) = "${command.planId}:${command.revision}:${command.stepId}"

    private fun rows(target: NasTarget, path: String): List<Map<String, String>> {
        val response = send(target, "GET", path)
        return mapper.readValue(response, object : TypeReference<List<Map<String, String>>>() {})
    }

    private fun send(target: NasTarget, method: String, path: String, body: String? = null): String {
        val host = target.host?.takeIf(String::isNotBlank)
            ?: throw RouterOsProvisioningException("ROUTEROS_HOST_REQUIRED")
        if (!target.apiUseTls && !allowInsecureHttpForTests) {
            throw RouterOsProvisioningException("INSECURE_TRANSPORT")
        }
        val username = target.apiUsername?.takeIf(String::isNotBlank)
            ?: throw RouterOsProvisioningException("ROUTEROS_USERNAME_REQUIRED")
        val secret = target.apiSecret ?: throw RouterOsProvisioningException("ROUTEROS_CREDENTIAL_REQUIRED")
        val scheme = if (target.apiUseTls) "https" else "http"
        val port = target.apiPort ?: if (target.apiUseTls) 443 else 80
        val auth = Base64.getEncoder().encodeToString("$username:$secret".toByteArray(StandardCharsets.UTF_8))
        val builder = HttpRequest.newBuilder(URI.create("$scheme://$host:$port/rest$path"))
            .timeout(requestTimeout)
            .header("Authorization", "Basic $auth")
            .header("Accept", "application/json")
        val publisher = body?.let {
            builder.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(it, StandardCharsets.UTF_8)
        } ?: HttpRequest.BodyPublishers.noBody()
        val response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw RouterOsHttpException(response.statusCode(), method, path)
        }
        return response.body()
    }

    private fun Map<String, String>.id(): String = required(".id")
    private fun Map<String, String>.required(key: String): String = this[key]?.takeIf(String::isNotBlank)
        ?: throw RouterOsProvisioningException("ROUTEROS_FIELD_MISSING:$key")
    private fun Map<String, String>.int(key: String, default: Int? = null): Int =
        this[key]?.toIntOrNull() ?: default ?: throw RouterOsProvisioningException("ROUTEROS_FIELD_INVALID:$key")
    private fun Map<String, String>.boolean(key: String): Boolean = this[key].equals("yes", true) ||
        this[key].equals("true", true)

    companion object {
        private const val BRIDGES = "/interface/bridge"
        private const val BRIDGE_PORTS = "/interface/bridge/port"
        private const val BRIDGE_VLANS = "/interface/bridge/vlan"
        private const val VLAN_INTERFACES = "/interface/vlan"
        private const val PPPOE_SERVERS = "/interface/pppoe-server/server"
        private const val IP_POOLS = "/ip/pool"
        private const val INTERFACE_LISTS = "/interface/list"
        private const val INTERFACE_LIST_MEMBERS = "/interface/list/member"
        private const val FIREWALL_FILTERS = "/ip/firewall/filter"
        private const val MUTATION_CREATED = "CREATED"
        private const val MUTATION_UPDATED = "UPDATED"
        private val DYNAMIC_ROW_FIELDS = setOf(
            "current-tagged",
            "current-untagged",
            "dynamic",
            "invalid",
            "running",
            "actual-interface",
        )
        private val MAP_TYPE = object : TypeReference<Map<String, String>>() {}
        private val LIST_TYPE = object : TypeReference<List<Map<String, String>>>() {}
        private val SUPPORTED_OPERATIONS = setOf(
            "ENSURE_TAGGED_VLAN",
            "ENSURE_ACCESS_PORT",
            "ENSURE_PPPOE_TERMINATION",
            "VERIFY_STATE",
        )
        val SUPPORTED_CAPABILITIES = setOf(
            "SINGLE_TAG_802_1Q",
            "BRIDGE_VLAN_FILTERING",
            "PPPOE_TERMINATION",
            "INTERFACE_LIST",
            "FIREWALL_FILTER",
        )

        internal fun csv(value: String?): Set<String> = value.orEmpty().split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()

        internal fun parseVlanSet(value: String?): Set<Int> = buildSet {
            value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).forEach { token ->
                val bounds = token.split('-', limit = 2).map(String::trim)
                if (bounds.size == 1) {
                    bounds.single().toIntOrNull()?.let(::add)
                        ?: throw RouterOsProvisioningException("VLAN_RANGE_INVALID:$token")
                } else {
                    val start = bounds[0].toIntOrNull()
                    val end = bounds[1].toIntOrNull()
                    if (start == null || end == null || start > end) {
                        throw RouterOsProvisioningException("VLAN_RANGE_INVALID:$token")
                    }
                    addAll(start..end)
                }
            }
        }
    }
}

open class RouterOsProvisioningException(message: String) : RuntimeException(message)
class RouterOsHttpException(val status: Int, method: String, path: String) :
    RouterOsProvisioningException("ROUTEROS_HTTP_$status:$method:$path")
private class RouterOsCommandException(val code: ProvisioningErrorCode) : RuntimeException(code.name)

private data class RouterOsDesiredState(
    val tenant: String,
    val intent: String,
    val bridge: String,
    val vlanId: Int,
    val trunkPorts: Set<String>,
    val accessPorts: Set<String>,
    val vlanInterface: String,
    val vlanParent: String,
    val pppoeInterface: String,
    val pppoeServiceName: String,
    val pppoeVlanRange: Set<Int>,
    val poolName: String,
    val poolRanges: String,
    val interfaceList: String,
    val firewallChain: String,
    val managementPathProven: Boolean,
    val protectedInterfaces: Set<String>,
    val protectedVlanIds: Set<Int>,
) {
    fun comment(resource: String): String = "ftth:$tenant:$intent:$resource"
}
