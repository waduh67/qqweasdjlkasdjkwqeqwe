package com.duluin.ftth.provisioning

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.provisioning.config.ProvisioningRolloutProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProvisioningRolloutPropertiesTest {
    @Test
    fun `fresh rollout permits planning but rejects production apply`() {
        val rollout = ProvisioningRolloutProperties()

        assertThat(rollout.plannerEnabled).isTrue()
        assertThat(rollout.uiEnabled).isTrue()
        assertThatThrownBy { rollout.requireAutoApplyAllowed(1) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("PRODUCTION_AUTO_APPLY_DISABLED")
    }

    @Test
    fun `default rollout limits canary to one and opens circuit on first failure`() {
        val rollout = ProvisioningRolloutProperties(autoApplyEnabled = true)

        assertThat(rollout.maxAffectedSubscribers).isEqualTo(1)
        assertThat(rollout.circuitFailureThreshold).isEqualTo(1)
        assertThatThrownBy { rollout.requireAutoApplyAllowed(2) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("BULK_EXPANSION_DISABLED")
    }

    @Test
    fun `bulk expansion requires both explicit enablement and a larger configured limit`() {
        val rollout = ProvisioningRolloutProperties(
            autoApplyEnabled = true,
            bulkExpansionEnabled = true,
            maxAffectedSubscribers = 2,
        )

        rollout.requireAutoApplyAllowed(2)
        assertThatThrownBy { rollout.requireAutoApplyAllowed(3) }
            .isInstanceOf(ConflictException::class.java)
            .hasMessage("CANARY_SCOPE_EXCEEDED")
    }
}
