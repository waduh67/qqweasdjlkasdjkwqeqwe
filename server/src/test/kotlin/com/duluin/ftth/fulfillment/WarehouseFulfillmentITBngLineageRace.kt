package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import com.duluin.ftth.common.tenant.TenantContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseFulfillmentITBngLineageRace : BngHandoffFixture() {
    @ParameterizedTest @ValueSource(strings=["http","delivery"])
    fun `attachment racing a locked replay cannot corrupt its successful outcome`(consumer: String) {
        val case=completedHandoff()
        val frozen=fixture(case.token).transaction {
            scalar("SELECT request_payload FROM fulfillment_approval_snapshot WHERE id='${case.approvalId}'").decodeFulfillmentRequest()
        }
        val extra=unbound(case)
        val before=graph(case)
        val locked=CountDownLatch(1)
        val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            FulfillmentSqlProbe(context,FulfillmentSqlPhase.CHECKPOINT_LOCK) {
                locked.countDown()
                check(release.await(30,TimeUnit.SECONDS))
            }.use {
                val replay=executor.submit<String> {
                    when(consumer) {
                        "http" -> request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status.toString()
                        "delivery" -> TenantContext.runAs(frozen.tenantId) {
                            context.getBean(FulfillmentCoordinator::class.java).process(frozen).state.name
                        }
                        else -> error("Unknown consumer")
                    }
                }
                check(locked.await(30,TimeUnit.SECONDS))

                assertThatThrownBy { fixture(case.token).transaction { attach(case,extra) } }
                    .hasStackTraceContaining("FULFILLMENT_BNG_BINDING_IMMUTABLE")

                release.countDown()
                assertThat(replay.get(30,TimeUnit.SECONDS)).isEqualTo(if(consumer=="http") "200" else "APPLIED")
            }
            assertThat(graph(case)).isEqualTo(before)
            assertThat(request("POST","/api/work-orders/${case.workOrder}/approve",case.token,"{}").status).isEqualTo(200)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15,TimeUnit.SECONDS)).isTrue()
        }
    }
}
