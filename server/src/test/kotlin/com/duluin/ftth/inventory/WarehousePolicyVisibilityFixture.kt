package com.duluin.ftth.inventory

import com.duluin.ftth.common.infrastructure.security.JwtAuthenticationConverter
import com.duluin.ftth.common.tenant.TenantContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.JwtDecoder
import tools.jackson.databind.JsonNode
import java.util.UUID

abstract class WarehousePolicyVisibilityFixture : WarehousePolicyRoleFixture() {
    protected data class VisibilityScenario(val setup: Setup, val warehouse1: String, val warehouse2: String,
        val approver: Pair<String, String>, val operator: Pair<String, String>, val document: String)
    protected val statusFields = setOf("code", "sourceDocumentId", "sourceRevision", "requiredAction", "message")

    protected fun visibilityScenario(permissions: Set<String> = setOf("inventory.receipt.manage")): VisibilityScenario {
        val initial = setupReceipt()
        val warehouse1 = create("locations", initial.token, """{"code":"WH1","name":"Visible","kind":"WAREHOUSE"}""").path("id").asString()
        val warehouse2 = create("locations", initial.token, """{"code":"WH2","name":"Hidden","kind":"WAREHOUSE"}""").path("id").asString()
        val inspection = create("locations", initial.token, """{"code":"WH1-INSPECT","name":"Inspection","kind":"QUARANTINE","parentLocationId":"$warehouse1"}""").path("id").asString()
        val setup = initial.copy(inspection = inspection)
        val approver = approver(setup.token, listOf(warehouse1, warehouse2))
        val operator = user(setup.token, permissions)
        grant(setup.token, operator.second, listOf(warehouse1))
        configure(setup.token, policyBody(listOf(warehouse1, warehouse2), listOf(approver.second)))
        return VisibilityScenario(setup, warehouse1, warehouse2, approver, operator, draft(setup, costLine(setup)).path("id").asString())
    }
    protected fun sourceBody(document: String) = """{"sourceDocumentId":"$document","sourceRevision":0}"""
    protected fun fields(value: JsonNode): Set<String> = value.properties().map { it.key }.toSet()
    protected fun fullEvaluation(admin: String, document: String): WarehousePolicyEvaluation {
        val tenant = fixture(admin).tenant
        val previous = SecurityContextHolder.getContext()
        val security = SecurityContextHolder.createEmptyContext()
        security.authentication = JwtAuthenticationConverter().convert(context.getBean(JwtDecoder::class.java).decode(admin))
        SecurityContextHolder.setContext(security)
        return try {
            TenantContext.runAs(tenant) {
                context.getBean(WarehousePolicyEvaluationApi::class.java).evaluate(WarehouseSourceInput(UUID.fromString(document), 0))
            }
        } finally { SecurityContextHolder.setContext(previous) }
    }
}
