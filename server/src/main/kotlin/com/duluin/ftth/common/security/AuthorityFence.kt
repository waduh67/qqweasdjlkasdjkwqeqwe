package com.duluin.ftth.common.security

import java.util.UUID

data class SessionIdentity(val tenantId: UUID, val userId: UUID, val sessionId: String?)

interface AuthorityFence {
    val identity: SessionIdentity
    val epoch: Long
    fun assertHeld()
}

interface AuthorityChangeFence {
    val tenantId: UUID
    val epoch: Long
    fun assertHeld()
    fun incrementEpoch(): Long
}

sealed interface AuthorityScope {
    data object Unrestricted : AuthorityScope
    data class Restricted(val ids: Set<UUID>) : AuthorityScope
}

enum class WarehouseLockStage {
    TENANT_CUTOVER, CURRENT_AUTHORITY, WORK_ORDER_CONTEXT, INVENTORY_DOCUMENT_ASSIGNMENT, STOCK_DIMENSION,
}
