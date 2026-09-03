package com.duluin.ftth.provisioning.adapter.inbound.web

import com.duluin.ftth.provisioning.application.service.ProvisioningLifecycleService
import com.duluin.ftth.provisioning.application.service.ProvisioningEvidenceQueryService
import com.duluin.ftth.provisioning.domain.model.ProvisionExecution
import com.duluin.ftth.provisioning.domain.model.ProvisionPlan
import com.duluin.ftth.provisioning.domain.policy.ExecutionMode
import com.duluin.ftth.common.domain.error.ValidationException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/provisioning")
class ProvisioningLifecycleController(
    private val lifecycle: ProvisioningLifecycleService,
    private val evidence: ProvisioningEvidenceQueryService,
) {
    @GetMapping("/plans/{id}")
    @PreAuthorize("@authz.can('provisioning.plan.view')")
    fun plan(@PathVariable id: UUID) = lifecycle.plan(id).toView()

    @PostMapping("/plans/{id}/preview")
    @PreAuthorize("@authz.can('provisioning.plan.view')")
    fun preview(@PathVariable id: UUID, @RequestParam mode: ExecutionMode) = lifecycle.preview(id, mode)

    @PostMapping("/plans/{id}/apply")
    @PreAuthorize("@authz.can('provisioning.execution.apply')")
    fun apply(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-Match") revision: String,
    ) = lifecycle.apply(id, parseRevision(revision), idempotencyKey).let { it.toView(lifecycle.executionRevision(it.id)) }

    @GetMapping("/executions/{id}")
    @PreAuthorize("@authz.can('provisioning.plan.view')")
    fun execution(@PathVariable id: UUID) = lifecycle.execution(id).let { it.toView(lifecycle.executionRevision(it.id)) }

    @GetMapping("/executions/{id}/timeline")
    @PreAuthorize("@authz.can('provisioning.plan.view')")
    fun timeline(@PathVariable id: UUID) = evidence.timeline(id)

    @PostMapping("/executions/{id}/cancel")
    @PreAuthorize("@authz.can('provisioning.execution.cancel')")
    fun cancel(@PathVariable id: UUID, @RequestHeader("If-Match") revision: String) =
        lifecycle.cancel(id, parseRevision(revision)).let { it.toView(lifecycle.executionRevision(it.id)) }

    @GetMapping("/capabilities")
    @PreAuthorize("@authz.can('provisioning.plan.view')")
    fun capabilities() = evidence.capabilities()

    @GetMapping("/management-protections")
    @PreAuthorize("@authz.can('provisioning.segment.view')")
    fun protections() = evidence.protections()

    @GetMapping("/observations")
    @PreAuthorize("@authz.can('provisioning.drift.view')")
    fun observations() = evidence.observations()

    @GetMapping("/drift")
    @PreAuthorize("@authz.can('provisioning.drift.view')")
    fun drift() = evidence.drift()

    @PostMapping("/drift/{id}/adopt")
    @PreAuthorize("@authz.can('provisioning.drift.adopt')")
    fun adoptDrift(@PathVariable id: UUID, @RequestHeader("If-Match") revision: String) =
        evidence.adoptDrift(id, parseRevision(revision))
}

data class ProvisioningPlanView(val id: UUID, val intentId: UUID, val revision: Int, val status: String, val contentHash: String)
data class ProvisioningExecutionView(val id: UUID, val planId: UUID, val revision: Int, val status: String)

private fun ProvisionPlan.toView() = ProvisioningPlanView(id, intentId, revision, status.name, contentHash)
private fun ProvisionExecution.toView(revision: Int) = ProvisioningExecutionView(id, planId, revision, status.name)

fun parseRevision(value: String): Int = value.removePrefix("W/").trim('"').toIntOrNull()
    ?.takeIf { it > 0 } ?: throw ValidationException("REVISION_REQUIRED")
