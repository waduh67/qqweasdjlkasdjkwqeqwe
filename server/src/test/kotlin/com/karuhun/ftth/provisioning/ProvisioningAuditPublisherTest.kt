package com.karuhun.ftth.provisioning

import com.karuhun.ftth.common.audit.AuditTrailEvent
import com.karuhun.ftth.common.security.AuthenticatedUser
import com.karuhun.ftth.common.security.CurrentUserProvider
import com.karuhun.ftth.provisioning.application.service.ProvisioningAuditPublisher
import com.karuhun.ftth.provisioning.application.service.ProvisioningAuditRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

class ProvisioningAuditPublisherTest {
    @Test
    fun `audit details redact every secret-bearing field`() {
        val events = mutableListOf<AuditTrailEvent>()
        val actor = AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "operator@test", "Operator", false, emptySet(), emptySet())
        val currentUser = object : CurrentUserProvider { override fun currentOrNull() = actor }
        val publisher = ProvisioningAuditPublisher(currentUser, ApplicationEventPublisher { events += it as AuditTrailEvent })
        val canaries = mapOf(
            "password" to "password-canary",
            "sharedSecret" to "shared-secret-canary",
            "token" to "token-canary",
            "sessionCookie" to "cookie-canary",
            "credentialReference" to "credential-reference-canary",
            "rawConfiguration" to "raw-config-canary",
        )

        publisher.publish(ProvisioningAuditRecord(actor.tenantId, "provisioning.test", "Resource", UUID.randomUUID(), canaries))

        val serialized = events.single().detail.toString()
        canaries.values.forEach { assertThat(serialized).doesNotContain(it) }
        assertThat(events.single().action).isEqualTo("provisioning.test")
    }
}
