package com.duluin.ftth.provisioning

import com.duluin.ftth.provisioning.application.service.DeviceIoDeadlineExceededException
import com.duluin.ftth.provisioning.application.service.DeviceIoExecutor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProvisioningDeviceIoExecutorTest {
    private val txManager = TestTransactionManager()
    private val executor: DeviceIoExecutor = com.duluin.ftth.provisioning.application.service.TransactionSuspendingDeviceIoExecutor(
        txManager,
        Clock.systemUTC(),
    )

    @Test
    fun `gateway operation runs with caller transaction suspended`() {
        TransactionTemplate(txManager).execute {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()

            val result = executor.execute(
                Instant.now().plusSeconds(2),
                Duration.ofMillis(50),
                renewLease = { true },
            ) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                "completed"
            }

            assertThat(result).isEqualTo("completed")
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue()
        }
    }

    @Test
    fun `hung gateway operation is interrupted and leaves no worker alive after deadline`() {
        val interrupted = CountDownLatch(1)

        assertThatThrownBy {
            executor.execute(
                Instant.now().plusMillis(100),
                Duration.ofMillis(25),
                renewLease = { true },
            ) {
                try {
                    CountDownLatch(1).await()
                } catch (failure: InterruptedException) {
                    interrupted.countDown()
                    throw failure
                }
            }
        }.isInstanceOf(DeviceIoDeadlineExceededException::class.java)
        assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue()
    }

    @Test
    fun `long operation renews lease until it completes`() {
        val renewals = AtomicInteger()

        val result = executor.execute(
            Instant.now().plusSeconds(2),
            Duration.ofMillis(25),
            renewLease = { renewals.incrementAndGet(); true },
        ) {
            Thread.sleep(120)
            "completed"
        }

        assertThat(result).isEqualTo("completed")
        assertThat(renewals.get()).isGreaterThan(0)
    }

    @Test
    fun `lease heartbeat prevents takeover while device operation is alive`() {
        val leaseExpiry = AtomicLong(System.nanoTime() + Duration.ofMillis(50).toNanos())
        val takeoverObserved = AtomicBoolean(false)
        val contender = Thread.ofVirtual().start {
            Thread.sleep(90)
            takeoverObserved.set(System.nanoTime() >= leaseExpiry.get())
        }

        executor.execute(
            Instant.now().plusSeconds(2),
            Duration.ofMillis(20),
            renewLease = {
                leaseExpiry.set(System.nanoTime() + Duration.ofMillis(50).toNanos())
                true
            },
        ) {
            Thread.sleep(150)
            "completed"
        }
        contender.join()

        assertThat(takeoverObserved).isFalse()
    }

    private class TestTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}
