package com.duluin.ftth.mobile.data

import com.duluin.ftth.mobile.domain.WorkOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkOrderRepositoryTest {
    @Test
    fun adapterContractPassesTypedResults() = kotlinx.coroutines.test.runTest {
        val order = WorkOrder("wo-1", "Repair", "Jl. Merdeka")
        val repository = WorkOrderRepository(object : WorkOrderGateway {
            override suspend fun list() = Result.success(listOf(order))
            override suspend fun detail(id: String) = Result.success(order)
        })
        assertEquals(order, repository.detail("wo-1").getOrThrow())
    }
}
