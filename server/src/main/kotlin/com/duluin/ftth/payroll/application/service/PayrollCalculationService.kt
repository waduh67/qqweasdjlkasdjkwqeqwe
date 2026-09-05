package com.duluin.ftth.payroll.application.service

import com.duluin.ftth.hris.HrisApi
import com.duluin.ftth.payroll.domain.CalculationSnapshot
import com.duluin.ftth.payroll.domain.PayrollCalculator
import com.duluin.ftth.payroll.domain.PayrollInput
import java.time.Instant
import org.springframework.stereotype.Service

@Service
class PayrollCalculationService(private val hris: HrisApi) {
    fun calculate(input: PayrollInput, now: Instant = Instant.now()): CalculationSnapshot {
        val facts = hris.approvedAttendance(input.employeeId, input.period.from, input.period.to)
            .filter { it.tenantId == input.tenantId && it.decision.name == "ACCEPTED" }
        val days = input.period.from.until(input.period.to).days + 1
        return PayrollCalculator.calculate(input.copy(approvedAttendanceDays = facts.size, leaveDays = (days - facts.size).coerceAtLeast(0).toBigDecimal()), facts.map { it.sessionId }, now)
    }
}
