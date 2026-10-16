package com.karuhun.ftth.subscriber360

import com.karuhun.ftth.billing.BillingApi
import com.karuhun.ftth.bng.BngApi
import com.karuhun.ftth.common.domain.error.NotFoundException
import com.karuhun.ftth.common.infrastructure.security.AccessChecker
import com.karuhun.ftth.common.security.CurrentUserProvider
import com.karuhun.ftth.cpe.CpeApi
import com.karuhun.ftth.customer.CustomerApi
import com.karuhun.ftth.customer.CustomerReadAccessApi
import com.karuhun.ftth.inventory.MaterialConsumptionApi
import com.karuhun.ftth.inventory.MaterialConsumptionApiV2
import com.karuhun.ftth.subscriber360.application.service.Subscriber360Service
import com.karuhun.ftth.workorder.WorkorderApi
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.util.UUID

class Subscriber360AreaBoundaryTest {
    @Test
    fun `owner denial happens before customer projection permission facets or either material query`() {
        val customerId = UUID.randomUUID()
        val access = mock(CustomerReadAccessApi::class.java)
        val failure = NotFoundException("Pelanggan $customerId tidak ditemukan")
        doThrow(failure).`when`(access).requireVisibleCustomer(customerId)
        val customer = mock(CustomerApi::class.java)
        val bng = mock(BngApi::class.java)
        val billing = mock(BillingApi::class.java)
        val cpe = mock(CpeApi::class.java)
        val workorder = mock(WorkorderApi::class.java)
        val permissions = mock(AccessChecker::class.java)
        val user = mock(CurrentUserProvider::class.java)
        val legacy = mock(MaterialConsumptionApi::class.java)
        val measured = mock(MaterialConsumptionApiV2::class.java)
        val service = Subscriber360Service(customer, bng, billing, cpe, workorder, permissions, legacy, user, measured, access)

        assertThatThrownBy { service.assemble(customerId) }.isSameAs(failure)

        verifyNoInteractions(customer, bng, billing, cpe, workorder, permissions, user, legacy, measured)
    }
}
