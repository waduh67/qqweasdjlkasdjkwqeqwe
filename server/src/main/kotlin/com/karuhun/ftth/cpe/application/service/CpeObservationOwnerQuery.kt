package com.karuhun.ftth.cpe.application.service

import com.karuhun.ftth.customer.CustomerObservationApi
import com.karuhun.ftth.tenancy.TenantAuthorityDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
class CpeObservationOwnerQuery(private val tenants: TenantAuthorityDirectory, private val observations: CustomerObservationApi) {
    fun tenantIds() = tenants.allTenantIds()
    fun serials(serials: Set<String>) = observations.activeObservationSerials(serials)
}
