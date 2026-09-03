package com.duluin.ftth.bng

import java.time.Instant
import java.util.UUID

interface BngProvisioningApi {
    fun findNas(nasId: UUID): BngNasRef?
    fun findAccess(subscriptionId: UUID): BngSubscriberAccessRef?
    fun activate(subscriptionId: UUID)
    fun isolate(subscriptionId: UUID)
    fun disconnect(subscriptionId: UUID): BngSubscriberAccessRef?
    fun terminate(subscriptionId: UUID)
}

data class BngNasRef(
    val id: UUID,
    val enabled: Boolean,
    val pppoeTerminationCapable: Boolean,
)

data class BngSubscriberAccessRef(
    val id: UUID,
    val subscriptionId: UUID,
    val nasId: UUID?,
    val planId: UUID,
    val serviceClass: String?,
    val authType: String,
    val accountStatus: String,
    val pppoeTerminationCapable: Boolean,
    val activeSessionCount: Int,
    val observedAt: Instant,
)
