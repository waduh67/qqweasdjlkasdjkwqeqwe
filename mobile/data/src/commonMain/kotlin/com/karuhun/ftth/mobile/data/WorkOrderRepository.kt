package com.karuhun.ftth.mobile.data

import com.karuhun.ftth.mobile.domain.WorkOrder
import com.karuhun.ftth.mobile.domain.WorkOrderPort

class WorkOrderRepository(private val gateway: WorkOrderGateway) : WorkOrderPort {
    override suspend fun list() = gateway.list()
    override suspend fun detail(id: String) = gateway.detail(id)
}

interface WorkOrderGateway {
    suspend fun list(): Result<List<WorkOrder>>
    suspend fun detail(id: String): Result<WorkOrder>
}
