package com.duluin.ftth.cpe.adapter.outbound.acs

import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class AcsDiagnosticCorrelation(private val client: RestClient, private val timeout: Duration, private val interval: Duration) {
    data class Ticket(val deviceId: String, val serial: String, val inform: Instant, val requestedAt: Instant, val taskId: String)
    sealed interface Start {
        data class Ready(val ticket: Ticket) : Start
        data class Incomplete(val state: String) : Start
    }
    private val mapper = jacksonObjectMapper()

    fun begin(deviceId: String, before: JsonNode, base: String, resetField: String, originalValue: String,
        task: Map<String, Any>, beforePost: () -> Unit): Start {
        val serial = before.path("_deviceId").path("_SerialNumber").asString().takeIf { it.isNotBlank() }
            ?: return Start.Incomplete("Error_Identity")
        val initialInform = inform(before) ?: return Start.Incomplete("Error_Inform")
        if (before.path("_id").asString() != deviceId || !emptyQueue(deviceId)) return Start.Incomplete("Error_Busy")
        val nonce = UUID.randomUUID().toString()
        val marker = if (resetField.endsWith(".Host")) "ftth-$nonce.invalid" else originalValue.substringBefore('#') + "#ftth-$nonce"
        val resetAt = Instant.now()
        beforePost()
        val resetTask = post(deviceId, mapOf("name" to "setParameterValues", "parameterValues" to listOf(listOf(resetField, marker, "xsd:string"))))
            ?: return Start.Incomplete("Queued")
        val deadline = Instant.now().plus(timeout)
        var reset: JsonNode? = null
        while (Instant.now().isBefore(deadline)) {
            val current = fetch(deviceId, "$base,_id,_deviceId,_lastInform")
            if (current != null && current.path("_deviceId").path("_SerialNumber").asString() == serial &&
                current.path("_id").asString() == deviceId && inform(current)?.let { it.isAfter(initialInform) && !it.isBefore(resetAt) } == true &&
                value(current, resetField) == marker && value(current, "$base.DiagnosticsState") == "None" &&
                freshLeaf(current, resetField, resetAt) && freshLeaf(current, "$base.DiagnosticsState", resetAt)) {
                reset = current
                break
            }
            Thread.sleep(interval.toMillis())
        }
        if (reset == null || !emptyQueue(deviceId)) return Start.Incomplete("Error_ResetUnconfirmed")
        val requestedAt = Instant.now()
        beforePost()
        val taskId = post(deviceId, task) ?: return Start.Incomplete("Queued")
        if (taskId == resetTask) return Start.Incomplete("Error_TaskIdentity")
        return Start.Ready(Ticket(deviceId, serial, requireNotNull(inform(reset)), requestedAt, taskId))
    }

    fun matches(ticket: Ticket, current: JsonNode, base: String): Boolean {
        val now = Instant.now()
        val refreshed = inform(current) ?: return false
        if (current.path("_id").asString() != ticket.deviceId ||
            current.path("_deviceId").path("_SerialNumber").asString() != ticket.serial ||
            !refreshed.isAfter(ticket.inform) || refreshed.isBefore(ticket.requestedAt)) return false
        val fields = descend(current, base)
        val evidence = parameterTimeEvidence(fields)
        if (evidence.earliest?.isAfter(ticket.requestedAt) != true) return false
        val pending = ArrayDeque<JsonNode>().apply { add(fields) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node.has("_value")) {
                val time = parse(node.path("_timestamp").asString()) ?: return false
                if (time.isAfter(now)) return false
            } else node.forEach { pending.add(it) }
        }
        return emptyQueue(ticket.deviceId) && query("faults", mapOf("_id" to "${ticket.deviceId}:task_${ticket.taskId}"))?.let { it.isArray && it.isEmpty } == true
    }

    private fun post(device: String, body: Map<String, Any>): String? {
        val response = client.post().uri { it.pathSegment("devices", device, "tasks").queryParam("connection_request").build() }
            .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode::class.java)
        if (response.statusCode.value() != 200) return null
        val task = response.body ?: return null
        return task.path("_id").asString().takeIf { it.matches(Regex("[0-9a-f]{24}")) && task.path("device").asString() == device }
    }
    private fun emptyQueue(device: String): Boolean = query("tasks", mapOf("device" to device))?.let { it.isArray && it.isEmpty } == true
    private fun query(collection: String, filter: Map<String, String>): JsonNode? = client.get().uri {
        it.path("/$collection/").queryParam("query", "{query}").build(mapOf("query" to mapper.writeValueAsString(filter)))
    }.retrieve().body(JsonNode::class.java)
    private fun fetch(device: String, projection: String): JsonNode? = client.get().uri {
        it.path("/devices/").queryParam("query", "{query}").queryParam("projection", projection)
            .build(mapOf("query" to mapper.writeValueAsString(mapOf("_id" to device))))
    }.retrieve().body(JsonNode::class.java)?.takeIf { it.isArray && !it.isEmpty }?.get(0)
    private fun inform(node: JsonNode): Instant? = parse(node.path("_lastInform").asString())?.takeUnless { it.isAfter(Instant.now()) }
    private fun freshLeaf(node: JsonNode, path: String, after: Instant): Boolean = parse(descend(node, "$path._timestamp").asString())
        ?.let { !it.isBefore(after) && !it.isAfter(Instant.now()) } == true
    private fun value(node: JsonNode, path: String): String = descend(node, "$path._value").asString()
    private fun descend(node: JsonNode, path: String): JsonNode = path.split('.').fold(node) { current, name -> current.path(name) }
    private fun parse(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
