package com.duluin.ftth.provisioning.adapter.inbound.web

import com.duluin.ftth.common.domain.error.ValidationException
import com.duluin.ftth.provisioning.application.service.PlanChange
import com.duluin.ftth.provisioning.domain.model.ServiceIntent
import com.duluin.ftth.provisioning.domain.model.VlanAllocationMode
import java.util.UUID

data class IntentRequest(
    val revision: Int? = null,
    val subscriptionId: UUID,
    val segmentProfileId: UUID,
    val allocationMode: VlanAllocationMode = VlanAllocationMode.SHARED,
    val dedicatedVlanId: Int? = null,
    val status: String = "DRAFT",
) { fun requiredRevision() = revision ?: throw ValidationException("REVISION_REQUIRED") }

data class ServiceIntentView(
    val id: UUID,
    val subscriptionId: UUID?,
    val hotspotSiteId: UUID?,
    val segmentProfileId: UUID,
    val allocationMode: VlanAllocationMode,
    val dedicatedVlanId: Int?,
    val status: String,
)

data class PlanGenerationRequest(val change: PlanChange = PlanChange.CREATE)

fun ServiceIntent.toView() = ServiceIntentView(
    id, subscriptionId, hotspotSiteId, segmentProfileId, allocationMode, dedicatedVlanId, status.name,
)
