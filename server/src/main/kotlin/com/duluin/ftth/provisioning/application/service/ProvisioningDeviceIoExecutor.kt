package com.duluin.ftth.provisioning.application.service

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.CountDownLatch
import javax.sql.DataSource

class DeviceIoDeadlineExceededException : RuntimeException("DEADLINE_EXCEEDED")
class DeviceIoLeaseLostException : RuntimeException("LEASE_LOST")
class DeviceIoCancellationPendingException : RuntimeException("DEVICE_IO_CANCELLATION_PENDING")
class DeviceIoExclusionBusyException : RuntimeException("DEVICE_IO_EXCLUSION_BUSY")

interface DeviceIoExclusion {
    fun <T : Any> withLock(key: String, operation: () -> T): T
}

@Component
class PostgresSessionDeviceIoExclusion(
    private val dataSource: DataSource,
) : DeviceIoExclusion {
    override fun <T : Any> withLock(key: String, operation: () -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = true
        val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!acquired) throw DeviceIoExclusionBusyException()
        try {
            operation()
        } finally {
            connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use { statement ->
                statement.setString(1, key)
                statement.execute()
            }
        }
    }
}

interface DeviceIoExecutor {
    fun <T : Any> execute(
        exclusionKey: String,
        deadline: Instant,
        renewalInterval: Duration,
        renewLease: () -> Boolean,
        operation: () -> T,
    ): T
}

@Component
class TransactionSuspendingDeviceIoExecutor(
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
    private val exclusion: DeviceIoExclusion,
) : DeviceIoExecutor {
    private val withoutTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
    }

    override fun <T : Any> execute(
        exclusionKey: String,
        deadline: Instant,
        renewalInterval: Duration,
        renewLease: () -> Boolean,
        operation: () -> T,
    ): T = withoutTransaction.execute<T> {
        executeBounded(exclusionKey, deadline, renewalInterval, renewLease, operation)
    }

    private fun <T : Any> executeBounded(
        exclusionKey: String,
        deadline: Instant,
        renewalInterval: Duration,
        renewLease: () -> Boolean,
        operation: () -> T,
    ): T {
        require(!renewalInterval.isZero && !renewalInterval.isNegative) { "LEASE_RENEWAL_INTERVAL_INVALID" }
        val timeout = Duration.between(clock.instant(), deadline)
        if (timeout.isZero || timeout.isNegative) throw DeviceIoDeadlineExceededException()
        val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("provisioning-device-io-", 0).factory())
        val workerExited = CountDownLatch(1)
        val future = executor.submit(Callable {
            try {
                exclusion.withLock(exclusionKey, operation)
            } finally {
                workerExited.countDown()
            }
        })
        val expiresAt = System.nanoTime() + timeout.toNanos()
        var nextRenewal = System.nanoTime() + renewalInterval.toNanos()
        try {
            while (true) {
                val now = System.nanoTime()
                if (now >= expiresAt) {
                    cancelAndAwait(future, executor, workerExited, renewalInterval, renewLease)
                    throw DeviceIoDeadlineExceededException()
                }
                val waitNanos = minOf(expiresAt - now, nextRenewal - now).coerceAtLeast(1)
                try {
                    return future.get(waitNanos, TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    val afterWait = System.nanoTime()
                    if (afterWait >= expiresAt) {
                        cancelAndAwait(future, executor, workerExited, renewalInterval, renewLease)
                        throw DeviceIoDeadlineExceededException()
                    }
                    if (!renewLease()) {
                        cancelAndAwait(future, executor, workerExited, renewalInterval, renewLease)
                        throw DeviceIoLeaseLostException()
                    }
                    nextRenewal = afterWait + renewalInterval.toNanos()
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } catch (failure: InterruptedException) {
            cancelAndAwait(future, executor, workerExited, renewalInterval, renewLease)
            Thread.currentThread().interrupt()
            throw failure
        } finally {
            if (!executor.isShutdown) {
                executor.shutdownNow()
                if (!workerExited.await(CANCELLATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw DeviceIoCancellationPendingException()
                }
            }
        }
    }

    private fun cancelAndAwait(
        future: Future<*>,
        executor: java.util.concurrent.ExecutorService,
        workerExited: CountDownLatch,
        renewalInterval: Duration,
        renewLease: () -> Boolean,
    ) {
        future.cancel(true)
        executor.shutdown()
        val stopWaitingAt = System.nanoTime() + CANCELLATION_GRACE.toNanos()
        while (true) {
            val remaining = stopWaitingAt - System.nanoTime()
            if (remaining <= 0) throw DeviceIoCancellationPendingException()
            val waitNanos = minOf(remaining, renewalInterval.toNanos()).coerceAtLeast(1)
            if (workerExited.await(waitNanos, TimeUnit.NANOSECONDS)) return
            if (!renewLease()) throw DeviceIoLeaseLostException()
        }
    }

    private companion object {
        val CANCELLATION_GRACE: Duration = Duration.ofSeconds(2)
    }
}
