package com.duluin.ftth.payroll.domain

import com.duluin.ftth.common.domain.error.AccessDeniedException
import com.duluin.ftth.common.domain.error.ConflictException
import com.duluin.ftth.common.domain.error.ValidationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmInline
value class MinorUnit(val value: Long) {
    operator fun plus(other: MinorUnit) = MinorUnit(Math.addExact(value, other.value))
    operator fun minus(other: MinorUnit) = MinorUnit(Math.subtractExact(value, other.value))
    init { require(value >= 0) { "money cannot be negative" } }
}

data class Money(val currency: String, val minor: MinorUnit) {
    init { require(currency.matches(Regex("[A-Z]{3}"))) }
    operator fun plus(other: Money): Money { sameCurrency(other); return Money(currency, minor + other.minor) }
    operator fun minus(other: Money): Money { sameCurrency(other); return Money(currency, minor - other.minor) }
    fun percent(rate: BigDecimal, scale: Int = 0): Money {
        require(rate >= BigDecimal.ZERO && rate <= BigDecimal("100"))
        val amount = BigDecimal(minor.value).multiply(rate).divide(BigDecimal("100"), scale, RoundingMode.HALF_UP)
        return Money(currency, MinorUnit(amount.setScale(0, RoundingMode.HALF_UP).longValueExact()))
    }
    private fun sameCurrency(other: Money) { require(currency == other.currency) { "currency mismatch" } }
}

enum class ComponentKind { BASE_SALARY, OVERTIME, ALLOWANCE, BONUS }
enum class DeductionKind { TAX, LEAVE, OTHER }

data class EffectiveCompensation(
    val id: UUID,
    val tenantId: UUID,
    val employeeId: UUID,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val monthlyBase: Money,
    val hourlyRate: Money,
) {
    init { require(validTo == null || !validTo.isBefore(validFrom)); require(monthlyBase.currency == hourlyRate.currency) }
    fun applies(date: LocalDate) = !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo))
}

data class PayComponent(
    val code: String,
    val kind: ComponentKind,
    val amount: Money,
    val effectiveFrom: LocalDate,
    val effectiveTo: LocalDate?,
) { init { require(code.matches(Regex("[a-z][a-z0-9_]{1,31}"))); require(effectiveTo == null || !effectiveTo.isBefore(effectiveFrom)) } }

data class DeductionRule(val code: String, val kind: DeductionKind, val rate: BigDecimal? = null, val fixed: Money? = null) {
    init { require((rate == null) xor (fixed == null)); require(rate == null || rate >= BigDecimal.ZERO) }
}

data class PayCalendar(val id: UUID, val tenantId: UUID, val from: LocalDate, val to: LocalDate, val payDate: LocalDate, var closedAt: Instant? = null) {
    init { require(!to.isBefore(from)); require(!payDate.isBefore(to)) }
    fun requireOpen() { if (closedAt != null) throw ConflictException("pay period is closed") }
    fun close(at: Instant) { requireOpen(); closedAt = at }
}

data class PayrollInput(
    val tenantId: UUID,
    val employeeId: UUID,
    val period: PayCalendar,
    val compensation: EffectiveCompensation,
    val components: List<PayComponent>,
    val approvedAttendanceDays: Int,
    val overtimeHours: BigDecimal,
    val leaveDays: BigDecimal,
    val deductions: List<DeductionRule>,
    val tax: DeductionRule,
) {
    init { require(approvedAttendanceDays >= 0); require(overtimeHours >= BigDecimal.ZERO); require(leaveDays >= BigDecimal.ZERO); require(tax.kind == DeductionKind.TAX) }
}

data class PayrollLine(val code: String, val kind: String, val amount: Money)

data class CalculationSnapshot(
    val id: UUID,
    val tenantId: UUID,
    val employeeId: UUID,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val compensationId: UUID,
    val hrisSessionIds: List<UUID>,
    val lines: List<PayrollLine>,
    val gross: Money,
    val deductions: Money,
    val tax: Money,
    val net: Money,
    val calculatedAt: Instant,
) { val immutableLines: List<PayrollLine> get() = lines.toList() }

object PayrollCalculator {
    fun calculate(input: PayrollInput, attendanceSessionIds: List<UUID>, now: Instant = Instant.now()): CalculationSnapshot {
        input.period.requireOpen()
        if (!input.compensation.applies(input.period.from)) throw ValidationException("no compensation effective at period start")
        val currency = input.compensation.monthlyBase.currency
        val periodDays = input.period.from.until(input.period.to).days.toLong() + 1
        val attendedDays = input.approvedAttendanceDays.coerceAtMost(periodDays.toInt())
        val baseMinor = BigDecimal(input.compensation.monthlyBase.minor.value).multiply(BigDecimal(attendedDays)).divide(BigDecimal(periodDays), 0, RoundingMode.HALF_UP).longValueExact()
        val lines = mutableListOf(PayrollLine("base_salary", ComponentKind.BASE_SALARY.name, Money(currency, MinorUnit(baseMinor))))
        input.components.filter { !it.effectiveFrom.isAfter(input.period.to) && (it.effectiveTo == null || !it.effectiveTo.isBefore(input.period.from)) }
            .forEach { lines += PayrollLine(it.code, it.kind.name, it.amount) }
        if (input.overtimeHours.signum() > 0) {
            val overtime = Money(currency, MinorUnit(input.compensation.hourlyRate.minor.value.toBigDecimal().multiply(input.overtimeHours).setScale(0, RoundingMode.HALF_UP).longValueExact()))
            lines += PayrollLine("overtime", ComponentKind.OVERTIME.name, overtime)
        }
        val gross = lines.map { it.amount }.fold(Money(currency, MinorUnit(0)), Money::plus)
        val leave = input.compensation.monthlyBase.percent(input.leaveDays.divide(BigDecimal(periodDays), 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")))
        val deductionLines = input.deductions.map { rule -> PayrollLine(rule.code, rule.kind.name, rule.fixed ?: gross.percent(rule.rate!!)) }
        val deductions = deductionLines.map { it.amount }.fold(leave, Money::plus)
        val tax = input.tax.fixed ?: gross.percent(input.tax.rate!!)
        if (deductions.minor.value + tax.minor.value > gross.minor.value) throw ValidationException("deductions exceed gross pay")
        val net = gross - deductions - tax
        return CalculationSnapshot(UUID.randomUUID(), input.tenantId, input.employeeId, input.period.from, input.period.to, input.compensation.id, attendanceSessionIds.toList(), lines + deductionLines, gross, deductions, tax, net, now)
    }
}

enum class PayrollRunState { DRAFT, CALCULATED, REVIEWED, APPROVED, PAID, VOIDED }
enum class ApprovalDecision { APPROVE, REJECT }
data class ApprovalTier(val number: Int, val approverIds: Set<UUID>) { init { require(number > 0 && approverIds.isNotEmpty()) } }
data class ApprovalSnapshot(val tier: Int, val approverId: UUID, val decision: ApprovalDecision, val decidedAt: Instant, val reason: String?)
data class PayrollRunOutcomeSnapshot(val id: UUID, val tenantId: UUID, val requesterId: UUID, val period: PayCalendar, val operationKey: String, val payloadHash: String, val state: PayrollRunState, val snapshot: CalculationSnapshot?, val tiers: List<ApprovalTier>, val approvals: List<ApprovalSnapshot>)

class PayrollRun private constructor(
    val id: UUID, val tenantId: UUID, val requesterId: UUID, val period: PayCalendar, val operationKey: String, val payloadHash: String,
    var state: PayrollRunState, var snapshot: CalculationSnapshot?, val tiers: List<ApprovalTier>, val approvals: MutableList<ApprovalSnapshot>,
) {
    fun outcomeSnapshot() = PayrollRunOutcomeSnapshot(id, tenantId, requesterId, period, operationKey, payloadHash, state, snapshot, tiers, approvals.toList())
    fun calculated(snapshot: CalculationSnapshot) { requireState(PayrollRunState.DRAFT); this.snapshot = snapshot; state = PayrollRunState.CALCULATED }
    fun review(actorId: UUID, at: Instant) { requireState(PayrollRunState.CALCULATED); if (snapshot == null) throw ConflictException("calculation snapshot is required"); state = PayrollRunState.REVIEWED }
    fun approve(actorId: UUID, at: Instant, reason: String? = null) {
        requireState(PayrollRunState.REVIEWED)
        if (actorId == requesterId || snapshot?.employeeId == actorId) throw AccessDeniedException("maker-checker approval is required")
        val tier = tiers.firstOrNull { required -> approvals.none { it.tier == required.number && it.decision == ApprovalDecision.APPROVE } } ?: throw ConflictException("run is already approved")
        if (actorId !in tier.approverIds) throw AccessDeniedException("actor cannot approve this tier")
        approvals += ApprovalSnapshot(tier.number, actorId, ApprovalDecision.APPROVE, at, reason)
        if (tiers.all { required -> approvals.any { it.tier == required.number && it.decision == ApprovalDecision.APPROVE } }) state = PayrollRunState.APPROVED
    }
    fun pay(at: Instant) { requireState(PayrollRunState.APPROVED); if (period.closedAt == null) throw ConflictException("pay period must be closed"); state = PayrollRunState.PAID }
    fun void(actorId: UUID, at: Instant, reason: String) { requireState(PayrollRunState.PAID); if (reason.isBlank()) throw ValidationException("void reason is required"); state = PayrollRunState.VOIDED }
    private fun requireState(expected: PayrollRunState) { if (state != expected) throw ConflictException("run must be $expected, was $state") }
    companion object {
        fun draft(tenantId: UUID, requesterId: UUID, period: PayCalendar, operationKey: String, payloadHash: String, tiers: List<ApprovalTier>) = PayrollRun(UUID.randomUUID(), tenantId, requesterId, period, operationKey, payloadHash, PayrollRunState.DRAFT, null, tiers, mutableListOf())
        fun rehydrate(id: UUID, tenantId: UUID, requesterId: UUID, period: PayCalendar, operationKey: String, payloadHash: String, state: PayrollRunState, snapshot: CalculationSnapshot?, tiers: List<ApprovalTier>, approvals: List<ApprovalSnapshot>) = PayrollRun(id, tenantId, requesterId, period, operationKey, payloadHash, state, snapshot, tiers, approvals.toMutableList())
    }
}
