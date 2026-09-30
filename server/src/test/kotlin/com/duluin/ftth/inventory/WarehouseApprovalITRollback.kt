package com.duluin.ftth.inventory

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@Import(WarehouseApprovalITRollback.Configuration::class)
class WarehouseApprovalITRollback : WarehouseApprovalHttpFixture() {
    @Autowired lateinit var failure: FailureProbe
    class FailureProbe : WarehouseApprovalProbe {
        val stage = AtomicReference<WarehouseApprovalStage?>()
        override fun reached(stage: WarehouseApprovalStage, requestId: UUID) {
            if (this.stage.get() == stage) throw WarehouseContractException(WarehouseError(WarehouseErrorCode.STALE_REVISION, "Injected approval rollback"))
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    class Configuration { @Bean fun approvalFailureProbe() = FailureProbe() }

    @Test fun `failure after every final approval stage rolls back decisions stock receipts and response`() {
        val case = pending()
        for (stage in listOf(WarehouseApprovalStage.DECISION, WarehouseApprovalStage.OWNER_EFFECT, WarehouseApprovalStage.INBOX,
            WarehouseApprovalStage.EFFECT_RECEIPT, WarehouseApprovalStage.RESPONSE)) {
            failure.stage.set(stage)
            try {
                val result = decide(case)
                assertThat(result.status).withFailMessage("$stage: ${result.contentAsString}").isEqualTo(409)
                counts(case, 0, 0)
                fixture(case.setup.token).transaction {
                    assertThat(scalar("SELECT count(*) FROM inventory_outbox")).isEqualTo("0")
                    assertThat(scalar("SELECT count(*) FROM inventory_approval_command WHERE namespace='decide'")).isEqualTo("0")
                    assertThat(scalar("SELECT status||':'||revision::text FROM inventory_approval")).isEqualTo("PENDING:0")
                    assertThat(scalar("SELECT count(*) FROM inventory_lot")).isEqualTo("0")
                    assertThat(scalar("SELECT state||':'||revision::text FROM inventory_document WHERE id='${case.document}'")).isEqualTo("DRAFT:0")
                }
            } finally { failure.stage.set(null) }
        }
        assertThat(decide(case).status).isEqualTo(200)
        counts(case, 1, 1)
    }
    @Test fun `failure after request snapshot requirements or command leaves no pending queue`() {
        val setup = setupReceipt()
        val checker = approver(setup.token, listOf(setup.source, setup.inspection))
        configure(setup.token, policyBody(listOf(setup.inspection), listOf(checker.second)))
        val id = draft(setup, costLine(setup)).path("id").asString()
        for (stage in listOf(WarehouseApprovalStage.REQUEST, WarehouseApprovalStage.REQUIREMENTS, WarehouseApprovalStage.RESPONSE)) {
            failure.stage.set(stage)
            try {
                val result = request("POST", "/api/v1/warehouse/approvals/request", setup.token, """{"sourceDocumentId":"$id","sourceRevision":0}""")
                assertThat(result.status).withFailMessage(result.contentAsString).isEqualTo(409)
                fixture(setup.token).transaction {
                    for (table in listOf("inventory_approval", "inventory_approval_requirement", "inventory_approval_command"))
                        assertThat(scalar("SELECT count(*) FROM $table")).isEqualTo("0")
                }
            } finally { failure.stage.set(null) }
        }
        submit(setup, checker, id)
    }
}
