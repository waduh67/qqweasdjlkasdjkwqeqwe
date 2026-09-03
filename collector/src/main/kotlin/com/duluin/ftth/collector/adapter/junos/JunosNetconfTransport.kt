package com.duluin.ftth.collector.adapter.junos

import com.duluin.ftth.contract.NasTarget
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class JunosConnection(
    val host: String,
    val port: Int,
    val username: String,
    val secret: String,
) {
    override fun toString(): String = "JunosConnection(host=$host, port=$port, username=<redacted>, secret=<redacted>)"

    companion object {
        fun from(target: NasTarget): JunosConnection = JunosConnection(
            host = target.host?.takeIf(String::isNotBlank)
                ?: throw JunosConfigurationException("NETCONF host is required"),
            port = (target.apiPort ?: 830).takeIf { it in 1..65535 }
                ?: throw JunosConfigurationException("NETCONF port is invalid"),
            username = target.apiUsername?.takeIf(String::isNotBlank)
                ?: throw JunosConfigurationException("NETCONF username is required"),
            secret = target.apiSecret?.takeIf(String::isNotBlank)
                ?: throw JunosConfigurationException("NETCONF secret is required"),
        )
    }
}

data class JunosHello(
    val identity: JunosDeviceIdentity,
    val capabilities: Set<String>,
)

enum class JunosEditOperation { MERGE }

data class JunosCandidateChange(
    val operation: JunosOperation,
    val defaultOperation: JunosEditOperation,
    val configuration: String,
    val expectedResources: Set<String>,
)

data class JunosOperationalObservation(
    val resources: Set<String>,
    val managementReachable: Boolean,
)

data class JunosConfirmedCommit(
    val commitId: String,
    val rollbackId: String,
    val expiresAt: Instant,
)

data class JunosRollbackReceipt(
    val rollbackId: String,
    val observation: JunosOperationalObservation,
)

fun interface JunosNetconfSessionFactory {
    fun open(connection: JunosConnection): JunosNetconfSession
}

interface JunosNetconfSession : AutoCloseable {
    fun hello(): JunosHello
    fun observe(change: JunosCandidateChange): JunosOperationalObservation
    fun lockCandidate()
    fun editCandidate(change: JunosCandidateChange)
    fun validateCandidate()
    fun commitConfirmed(timeoutSeconds: Int): JunosConfirmedCommit
    fun confirmCommit(commitId: String)
    fun awaitAutomaticRollback(rollbackId: String): JunosRollbackReceipt
    fun discardCandidate()
    fun unlockCandidate()
}

enum class JunosRollbackStatus { PENDING_AUTOMATIC, AUTOMATIC_COMPLETED }

data class JunosRollbackRecord(
    val stepKey: String,
    val commitId: String,
    val rollbackId: String,
    val before: JunosOperationalObservation,
    val status: JunosRollbackStatus,
    val recordedAt: Instant,
)

interface JunosRollbackJournal {
    fun record(record: JunosRollbackRecord)
    fun find(stepKey: String): JunosRollbackRecord?
}

class InMemoryJunosRollbackJournal : JunosRollbackJournal {
    private val records = ConcurrentHashMap<String, JunosRollbackRecord>()

    override fun record(record: JunosRollbackRecord) {
        records[record.stepKey] = record
    }

    override fun find(stepKey: String): JunosRollbackRecord? = records[stepKey]
}

open class JunosNetconfException(message: String) : RuntimeException(message)
class JunosConfigurationException(message: String) : JunosNetconfException(message)
class JunosUnsupportedCapabilityException : JunosNetconfException("Unsupported Junos capability profile")
class JunosLockDeniedException : JunosNetconfException("Candidate datastore is locked")
class JunosValidationException(message: String) : JunosNetconfException(message)
class JunosStalePreconditionException : JunosNetconfException("Observed state differs from the precondition")
class JunosManagementPathException : JunosNetconfException("Management path is not reachable")
class JunosVerificationException : JunosNetconfException("Operational verification failed")
class JunosConfirmationExpiredException : JunosNetconfException("Confirmed commit expired")
