package com.duluin.ftth.monitoring.application.service

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class OltPollSingleFlight {
    private val active = ConcurrentHashMap.newKeySet<Key>()

    fun <T> run(tenantId: UUID, oltId: UUID, block: () -> T): Result<T> {
        val key = Key(tenantId, oltId)
        if (!active.add(key)) return Result.Busy

        return try {
            Result.Completed(block())
        } finally {
            active.remove(key)
        }
    }

    sealed interface Result<out T> {
        data class Completed<T>(val value: T) : Result<T>
        data object Busy : Result<Nothing>
    }

    private data class Key(val tenantId: UUID, val oltId: UUID)
}
