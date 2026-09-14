package com.duluin.ftth.customer

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CustomerAssetAuthorizationITRaces : CustomerAssetAuthorizationFixture() {
    @ParameterizedTest
    @ValueSource(strings=["ASSET", "CLAIM"])
    fun `authorization insertion and retirement cannot commit inconsistent authority`(target: String) {
        val fixture = episodeCase()
        val id = UUID.randomUUID()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val tasks = listOf<() -> Unit>(
                { fixture.stock.transaction { sql(fixture.authorization(id)) } },
                { fixture.stock.transaction {
                    when (target) {
                        "ASSET" -> sql("UPDATE inventory_serialized_asset SET status='DISPOSED',revision=revision+1 WHERE id='${fixture.asset}'")
                        "CLAIM" -> {
                            sql("UPDATE inventory_balance_projection SET quantity_base=0,revision=revision+1 WHERE stock_identity_id='${fixture.asset}'")
                            sql("UPDATE inventory_segment SET state='RETIRED',revision=revision+1 WHERE id='${fixture.asset}'")
                            sql("UPDATE inventory_identity_claim SET state='RETIRED',revision=revision+1 WHERE admitted_asset_id='${fixture.asset}'")
                        }
                        else -> error("Unknown retirement target")
                    }
                } },
            )
            val futures = tasks.map { action -> executor.submit<String> {
                ready.countDown()
                check(start.await(20, TimeUnit.SECONDS))
                try { action(); "00000" }
                catch (failure: Exception) {
                    generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().first().sqlState
                }
            } }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = futures.map { it.get(40, TimeUnit.SECONDS) }
            assertThat(outcomes.count { it == "00000" }).isEqualTo(1)
            assertThat(outcomes).allMatch { it in setOf("00000", "23514", "40P01", "40001") }
            fixture.stock.transaction {
                val authorized = scalar("SELECT count(*) FROM inventory_deployment_authorization WHERE id='$id'") == "1"
                if (authorized) {
                    assertThat(scalar("SELECT (warehouse_read_deployment_authorization('$tenant','$id')).id::text")).isEqualTo(id.toString())
                } else {
                    val state = when (target) {
                        "ASSET" -> scalar("SELECT status FROM inventory_serialized_asset WHERE id='${fixture.asset}'")
                        "CLAIM" -> scalar("SELECT state FROM inventory_identity_claim WHERE admitted_asset_id='${fixture.asset}' AND identity_type='SERIAL'")
                        else -> error("Unknown retirement target")
                    }
                    assertThat(state).isIn("DISPOSED", "RETIRED")
                }
            }
        }
    }
}
