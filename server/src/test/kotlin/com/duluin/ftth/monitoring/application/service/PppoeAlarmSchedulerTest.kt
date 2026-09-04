package com.duluin.ftth.monitoring.application.service

import com.duluin.ftth.tenancy.TenantApi
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.UUID

class PppoeAlarmSchedulerTest {
    @Test
    fun `disabled scheduler does not enumerate tenants`() {
        val tenants = mock(TenantApi::class.java)
        val evaluator = mock(PppoeAlarmEvaluator::class.java)

        PppoeAlarmScheduler(tenants, evaluator, false).evaluateAll()

        verify(tenants, never()).findActiveTenantIds()
    }

    @Test
    fun `enabled scheduler evaluates every active tenant`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val tenants = mock(TenantApi::class.java)
        val evaluator = mock(PppoeAlarmEvaluator::class.java)
        org.mockito.Mockito.`when`(tenants.findActiveTenantIds()).thenReturn(listOf(first, second))

        PppoeAlarmScheduler(tenants, evaluator, true).evaluateAll()

        verify(evaluator).evaluateTenant(first)
        verify(evaluator).evaluateTenant(second)
    }
}
