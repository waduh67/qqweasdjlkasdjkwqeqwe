package com.duluin.ftth.onboarding

import com.duluin.ftth.bng.CredentialHandle
import java.time.Instant
import java.util.UUID

data class MigrationFulfillmentRequested(
    val tenantId: UUID? = null,
    val operationKey: String,
    val subscriptionId: UUID,
    val username: String,
    val planId: UUID,
    val nasId: UUID?,
    val authType: String,
    val credentialHandle: CredentialHandle?,
    val requestedAt: Instant = Instant.now(),
)

interface MigrationFulfillmentPublisher {
    fun publish(request: MigrationFulfillmentRequested)
}

interface CredentialSealer {
    fun seal(secret: String?): CredentialHandle?
}

object UuidCredentialSealer : CredentialSealer {
    override fun seal(secret: String?): CredentialHandle? =
        secret?.trim()?.takeIf { it.isNotEmpty() }?.let { CredentialHandle(UUID.randomUUID()) }
}

object NoopMigrationFulfillmentPublisher : MigrationFulfillmentPublisher {
    override fun publish(request: MigrationFulfillmentRequested) = Unit
}
