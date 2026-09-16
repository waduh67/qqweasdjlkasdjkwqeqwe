package com.duluin.ftth.cpe.application.service

import com.duluin.ftth.common.tenant.TenantContext
import com.duluin.ftth.customer.CustomerObservationApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CpeOwnershipCensus(private val observations: CustomerObservationApi, private val owners: CpeObservationOwnerQuery) {
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun read(serials: Set<String>): Map<String, List<UUID>> = readLocked(serials)

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun readLocked(serials: Set<String>): Map<String, List<UUID>> {
        observations.lockOwnershipView()
        val matches = mutableMapOf<String, MutableList<UUID>>()
        owners.tenantIds().forEach { tenant ->
            TenantContext.runAs(tenant) { owners.serials(serials) }.forEach { serial ->
                matches.getOrPut(serial) { mutableListOf() }.add(tenant)
            }
        }
        return serials.associateWith { matches[it]?.toList().orEmpty() }
    }
}
