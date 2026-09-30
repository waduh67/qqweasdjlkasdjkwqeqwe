package com.duluin.ftth.customer

import com.duluin.ftth.common.domain.error.NotFoundException
import com.duluin.ftth.common.security.AuthorityFence
import com.duluin.ftth.common.security.AuthorityScope
import com.duluin.ftth.customer.application.port.outbound.CustomerRepository
import com.duluin.ftth.customer.application.service.CustomerReadAccessService
import com.duluin.ftth.customer.domain.model.Customer
import com.duluin.ftth.iam.CurrentAuthority
import com.duluin.ftth.iam.CurrentAuthorityApi
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class CustomerReadAccessServiceTest {
    private val customerId = UUID.randomUUID()
    private val area = UUID.randomUUID()

    @Test
    fun `unrestricted authority may read an unassigned area`() {
        assertThatCode { service(AuthorityScope.Unrestricted, null).requireVisibleCustomer(customerId) }.doesNotThrowAnyException()
    }

    @Test
    fun `restricted authority may read its own area`() {
        assertThatCode { service(AuthorityScope.Restricted(setOf(area)), area).requireVisibleCustomer(customerId) }.doesNotThrowAnyException()
    }

    @Test
    fun `restricted empty different and null areas deny with the same not found contract`() {
        for ((scope, customerArea) in listOf(
            AuthorityScope.Restricted(emptySet()) to area,
            AuthorityScope.Restricted(setOf(area)) to UUID.randomUUID(),
            AuthorityScope.Restricted(setOf(area)) to null,
        )) {
            assertThatThrownBy { service(scope, customerArea).requireVisibleCustomer(customerId) }
                .isInstanceOf(NotFoundException::class.java).hasMessage("Pelanggan $customerId tidak ditemukan")
        }
    }

    @Test
    fun `missing or tenant invisible customer has the same not found contract`() {
        assertThatThrownBy { service(AuthorityScope.Unrestricted, area, present = false).requireVisibleCustomer(customerId) }
            .isInstanceOf(NotFoundException::class.java).hasMessage("Pelanggan $customerId tidak ditemukan")
    }

    private fun service(scope: AuthorityScope, customerArea: UUID?, present: Boolean = true): CustomerReadAccessService {
        val customers = mock(CustomerRepository::class.java)
        if (present) {
            val customer = mock(Customer::class.java)
            `when`(customer.areaId).thenReturn(customerArea)
            `when`(customers.findById(customerId)).thenReturn(customer)
        }
        val authority = mock(CurrentAuthorityApi::class.java)
        `when`(authority.lockCurrent()).thenReturn(CurrentAuthority(mock(AuthorityFence::class.java), emptySet(), emptySet(), scope,
            scope == AuthorityScope.Unrestricted))
        return CustomerReadAccessService(customers, authority)
    }
}
