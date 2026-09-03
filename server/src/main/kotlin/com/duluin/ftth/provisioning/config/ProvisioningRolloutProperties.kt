package com.duluin.ftth.provisioning.config

import com.duluin.ftth.common.domain.error.ConflictException
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ftth.provisioning.rollout")
data class ProvisioningRolloutProperties(
    val plannerEnabled: Boolean = true,
    val uiEnabled: Boolean = true,
    val autoApplyEnabled: Boolean = false,
    val maxAffectedSubscribers: Int = 1,
    val bulkExpansionEnabled: Boolean = false,
) {
    init {
        require(maxAffectedSubscribers > 0) { "PROVISIONING_CANARY_LIMIT_INVALID" }
        require(bulkExpansionEnabled || maxAffectedSubscribers == 1) { "PROVISIONING_BULK_EXPANSION_NOT_ENABLED" }
    }

    fun requirePlannerEnabled() {
        if (!plannerEnabled) throw ConflictException("PROVISIONING_PLANNER_DISABLED")
    }

    fun requireAutoApplyAllowed(affectedSubscribers: Int) {
        requirePlannerEnabled()
        if (!autoApplyEnabled) throw ConflictException("PRODUCTION_AUTO_APPLY_DISABLED")
        if (affectedSubscribers > 1 && !bulkExpansionEnabled) throw ConflictException("BULK_EXPANSION_DISABLED")
        if (affectedSubscribers !in 1..maxAffectedSubscribers) throw ConflictException("CANARY_SCOPE_EXCEEDED")
    }
}
