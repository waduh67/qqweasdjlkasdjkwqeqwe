package com.duluin.ftth.provisioning.application.service

import com.duluin.ftth.common.audit.AuditTrailEvent
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ProvisioningAuditPublisher(
    private val currentUser: CurrentUserProvider,
    private val events: ApplicationEventPublisher,
) {
    fun publish(record: ProvisioningAuditRecord) {
        val actor = currentUser.currentOrNull()
        events.publishEvent(
            AuditTrailEvent(
                record.tenantId,
                actor?.userId,
                actor?.email,
                record.action,
                record.entityType,
                record.entityId.toString(),
                redact(record.detail),
            ),
        )
    }

    private fun redact(detail: Map<String, Any?>): Map<String, Any?> = detail.mapValues { (key, value) ->
        val normalized = normalize(key)
        if (normalized !in SAFE_KEYS || SENSITIVE_KEYS.any(normalized::contains)) REDACTED else redactValue(value)
    }

    private fun redactValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to nested }.let(::redact)
        is Iterable<*> -> value.map(::redactValue)
        else -> value
    }

    private fun normalize(value: String) = value.lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        const val REDACTED = "[REDACTED]"
        val SENSITIVE_KEYS = setOf(
            "password", "sharedsecret", "token", "cookie", "credential",
            "rawconfiguration", "rawconfig", "command", "script",
        )
        val SAFE_KEYS = setOf(
            "devicekind", "deviceid", "snapshotid", "observationid", "revision", "status", "reasoncode",
        )
    }
}

data class ProvisioningAuditRecord(
    val tenantId: UUID,
    val action: String,
    val entityType: String,
    val entityId: UUID,
    val detail: Map<String, Any?> = emptyMap(),
)
