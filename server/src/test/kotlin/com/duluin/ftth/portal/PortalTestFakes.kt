package com.duluin.ftth.portal

import com.duluin.ftth.common.security.AuthenticatedUser
import com.duluin.ftth.common.security.CurrentUserProvider
import com.duluin.ftth.customer.CustomerApi
import com.duluin.ftth.customer.CustomerRef
import com.duluin.ftth.customer.ProvisionOnuCommand
import com.duluin.ftth.customer.RegisterCustomerCommand
import com.duluin.ftth.customer.SubscriptionRef
import com.duluin.ftth.portal.application.port.outbound.PortalAccessTokenIssuer
import com.duluin.ftth.portal.application.port.outbound.PortalCredentialRepository
import com.duluin.ftth.portal.application.port.outbound.PortalIssuedToken
import com.duluin.ftth.portal.application.port.outbound.PortalPasswordHasher
import com.duluin.ftth.portal.application.port.outbound.PortalRefreshTokenRepository
import com.duluin.ftth.portal.domain.model.PortalCredential
import com.duluin.ftth.portal.domain.model.PortalRefreshToken
import com.duluin.ftth.portal.security.CurrentPortalCustomer
import com.duluin.ftth.portal.security.PortalCustomer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Fake bersama untuk uji portal — in-memory, tanpa mock, sesuai konvensi repo. */

class InMemoryPortalCredentialRepository : PortalCredentialRepository {
    private val byCustomer = LinkedHashMap<UUID, PortalCredential>()

    override fun save(credential: PortalCredential): PortalCredential {
        byCustomer[credential.customerId] = credential
        return credential
    }

    override fun findByLogin(login: String): PortalCredential? =
        byCustomer.values.firstOrNull { it.login == login }

    override fun findByCustomerId(customerId: UUID): PortalCredential? = byCustomer[customerId]

    fun count(): Int = byCustomer.size
}

class RecordingPortalRefreshTokenRepository : PortalRefreshTokenRepository {
    private val byHash = LinkedHashMap<String, PortalRefreshToken>()
    val revokedCustomers = mutableListOf<UUID>()

    override fun save(token: PortalRefreshToken): PortalRefreshToken {
        byHash[token.tokenHash] = token
        return token
    }

    override fun findByTokenHash(tokenHash: String): PortalRefreshToken? = byHash[tokenHash]

    override fun revokeAllForCustomer(customerId: UUID) {
        revokedCustomers.add(customerId)
        byHash.values.filter { it.customerId == customerId && it.revokedAt == null }.forEach { it.revoke() }
    }
}

/** Hasher plaintext deterministik — cukup untuk menegaskan hash↔password cocok. */
class PlainTextPortalPasswordHasher : PortalPasswordHasher {
    override fun hash(rawPassword: String): String = "hash:$rawPassword"
    override fun matches(rawPassword: String, passwordHash: String): Boolean = passwordHash == "hash:$rawPassword"
}

/** Penerbit access-token yang mengembalikan nilai tetap; cukup untuk uji alur. */
class StubPortalAccessTokenIssuer(private val expiresAt: Instant = Instant.now().plusSeconds(900)) : PortalAccessTokenIssuer {
    var lastCustomerId: UUID? = null

    override fun issue(customerId: UUID, tenantId: UUID, login: String, name: String): PortalIssuedToken {
        lastCustomerId = customerId
        return PortalIssuedToken(value = "access-$login", expiresAt = expiresAt)
    }
}

class MutableCurrentPortalCustomer(var value: PortalCustomer? = null) : CurrentPortalCustomer {
    override fun currentOrNull(): PortalCustomer? = value
}

/** Tanpa operator login (audit actor null) — dipakai saat aksi bukan dari konteks operator. */
class NoOperatorCurrentUserProvider(private val user: AuthenticatedUser? = null) : CurrentUserProvider {
    override fun currentOrNull(): AuthenticatedUser? = user
}

/** CustomerApi stub: resolusi pelanggan + langganan; sisanya tak dipakai uji portal. */
class StubCustomerApi(vararg seed: CustomerRef) : CustomerApi {
    private val byId = LinkedHashMap<UUID, CustomerRef>().apply { seed.forEach { put(it.id, it) } }
    private val subscriptions = LinkedHashMap<UUID, List<SubscriptionRef>>()

    fun add(ref: CustomerRef) {
        byId[ref.id] = ref
    }

    fun seedSubscriptions(customerId: UUID, subs: List<SubscriptionRef>) {
        subscriptions[customerId] = subs
    }

    override fun findCustomer(id: UUID): CustomerRef? = byId[id]

    override fun findCustomersByIds(ids: Set<UUID>) = ids.mapNotNull { byId[it] }
    override fun findSubscription(id: UUID) = throw UnsupportedOperationException()
    override fun findSubscriptionsByCustomer(customerId: UUID): List<SubscriptionRef> =
        subscriptions[customerId].orEmpty()
    override fun findOccupantsOfOdp(odpId: UUID) = throw UnsupportedOperationException()
    override fun findAwaitingInstallation(areaIds: Set<UUID>?) = throw UnsupportedOperationException()
    override fun findPlacementOf(customerId: UUID) = throw UnsupportedOperationException()
    override fun occupiedPortsOn(odpId: UUID) = throw UnsupportedOperationException()
    override fun countOccupantsByOdp(odpIds: Set<UUID>) = throw UnsupportedOperationException()
    override fun renderMapTile(z: Int, x: Int, y: Int, areaIds: Set<UUID>?) = throw UnsupportedOperationException()
    override fun findOnusBySerialNumbers(serialNumbers: Set<String>) = throw UnsupportedOperationException()
    override fun placementsForOnus(onuIds: Set<UUID>) = throw UnsupportedOperationException()
    override fun recordObservedOnuStatuses(statuses: Map<UUID, String>) = throw UnsupportedOperationException()
    override fun provisionOnu(command: ProvisionOnuCommand) = throw UnsupportedOperationException()
    override fun findBillableSubscriptions() = throw UnsupportedOperationException()
    override fun findBillableSubscription(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun isolateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun reactivateForBilling(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun activateForInstallation(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun terminateForDismantle(subscriptionId: UUID) = throw UnsupportedOperationException()
    override fun registerCustomer(command: RegisterCustomerCommand) = throw UnsupportedOperationException()
    override fun openSubscription(customerId: UUID, planId: UUID, monthlyFeeOverride: BigDecimal?) = throw UnsupportedOperationException()
    override fun subscriberStats() = throw UnsupportedOperationException()
    override fun updateCustomerBiodata(command: com.duluin.ftth.customer.UpdateCustomerBiodataCommand) = throw UnsupportedOperationException()
    override fun activateImportedSubscription(subscriptionId: UUID, activatedAt: java.time.Instant?, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
    override fun overrideSubscriptionBillingDay(subscriptionId: UUID, billingDayOfMonth: Int?) = throw UnsupportedOperationException()
    override fun findExportRows(subscriptionIds: Set<java.util.UUID>): List<com.duluin.ftth.customer.CustomerExportRow> = throw UnsupportedOperationException()
}
