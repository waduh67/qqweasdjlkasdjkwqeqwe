package com.duluin.ftth.fulfillment

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkOrderMaterialUsageITProjectionRace : MaterialUsageProjectionFixture() {
    @ParameterizedTest @ValueSource(strings = ["zero", "direct-child", "zero-restore"])
    fun `replay racing projection mutation cannot return an inconsistent committed graph`(mode: String) {
        val case = consumedCase()
        val writer = fixture(case.usage.receipt.stock.token)
        val observer = fixture(case.usage.receipt.stock.token)
        val before = usageAccounting(case.usage)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val mutation = executor.submit<Throwable?> {
                runCatching { writer.transaction {
                    changeProjection(case, mode)
                    held.countDown()
                    check(release.await(30, TimeUnit.SECONDS))
                } }.exceptionOrNull()
            }
            check(held.await(30, TimeUnit.SECONDS))
            val replay = executor.submit<Pair<Int, String>> { val response = use(case.usage); response.status to response.contentAsString }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var blocked = false
            while (!blocked && System.nanoTime() < deadline) blocked = observer.transaction {
                scalar("""SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user
                    AND pid<>pg_backend_pid() AND wait_event_type='Lock'""").toLong() > 0L
            }

            assertThat(blocked).isTrue()
            release.countDown()
            val failure = mutation.get(45, TimeUnit.SECONDS)
            val result = replay.get(45, TimeUnit.SECONDS)

            if (mode == "zero-restore") assertThat(failure).isNull() else assertThat(failure).hasStackTraceContaining("consumed usage")
            assertThat(result.first).withFailMessage(result.second).isEqualTo(200)
            assertThat(result.second).isEqualTo(case.body)
            assertThat(usageAccounting(case.usage)).isEqualTo(before)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue()
        }
    }
}
