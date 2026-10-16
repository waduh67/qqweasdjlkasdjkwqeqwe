package com.karuhun.ftth.portal

import java.util.UUID

interface PortalCustomerSession {
    fun currentCustomerId(): UUID
}
