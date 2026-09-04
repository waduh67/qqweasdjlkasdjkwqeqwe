package com.duluin.ftth.payroll

import com.duluin.ftth.payroll.domain.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class PayrollReplaySnapshotTest {
    @Test
    fun `calculate replay remains calculated after later review`() {
        val run = run()
        run.calculated(PayrollCalculator.calculate(input(run), emptyList()))
        val original = run.outcomeSnapshot()
        run.review(UUID.randomUUID(), Instant.now())
        val replay = PayrollRun.rehydrate(original.id, original.tenantId, original.requesterId, original.period, original.operationKey, original.payloadHash, original.state, original.snapshot, original.tiers, original.approvals)
        assertThat(replay.state).isEqualTo(PayrollRunState.CALCULATED)
    }

    @Test
    fun `paid replay remains paid after void`() {
        val approver = UUID.randomUUID()
        val run = PayrollRun.draft(UUID.randomUUID(), UUID.randomUUID(), PayCalendar(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-01")), "operation-paid", "hash-paid", listOf(ApprovalTier(1, setOf(approver))))
        run.calculated(PayrollCalculator.calculate(input(run), emptyList())); run.review(UUID.randomUUID(), Instant.now()); run.approve(approver, Instant.now())
        run.period.close(Instant.now()); run.pay(Instant.now())
        val original = run.outcomeSnapshot()
        run.void(UUID.randomUUID(), Instant.now(), "reversal")
        val replay = PayrollRun.rehydrate(original.id, original.tenantId, original.requesterId, original.period, original.operationKey, original.payloadHash, original.state, original.snapshot, original.tiers, original.approvals)
        assertThat(replay.state).isEqualTo(PayrollRunState.PAID)
    }

    @Test
    fun `period create replay remains open after close`() {
        val period = PayCalendar(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-01"))
        val original = period.copy()
        period.close(Instant.now())
        assertThat(original.closedAt).isNull()
    }

    private fun run() = PayrollRun.draft(UUID.randomUUID(), UUID.randomUUID(), PayCalendar(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-01")), "operation", "hash", listOf(ApprovalTier(1, setOf(UUID.randomUUID()))))
    private fun input(run: PayrollRun) = PayrollInput(run.tenantId, UUID.randomUUID(), run.period, EffectiveCompensation(UUID.randomUUID(), run.tenantId, UUID.randomUUID(), run.period.from, null, Money("IDR", MinorUnit(100)), Money("IDR", MinorUnit(10))), emptyList(), 31, BigDecimal.ZERO, BigDecimal.ZERO, emptyList(), DeductionRule("tax", DeductionKind.TAX, rate = BigDecimal.ZERO))
}
