package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.service.DeviceIoCancellationPendingException
import com.duluin.ftth.provisioning.application.service.DeviceIoExclusionBusyException
import com.duluin.ftth.provisioning.application.service.DeviceIoExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@SpringBootTest
@ActiveProfiles("test")
class ProvisioningDeviceIoSessionExclusionIT {
    @Autowired private lateinit var executor: DeviceIoExecutor
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `interrupt resistant worker keeps session exclusion until actual exit`() {
        val key = "task5-device-${UUID.randomUUID()}"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val mutations = AtomicInteger()

        assertThatThrownBy {
            executor.execute(key, Instant.now().plusMillis(100), Duration.ofMillis(25), renewLease = { true }) {
                entered.countDown()
                try {
                    while (release.count > 0) {
                        try {
                            Thread.sleep(25)
                        } catch (_: InterruptedException) {
                        }
                    }
                    mutations.incrementAndGet()
                    "first"
                } finally {
                    exited.countDown()
                }
            }
        }.isInstanceOf(DeviceIoCancellationPendingException::class.java)
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()

        assertThatThrownBy {
            executor.execute(key, Instant.now().plusSeconds(1), Duration.ofMillis(25), renewLease = { true }) {
                mutations.incrementAndGet()
                "overlap"
            }
        }.isInstanceOf(DeviceIoExclusionBusyException::class.java)
        assertThat(mutations.get()).isZero()

        release.countDown()
        assertThat(exited.await(1, TimeUnit.SECONDS)).isTrue()
        val result = executor.execute(key, Instant.now().plusSeconds(1), Duration.ofMillis(25), renewLease = { true }) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            mutations.incrementAndGet()
            "after-release"
        }

        assertThat(result).isEqualTo("after-release")
        assertThat(mutations.get()).isEqualTo(2)
    }

    @Test
    fun `postgres releases session advisory exclusion when owner connection dies`() {
        val key = "task5-process-${UUID.randomUUID()}"
        val owner = dataSource.connection
        val ownerPid = try {
            owner.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, key)
                statement.execute()
            }
            dataSource.connection.use { contender ->
                assertThat(tryLock(contender, key)).isFalse()
            }
            owner.createStatement().use { statement ->
                statement.executeQuery("SELECT pg_backend_pid()").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        } catch (failure: Throwable) {
            owner.close()
            throw failure
        }

        dataSource.connection.use { terminator ->
            terminator.prepareStatement("SELECT pg_terminate_backend(?)").use { statement ->
                statement.setInt(1, ownerPid)
                statement.executeQuery().use { result ->
                    assertThat(result.next() && result.getBoolean(1)).isTrue()
                }
            }
        }
        owner.close()

        var acquired = false
        repeat(20) {
            if (!acquired) {
                acquired = runCatching {
                    dataSource.connection.use { contender ->
                        val locked = tryLock(contender, key)
                        if (locked) {
                            contender.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                                statement.setString(1, key)
                                statement.execute()
                            }
                        }
                        locked
                    }
                }.getOrDefault(false)
                if (!acquired) Thread.sleep(25)
            }
        }
        assertThat(acquired).isTrue()
    }

    private fun tryLock(connection: java.sql.Connection, key: String): Boolean =
        connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
}
