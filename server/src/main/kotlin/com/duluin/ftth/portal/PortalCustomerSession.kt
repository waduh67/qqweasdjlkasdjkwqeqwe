package com.duluin.ftth.portal

import java.util.UUID

interface PortalCustomerSession {
    fun currentCustomerId(): UUID
}
