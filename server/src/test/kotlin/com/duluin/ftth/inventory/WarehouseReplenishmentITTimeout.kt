package com.duluin.ftth.inventory

import com.duluin.ftth.iam.CurrentAuthorityApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseReplenishmentITTimeout : WarehouseReplenishmentFixture() {
    @Test fun `contended authority returns bounded conflict instead of hanging a replenishment command`() {
        val (token, fixture) = prepare()
        val rule = createRule(token, fixture)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                fixture.transaction {
                    context.getBean(CurrentAuthorityApi::class.java).lockForChange().assertHeld()
                    held.countDown()
                    check(release.await(20, TimeUnit.SECONDS))
                }
            }
            check(held.await(10, TimeUnit.SECONDS))
            val attempt = executor.submit<Int> {
                request("POST", "$root/rules/${rule.path("id").asString()}/recompute", token, """{"expectedRevision":0}""").status
            }
            try { assertThat(attempt.get(8, TimeUnit.SECONDS)).isEqualTo(409) }
            finally { release.countDown(); holder.get(10, TimeUnit.SECONDS) }
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
