package com.duluin.ftth.customer.application.service

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.CustomerReadAccessApi
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.iam.CurrentAuthorityApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CustomerReadAccessService(
    private val customers: CustomerRepository,
    private val authority: CurrentAuthorityApi,
) : CustomerReadAccessApi {
    @Transactional(timeout = 20)
    override fun requireVisibleCustomer(customerId: UUID) {
        val current = authority.lockCurrent()
        val customer = customers.findById(customerId)
            ?: throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        val scope = current.areaScope
        if (scope is AuthorityScope.Restricted && customer.areaId !in scope.ids) {
            throw NotFoundException("Pelanggan $customerId tidak ditemukan")
        }
    }
}
