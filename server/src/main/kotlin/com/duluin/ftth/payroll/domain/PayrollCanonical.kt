package com.duluin.ftth.payroll.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object PayrollCanonical {
    fun hash(namespace: String, tenantId: UUID, actorId: UUID, fields: Map<String, String>): String {
        val canonical = buildString {
            append(namespace).append('|').append(tenantId).append('|').append(actorId)
            fields.toSortedMap().forEach { (key, value) -> append('|').append(key).append('=').append(value.trim()) }
        }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
