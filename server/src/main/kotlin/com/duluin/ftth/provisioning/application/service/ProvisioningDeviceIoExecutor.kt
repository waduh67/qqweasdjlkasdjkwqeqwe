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

class DeviceIoDeadlineExceededException : RuntimeException("DEADLINE_EXCEEDED")
class DeviceIoLeaseLostException : RuntimeException("LEASE_LOST")
class DeviceIoCancellationException : RuntimeException("DEVICE_IO_CANCELLATION_FAILED")

interface DeviceIoExecutor {
    fun <T : Any> execute(
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
) : DeviceIoExecutor {
    private val withoutTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_NOT_SUPPORTED
    }

    override fun <T : Any> execute(
        deadline: Instant,
        renewalInterval: Duration,
        renewLease: () -> Boolean,
        operation: () -> T,
    ): T = withoutTransaction.execute<T> {
        executeBounded(deadline, renewalInterval, renewLease, operation)
    }

    private fun <T : Any> executeBounded(
        deadline: Instant,
        renewalInterval: Duration,
        renewLease: () -> Boolean,
        operation: () -> T,
    ): T {
        require(!renewalInterval.isZero && !renewalInterval.isNegative) { "LEASE_RENEWAL_INTERVAL_INVALID" }
        val timeout = Duration.between(clock.instant(), deadline)
        if (timeout.isZero || timeout.isNegative) throw DeviceIoDeadlineExceededException()
        val executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("provisioning-device-io-", 0).factory())
        val future = executor.submit(Callable(operation))
        val expiresAt = System.nanoTime() + timeout.toNanos()
        var nextRenewal = System.nanoTime() + renewalInterval.toNanos()
        try {
            while (true) {
                val now = System.nanoTime()
                if (now >= expiresAt) {
                    cancelAndAwait(future, executor)
                    throw DeviceIoDeadlineExceededException()
                }
                val waitNanos = minOf(expiresAt - now, nextRenewal - now).coerceAtLeast(1)
                try {
                    return future.get(waitNanos, TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    val afterWait = System.nanoTime()
                    if (afterWait >= expiresAt) {
                        cancelAndAwait(future, executor)
                        throw DeviceIoDeadlineExceededException()
                    }
                    if (!renewLease()) {
                        cancelAndAwait(future, executor)
                        throw DeviceIoLeaseLostException()
                    }
                    nextRenewal = afterWait + renewalInterval.toNanos()
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }
        } catch (failure: InterruptedException) {
            cancelAndAwait(future, executor)
            Thread.currentThread().interrupt()
            throw failure
        } finally {
            if (!executor.isShutdown) {
                executor.shutdownNow()
                if (!executor.awaitTermination(CANCELLATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw DeviceIoCancellationException()
                }
            }
        }
    }

    private fun cancelAndAwait(future: Future<*>, executor: java.util.concurrent.ExecutorService) {
        future.cancel(true)
        executor.shutdownNow()
        if (!executor.awaitTermination(CANCELLATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            throw DeviceIoCancellationException()
        }
    }

    private companion object {
        val CANCELLATION_GRACE: Duration = Duration.ofSeconds(2)
    }
}
