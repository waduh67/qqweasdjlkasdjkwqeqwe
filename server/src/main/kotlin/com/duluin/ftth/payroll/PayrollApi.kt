package com.duluin.ftth.payroll

import java.time.LocalDate
import java.util.UUID
import com.duluin.ftth.payroll.domain.CalculationSnapshot

/** Public payroll boundary. HRIS and IAM internals never cross this API. */
interface PayrollApi {
    fun payslip(tenantId: UUID, runId: UUID, employeeId: UUID): RedactedPayslip
}

data class RedactedPayslip(
    val runId: UUID,
    val employeeId: UUID,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val currency: String,
    val grossMinor: Long,
    val deductionMinor: Long,
    val taxMinor: Long,
    val netMinor: Long,
    val components: List<RedactedPayslipComponent>,
)

data class RedactedPayslipComponent(val code: String, val amountMinor: Long)

fun CalculationSnapshot.redactedPayslip(): RedactedPayslip = RedactedPayslip(
    runId = id,
    employeeId = employeeId,
    periodFrom = periodFrom,
    periodTo = periodTo,
    currency = gross.currency,
    grossMinor = gross.minor.value,
    deductionMinor = deductions.minor.value,
    taxMinor = tax.minor.value,
    netMinor = net.minor.value,
    components = lines.map { RedactedPayslipComponent(it.code, it.amount.minor.value) },
)
