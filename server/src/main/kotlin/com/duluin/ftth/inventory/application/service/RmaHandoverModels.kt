package com.duluin.ftth.inventory.application.service

import com.duluin.ftth.inventory.*
import java.util.UUID

data class RmaHandoverOrigin(val repairCaseId: UUID, val repairRevision: Long, val originalAssignmentId: UUID,
    val customerId: UUID, val assetRevision: Long, val source: WarehouseReturnSource)
data class RmaHandoverRecord(val request: CustomerRmaDispatch, val origin: RmaHandoverOrigin, val view: CustomerRmaHandover)
