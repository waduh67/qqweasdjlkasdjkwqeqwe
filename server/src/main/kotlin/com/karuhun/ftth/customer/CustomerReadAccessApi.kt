package com.karuhun.ftth.customer

import java.util.UUID

interface CustomerReadAccessApi {
    fun requireVisibleCustomer(customerId: UUID)
}
