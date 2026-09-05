package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.adapter.inbound.web.ProvisioningExecutionView
import com.duluin.ftth.provisioning.application.service.ExecutionTimelineEntry
import com.duluin.ftth.provisioning.application.service.ObservationView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProvisioningApiRedactionContractTest {
    @Test
    fun `execution and observation responses expose identifiers and state only`() {
        val fields = listOf(
            ProvisioningExecutionView::class.java,
            ExecutionTimelineEntry::class.java,
            ObservationView::class.java,
        ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        assertThat(fields).doesNotContain(
            "detail", "normalizedstate", "password", "secret", "token", "cookie",
            "credentialreference", "rawconfiguration", "command",
        )
    }
}
