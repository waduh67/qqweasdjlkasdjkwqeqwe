package com.duluin.ftth.collector.adapter.hsgq

import com.duluin.ftth.contract.OltTarget

data class HsgqCredentials(val username: String, val secret: String) {
    override fun toString(): String = "HsgqCredentials(username=$username, secret=<redacted>)"
}

fun interface HsgqCredentialResolver {
    fun resolve(reference: String): HsgqCredentials?
}

fun interface HsgqManagementSessionFactory {
    fun open(target: OltTarget, credentials: HsgqCredentials): HsgqManagementSession
}

interface HsgqManagementSession : AutoCloseable {
    fun discover(): HsgqDeviceState
    fun ensureSubscriberVlan(desired: HsgqDesiredVlan)
    fun ensureTaggedUplink(desired: HsgqDesiredVlan, uplink: String)
    fun removeSubscriberVlan(desired: HsgqDesiredVlan)
    fun removeTaggedUplink(desired: HsgqDesiredVlan, uplink: String)
    fun restore(state: HsgqDeviceState)
    fun persist()
    fun reconnect()
}

enum class HsgqFailureStage { DISCOVERY, MUTATION, PERSISTENCE, RECONNECT }

enum class HsgqFailureKind { AUTHENTICATION, TIMEOUT, PERSISTENCE, TRANSPORT }

class HsgqTransportFailure(
    val stage: HsgqFailureStage,
    val kind: HsgqFailureKind,
    cause: Throwable? = null,
) : RuntimeException("HSGQ_${stage}_${kind}", cause)
