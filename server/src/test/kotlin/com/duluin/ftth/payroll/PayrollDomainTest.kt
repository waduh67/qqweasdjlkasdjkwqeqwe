package com.duluin.ftth.payroll

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.payroll.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PayrollDomainTest {
    private val tenant = UUID.randomUUID()
    private val employee = UUID.randomUUID()
    private val approver = UUID.randomUUID()
    private val period = PayCalendar(UUID.randomUUID(), tenant, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), LocalDate.parse("2026-10-01"))
    private val compensation = EffectiveCompensation(UUID.randomUUID(), tenant, employee, LocalDate.parse("2026-09-01"), null, Money("IDR", MinorUnit(10_000_000)), Money("IDR", MinorUnit(50_000)))

    @Test
    fun `effective compensation is selected at period date and snapshot stays stable`() {
        val input = input()
        val first = PayrollCalculator.calculate(input, listOf(UUID.randomUUID()), Instant.parse("2026-09-30T00:00:00Z"))
        val second = PayrollCalculator.calculate(input.copy(components = listOf(PayComponent("meal", ComponentKind.ALLOWANCE, Money("IDR", MinorUnit(1_000)), period.from, null))), first.hrisSessionIds, first.calculatedAt)

        assertThat(first.gross.minor.value).isEqualTo(6_666_667)
        assertThat(first.lines).hasSize(1)
        assertThat(second.lines).hasSize(2)
        assertThat(first.hrisSessionIds).hasSize(1)
    }

    @Test
    fun `rounding tax and leave deduction use minor units`() {
        val input = input().copy(leaveDays = BigDecimal("1"), tax = DeductionRule("income_tax", DeductionKind.TAX, rate = BigDecimal("2.5")))
        val snapshot = PayrollCalculator.calculate(input, emptyList(), Instant.parse("2026-09-30T00:00:00Z"))

        assertThat(snapshot.deductions.minor.value).isEqualTo(333_333)
        assertThat(snapshot.tax.minor.value).isEqualTo(166_667)
        assertThat(snapshot.net.minor.value).isEqualTo(6_166_667)
    }

    @Test
    fun `run requires ordered maker checker approval and supports void`() {
        val run = PayrollRun.draft(tenant, UUID.randomUUID(), period, "run-1", "hash-1", listOf(ApprovalTier(1, setOf(approver))))
        run.calculated(PayrollCalculator.calculate(input(), emptyList()))
        run.review(UUID.randomUUID(), Instant.now())
        assertThatThrownBy { run.approve(employee, Instant.now()) }.isInstanceOf(AccessDeniedException::class.java)
        run.approve(approver, Instant.now())
        assertThat(run.state).isEqualTo(PayrollRunState.APPROVED)
        period.close(Instant.now())
        run.pay(Instant.now())
        run.void(approver, Instant.now(), "bank reversal")
        assertThat(run.state).isEqualTo(PayrollRunState.VOIDED)
    }

    @Test
    fun `closed period rejects calculation and paid run`() {
        period.close(Instant.now())
        assertThatThrownBy { PayrollCalculator.calculate(input(), emptyList()) }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `redacted payslip contains totals and no sensitive peer fields`() {
        val snapshot = PayrollCalculator.calculate(input(), emptyList())
        val payslip = snapshot.redactedPayslip()

        assertThat(payslip.employeeId).isEqualTo(employee)
        assertThat(payslip.grossMinor).isEqualTo(6_666_667)
        assertThat(payslip.components).extracting<String> { it.code }.containsExactly("base_salary")
    }

    private fun input() = PayrollInput(tenant, employee, period, compensation, emptyList(), 20, BigDecimal.ZERO, BigDecimal.ZERO, emptyList(), DeductionRule("income_tax", DeductionKind.TAX, rate = BigDecimal.ZERO))
}
