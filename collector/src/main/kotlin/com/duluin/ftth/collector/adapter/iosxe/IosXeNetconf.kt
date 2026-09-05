package com.duluin.ftth.collector.adapter.iosxe

import com.duluin.ftth.contract.NasTarget
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class IosXeCredentials(val username: String, val password: String) {
    override fun toString(): String = "IosXeCredentials(username=[REDACTED], password=[REDACTED])"
}

data class IosXeHello(
    val vendor: String,
    val platform: String,
    val softwareVersion: String,
    val capabilities: Set<String>,
    val yangModules: Set<String>,
)

data class IosXeDesiredConfiguration(
    val vlanId: Int,
    val trunkInterfaces: Set<String>,
    val accessInterfaces: Set<String>,
    val aclName: String?,
    val remove: Boolean,
)

data class IosXeOperationalState(
    val vlanPresent: Boolean,
    val vlanId: Int?,
    val trunkInterfaces: Set<String>,
    val accessInterfaces: Set<String>,
    val aclApplied: Boolean,
    val managementReachable: Boolean,
) {
    fun matches(desired: IosXeDesiredConfiguration): Boolean {
        if (!managementReachable) return false
        if (desired.remove) return !vlanPresent
        return vlanPresent && vlanId == desired.vlanId &&
            trunkInterfaces.containsAll(desired.trunkInterfaces) &&
            accessInterfaces.containsAll(desired.accessInterfaces) &&
            (desired.aclName == null || aclApplied)
    }

    fun hash(): String {
        val canonical = listOf(
            vlanPresent.toString(),
            vlanId?.toString().orEmpty(),
            trunkInterfaces.sorted().joinToString(","),
            accessInterfaces.sorted().joinToString(","),
            aclApplied.toString(),
            managementReachable.toString(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

fun interface IosXeNetconfSessionFactory {
    fun open(target: NasTarget, credentials: IosXeCredentials): IosXeNetconfSession
}

interface IosXeNetconfSession : AutoCloseable {
    fun hello(): IosXeHello
    fun readBaseline(): IosXeOperationalState
    fun lockCandidate()
    fun discardChanges()
    fun editCandidate(xml: String)
    fun validateCandidate()
    fun confirmedCommit(timeoutSeconds: Int)
    fun verifyOperational(expected: IosXeDesiredConfiguration): IosXeOperationalState
    fun finalCommit()
    fun unlockCandidate()
    fun awaitDeviceRollback(expectedStateHash: String, timeoutSeconds: Int): IosXeOperationalState
}

enum class IosXeNetconfError {
    LOCK_DENIED,
    VALIDATION,
    TIMEOUT,
    RPC_ERROR,
}

class IosXeNetconfException(val error: IosXeNetconfError) : RuntimeException(error.name)

object IosXeCapabilities {
    const val BASE = "urn:ietf:params:netconf:base:1.0"
    const val CANDIDATE = "urn:ietf:params:netconf:capability:candidate:1.0"
    const val CONFIRMED_COMMIT = "urn:ietf:params:netconf:capability:confirmed-commit:1.1"
    const val VALIDATE = "urn:ietf:params:netconf:capability:validate:1.1"
    val PROTOCOL = setOf(BASE, CANDIDATE, CONFIRMED_COMMIT, VALIDATE)
}

object IosXeXml {
    private val sensitiveElements = Regex(
        "(?is)<(password|secret|community|username)(?:\\s[^>]*)?>.*?</\\1>",
    )
    private val configElement = Regex("(?is)<config(?:\\s[^>]*)?>.*?</config>")

    fun redact(xml: String): String = xml
        .replace(configElement, "<config>[REDACTED]</config>")
        .replace(sensitiveElements) { match ->
            val name = match.groupValues[1]
            "<$name>[REDACTED]</$name>"
        }

    internal fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }
}
