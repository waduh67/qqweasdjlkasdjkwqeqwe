package com.karuhun.ftth.fieldservice.application.port.outbound

import com.karuhun.ftth.common.domain.error.ConflictException
import com.karuhun.ftth.fieldservice.domain.model.CommandMetadata
import java.util.concurrent.ConcurrentHashMap

data class CommandOutcome(val namespace: String, val operationKey: String, val payloadHash: String, val result: String,
    val targetId: java.util.UUID? = null)

interface CommandOutcomeStore {
    fun find(command: CommandMetadata): CommandOutcome?
    fun record(command: CommandMetadata, result: String): CommandOutcome
    fun record(command: CommandMetadata, targetId: java.util.UUID, result: String): CommandOutcome = record(command, result)
}

class InMemoryCommandOutcomeStore : CommandOutcomeStore {
    private val outcomes = ConcurrentHashMap<Triple<java.util.UUID, String, String>, CommandOutcome>()

    override fun find(command: CommandMetadata): CommandOutcome? = outcomes[Triple(command.tenantId, command.namespace, command.operationKey)]

    override fun record(command: CommandMetadata, result: String): CommandOutcome {
        return save(command, null, result)
    }

    override fun record(command: CommandMetadata, targetId: java.util.UUID, result: String): CommandOutcome =
        save(command, targetId, result)

    private fun save(command: CommandMetadata, targetId: java.util.UUID?, result: String): CommandOutcome {
        val key = Triple(command.tenantId, command.namespace, command.operationKey)
        val candidate = CommandOutcome(command.namespace, command.operationKey, command.payloadHash, result, targetId)
        val existing = outcomes.putIfAbsent(key, candidate) ?: return candidate
        if (existing.payloadHash != command.payloadHash || existing.targetId != targetId)
            throw ConflictException("Operation key was used with a different payload or visit")
        return existing
    }
}
