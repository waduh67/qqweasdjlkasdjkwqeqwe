package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerObservationApi
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Locale
import java.util.UUID

@Service
class CpeOwnershipEligibility(private val observations: CustomerObservationApi, private val owners: CpeObservationOwnerQuery) {
    private class View : TransactionSynchronization {
        val resolved = mutableMapOf<String, List<UUID>>()
    }

    fun prepare(serials: Set<String>) {
        if (serials.isEmpty()) return
        check(TransactionSynchronizationManager.isActualTransactionActive())
        val view = TransactionSynchronizationManager.getSynchronizations().filterIsInstance<View>().singleOrNull() ?: View().also {
            observations.lockOwnershipView()
            TransactionSynchronizationManager.registerSynchronization(it)
        }
        val missing = serials.mapTo(sortedSetOf()) { normalize(it) }.filterNotTo(sortedSetOf()) { view.resolved.containsKey(it) }
        if (missing.isEmpty()) return
        val matches = mutableMapOf<String, MutableList<UUID>>()
        owners.tenantIds().forEach { tenant ->
            TenantContext.runAs(tenant) { owners.serials(missing) }.forEach { serial ->
                matches.getOrPut(serial) { mutableListOf() }.add(tenant)
            }
        }
        missing.forEach { view.resolved[it] = matches[it]?.toList().orEmpty() }
    }

    fun current(serial: String): Boolean {
        prepare(setOf(serial))
        val view = TransactionSynchronizationManager.getSynchronizations().filterIsInstance<View>().single()
        return view.resolved[normalize(serial)]?.singleOrNull() == TenantContext.tenantId()
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
    fun census(serials: Set<String>): Map<String, List<UUID>> {
        if (serials.isEmpty()) return emptyMap()
        prepare(serials)
        return TransactionSynchronizationManager.getSynchronizations().filterIsInstance<View>().single().resolved.toMap()
    }

    private fun normalize(serial: String) = serial.trim().uppercase(Locale.ROOT)
}
