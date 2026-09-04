package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.WorkOrder
import com.duluin.ftth.mobile.domain.WorkOrderPort

class WorkOrderRepository(private val gateway: WorkOrderGateway) : WorkOrderPort {
    override suspend fun list() = gateway.list()
    override suspend fun detail(id: String) = gateway.detail(id)
}

interface WorkOrderGateway {
    suspend fun list(): Result<List<WorkOrder>>
    suspend fun detail(id: String): Result<WorkOrder>
}
