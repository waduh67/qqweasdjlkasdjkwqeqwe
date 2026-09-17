package com.duluin.ftth.monitoring

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.cpe.application.port.outbound.AcsDevice
import com.duluin.ftth.cpe.application.port.outbound.CpeDeviceRepository
import com.duluin.ftth.cpe.application.service.CpeSyncService
import com.duluin.ftth.customer.CustomerAssetEpisodeFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WarehouseDiscoveryITR2Isolation : CustomerAssetEpisodeFixture() {
    @Test
    fun `repeatable read cannot expose old owner after an actual committed episode transfer`() {
        val fixture = episodeCase()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val start = Instant.now().minusSeconds(60).toString()
        fixture.stock.transaction { sql(fixture.assignment(first, start = start)); sql(fixture.episode(first, start = start)) }
        val now = Instant.now()
        TenantContext.runAs(fixture.stock.tenant) { context.getBean(CpeSyncService::class.java).sync(listOf(
            AcsDevice("ISO-${fixture.asset}", fixture.serial, null, null, "Vendor", "old", null, null, now, "old-private", observedFieldsAt = now))) }
        val id = fixture.stock.transaction { UUID.fromString(scalar("SELECT id FROM cpe_device")) }
        val snapshot = CountDownLatch(1)
        val changed = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { executor ->
            val read = executor.submit<UUID?> {
                TenantContext.runAs(fixture.stock.tenant) {
                    val transaction = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).apply {
                        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
                    }
                    try {
                        transaction.execute {
                            fixture.stock.scalar("SELECT id FROM onu WHERE assignment_id='$first'")
                            snapshot.countDown(); check(changed.await(20, TimeUnit.SECONDS))
                            context.getBean(CpeDeviceRepository::class.java).findById(id)?.id
                        }
                    } catch (failure: com.duluin.ftth.common.domain.error.ConflictException) {
                        assertThat(failure.message).contains("READ_COMMITTED_REQUIRED")
                        null
                    }
                }
            }
            try {
                check(snapshot.await(10, TimeUnit.SECONDS))
                val boundary = Instant.now().toString()
                fixture.stock.transaction {
                    sql("UPDATE onu SET retired_at='$boundary',episode_revision=episode_revision+1 WHERE assignment_id='$first'")
                    sql("UPDATE inventory_asset_assignment SET ended_at='$boundary',revision=revision+1 WHERE id='$first'")
                }
                fixture.stock.transaction { sql(fixture.assignment(second, fixture.customerB, boundary)); sql(fixture.episode(second, fixture.customerB, boundary)) }
            } finally { changed.countDown() }
            assertThat(read.get(30, TimeUnit.SECONDS)).isNull()
        }
    }
}
