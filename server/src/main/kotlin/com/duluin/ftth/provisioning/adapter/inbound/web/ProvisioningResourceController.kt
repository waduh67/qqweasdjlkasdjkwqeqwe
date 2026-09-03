package com.duluin.ftth.provisioning.adapter.inbound.web

import com.duluin.ftth.provisioning.application.service.ProvisioningResourceService
import com.duluin.ftth.provisioning.application.service.ProvisioningManagementEvidenceService
import com.duluin.ftth.provisioning.application.service.RecordManagementEvidenceCommand
import com.duluin.ftth.provisioning.domain.model.AdministrativeStatus
import com.duluin.ftth.provisioning.domain.model.DeviceKind
import com.duluin.ftth.provisioning.domain.model.DeviceReference
import com.duluin.ftth.provisioning.domain.model.InterfaceRole
import com.duluin.ftth.provisioning.domain.model.ManagedNodeRole
import com.duluin.ftth.provisioning.domain.model.TopologyReference
import com.duluin.ftth.provisioning.domain.model.TopologyReferenceKind
import com.duluin.ftth.provisioning.domain.model.VlanRange
import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.domain.policy.ManagementEvidenceSourceType
import com.duluin.ftth.provisioning.domain.policy.ProtectedManagementResources
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/provisioning")
class ProvisioningResourceController(
    private val resources: ProvisioningResourceService,
    private val managementEvidence: ProvisioningManagementEvidenceService,
) {
    @GetMapping("/topology")
    @PreAuthorize("@authz.can('provisioning.segment.view')")
    fun topology() = resources.topology()

    @PostMapping("/topology/nodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun createNode(@RequestBody request: NodeRequest) = resources.createNode(
        request.name, request.role, request.reference(), request.status,
    )

    @PutMapping("/topology/nodes/{id}")
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun updateNode(@PathVariable id: UUID, @RequestBody request: NodeRequest) = resources.updateNode(
        id, request.requiredRevision(), request.name, request.role, request.reference(), request.status,
    )

    @PostMapping("/topology/interfaces")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun createInterface(@RequestBody request: InterfaceRequest) = resources.createInterface(
        request.nodeId, request.name, request.role, request.reference(), request.status,
    )

    @PutMapping("/topology/interfaces/{id}")
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun updateInterface(@PathVariable id: UUID, @RequestBody request: InterfaceRequest) = resources.updateInterface(
        id, request.requiredRevision(), request.nodeId, request.name, request.role, request.reference(), request.status,
    )

    @PostMapping("/topology/links")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun createLink(@RequestBody request: LinkRequest) =
        resources.createLink(request.interfaceAId, request.interfaceZId, request.status)

    @PutMapping("/topology/links/{id}")
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun updateLink(@PathVariable id: UUID, @RequestBody request: LinkRequest) =
        resources.updateLink(id, request.requiredRevision(), request.status)

    @DeleteMapping("/topology/{type}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun deleteTopology(@PathVariable type: String, @PathVariable id: UUID, @RequestParam revision: Int) =
        resources.deleteTopology(type.uppercase(), id, revision)

    @GetMapping("/vlan-pools")
    @PreAuthorize("@authz.can('provisioning.segment.view')")
    fun pools() = resources.pools()

    @PostMapping("/vlan-pools")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun createPool(@RequestBody request: PoolRequest) =
        resources.createPool(request.name, request.vlanStart, request.vlanEnd, request.reservedRanges())

    @PutMapping("/vlan-pools/{id}")
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun updatePool(@PathVariable id: UUID, @RequestBody request: PoolRequest) =
        resources.updatePool(id, request.requiredRevision(), request.name, request.vlanStart, request.vlanEnd, request.reservedRanges())

    @DeleteMapping("/vlan-pools/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun deletePool(@PathVariable id: UUID, @RequestParam revision: Int) = resources.deletePool(id, revision)

    @GetMapping("/segment-profiles")
    @PreAuthorize("@authz.can('provisioning.segment.view')")
    fun profiles() = resources.profiles()

    @PostMapping("/segment-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun createProfile(@RequestBody request: ProfileRequest) = resources.createProfile(request.name, request.poolId)

    @PutMapping("/segment-profiles/{id}")
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun updateProfile(@PathVariable id: UUID, @RequestBody request: ProfileRequest) =
        resources.updateProfile(id, request.requiredRevision(), request.name, request.poolId)

    @DeleteMapping("/segment-profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun deleteProfile(@PathVariable id: UUID, @RequestParam revision: Int) = resources.deleteProfile(id, revision)

    @GetMapping("/intents")
    @PreAuthorize("@authz.can('provisioning.segment.view')")
    fun intents() = resources.intents()

    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun createIntent(@RequestBody request: IntentRequest) =
        resources.createIntent(request.subscriptionId, request.segmentProfileId, request.dedicatedVlanId)

    @PutMapping("/intents/{id}")
    @PreAuthorize("@authz.can('provisioning.segment.manage')")
    fun updateIntent(@PathVariable id: UUID, @RequestBody request: IntentRequest) =
        resources.updateIntent(id, request.requiredRevision(), request.segmentProfileId, request.status)

    @PutMapping("/management-protections/{deviceKind}/{deviceId}")
    @PreAuthorize("@authz.isPlatformAdmin()")
    fun configureProtection(
        @PathVariable deviceKind: DeviceKind,
        @PathVariable deviceId: UUID,
        @RequestBody request: ManagementProtectionRequest,
    ) = managementEvidence.record(request.toCommand(deviceKind, deviceId), request.requiredRevision())
}

data class NodeRequest(
    val revision: Int? = null,
    val name: String,
    val role: ManagedNodeRole,
    val referenceKind: TopologyReferenceKind?,
    val referenceId: UUID?,
    val status: AdministrativeStatus,
) {
    fun reference() = if (referenceKind == null || referenceId == null) null else TopologyReference(referenceKind, referenceId)
    fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED")
}

data class InterfaceRequest(
    val revision: Int? = null,
    val nodeId: UUID,
    val name: String,
    val role: InterfaceRole,
    val referenceKind: TopologyReferenceKind?,
    val referenceId: UUID?,
    val status: AdministrativeStatus,
) {
    fun reference() = if (referenceKind == null || referenceId == null) null else TopologyReference(referenceKind, referenceId)
    fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED")
}

data class LinkRequest(
    val revision: Int? = null,
    val interfaceAId: UUID,
    val interfaceZId: UUID,
    val status: AdministrativeStatus,
) { fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED") }

data class PoolRequest(
    val revision: Int? = null,
    val name: String,
    val vlanStart: Int,
    val vlanEnd: Int,
    val reserved: List<VlanRangeRequest> = emptyList(),
) {
    fun reservedRanges() = reserved.map { VlanRange(it.start, it.endInclusive) }
    fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED")
}

data class VlanRangeRequest(val start: Int, val endInclusive: Int)

data class ProfileRequest(val revision: Int? = null, val name: String, val poolId: UUID) {
    fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED")
}

data class IntentRequest(
    val revision: Int? = null,
    val subscriptionId: UUID,
    val segmentProfileId: UUID,
    val dedicatedVlanId: Int?,
    val status: String = "DRAFT",
) { fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED") }

data class ManagementProtectionRequest(
    val revision: Int? = null,
    val tenantId: UUID,
    val vlanRanges: List<VlanRangeRequest>,
    val managementIpPrefixes: Set<String>,
    val vrfs: Set<String>,
    val interfaceRoles: Set<String>,
    val collectorSourcePaths: Set<String>,
    val requiredOutOfBandRoutes: Set<String>,
    val availableOutOfBandRoutes: Set<String>,
    val sourceType: ManagementEvidenceSourceType,
    val sourceEvidenceId: UUID,
    val observedAt: java.time.Instant,
    val validUntil: java.time.Instant,
) {
    fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED")
    fun toCommand(kind: DeviceKind, id: UUID) = RecordManagementEvidenceCommand(
        tenantId,
        DeviceReference(kind, id),
        ProtectedManagementResources(
            vlanRanges.map { VlanRange(it.start, it.endInclusive) },
            managementIpPrefixes,
            vrfs,
            interfaceRoles,
            collectorSourcePaths,
            requiredOutOfBandRoutes,
        ),
        availableOutOfBandRoutes,
        sourceType,
        sourceEvidenceId,
        observedAt,
        validUntil,
    )
}
