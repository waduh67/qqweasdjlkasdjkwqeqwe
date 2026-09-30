package com.duluin.ftth.customer

import java.util.UUID

interface CustomerReadAccessApi {
    fun requireVisibleCustomer(customerId: UUID)
}
