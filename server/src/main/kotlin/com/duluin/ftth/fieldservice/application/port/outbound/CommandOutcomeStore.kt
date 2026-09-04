package com.duluin.ftth.fieldservice.application.port.outbound

import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.fieldservice.domain.model.CommandMetadata
import java.util.concurrent.ConcurrentHashMap

data class CommandOutcome(val namespace: String, val operationKey: String, val payloadHash: String, val result: String)

interface CommandOutcomeStore {
    fun find(command: CommandMetadata): CommandOutcome?
    fun record(command: CommandMetadata, result: String): CommandOutcome
    fun record(command: CommandMetadata, targetId: java.util.UUID, result: String): CommandOutcome = record(command, result)
}

class InMemoryCommandOutcomeStore : CommandOutcomeStore {
    private val outcomes = ConcurrentHashMap<Pair<String, String>, CommandOutcome>()

    override fun find(command: CommandMetadata): CommandOutcome? = outcomes[command.namespace to command.operationKey]

    override fun record(command: CommandMetadata, result: String): CommandOutcome {
        val key = command.namespace to command.operationKey
        val candidate = CommandOutcome(command.namespace, command.operationKey, command.payloadHash, result)
        val existing = outcomes.putIfAbsent(key, candidate) ?: return candidate
        if (existing.payloadHash != command.payloadHash) throw ConflictException("Operation key was used with a different payload")
        return existing
    }
}
