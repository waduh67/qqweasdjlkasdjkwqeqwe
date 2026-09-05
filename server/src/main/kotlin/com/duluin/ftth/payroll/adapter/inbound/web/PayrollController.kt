package com.duluin.ftth.payroll.adapter.inbound.web

import com.duluin.ftth.payroll.RedactedPayslip
import com.duluin.ftth.payroll.application.service.PayrollCommandService
import com.duluin.ftth.payroll.application.service.PayrollConfigurationService
import com.duluin.ftth.payroll.domain.*
import com.duluin.ftth.common.security.CurrentUserProvider
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/payroll")
class PayrollController(private val payroll: PayrollCommandService, private val configuration: PayrollConfigurationService, private val current: CurrentUserProvider) {
    @PostMapping("/periods")
    @PreAuthorize("@authz.can('payroll.period.manage')")
    fun createPeriod(@RequestBody period: PayPeriodRequest) = configuration.createPeriod(PayCalendar(period.id, current.current().tenantId, period.from, period.to, period.payDate), period.operationKey, period.payloadHash)

    @PostMapping("/periods/{periodId}/close")
    @PreAuthorize("@authz.can('payroll.period.manage')")
    fun closePeriod(@PathVariable periodId: UUID, @RequestBody request: OperationRequest) = configuration.closePeriod(periodId, request.operationKey, request.payloadHash)
    @PostMapping("/runs")
    @PreAuthorize("@authz.can('payroll.run.calculate')")
    fun draft(@RequestBody request: DraftPayrollRequest) = payroll.draft(request.periodId, request.operationKey, request.payloadHash)

    @PostMapping("/runs/{runId}/calculate")
    @PreAuthorize("@authz.can('payroll.run.calculate')")
    fun calculate(@PathVariable runId: UUID, @RequestBody input: CalculatePayrollRequest) = payroll.calculate(runId, input.employeeId, input.periodId, input.operationKey, input.payloadHash)

    @PostMapping("/runs/{runId}/review")
    @PreAuthorize("@authz.can('payroll.run.review')")
    fun review(@PathVariable runId: UUID, @RequestBody request: OperationRequest) = payroll.review(runId, request.operationKey, request.payloadHash)

    @PostMapping("/runs/{runId}/approve")
    @PreAuthorize("@authz.can('payroll.run.approve')")
    fun approve(@PathVariable runId: UUID, @RequestBody request: DecisionRequest) = payroll.approve(runId, request.operationKey, request.payloadHash, request.reason)

    @PostMapping("/runs/{runId}/pay")
    @PreAuthorize("@authz.can('payroll.run.pay')")
    fun pay(@PathVariable runId: UUID, @RequestBody request: PaymentRequest) = payroll.pay(runId, request.operationKey, request.payloadHash)

    @PostMapping("/runs/{runId}/void")
    @PreAuthorize("@authz.can('payroll.run.void')")
    fun void(@PathVariable runId: UUID, @RequestBody request: DecisionRequest) = payroll.void(runId, request.operationKey, request.payloadHash, request.reason ?: "")

    @GetMapping("/payslips/{runId}/{employeeId}")
    @PreAuthorize("@authz.can('payroll.payslip.self') or @authz.can('payroll.payslip.view')")
    fun payslip(@PathVariable runId: UUID, @PathVariable employeeId: UUID): RedactedPayslip = payroll.payslip(current.current().tenantId, runId, employeeId)
}

data class DraftPayrollRequest(val periodId: UUID, val operationKey: String, val payloadHash: String)
data class CalculatePayrollRequest(val employeeId: UUID, val periodId: UUID, val operationKey: String, val payloadHash: String)
data class PayPeriodRequest(val id: UUID, val from: java.time.LocalDate, val to: java.time.LocalDate, val payDate: java.time.LocalDate, val operationKey: String, val payloadHash: String)
data class OperationRequest(val operationKey: String, val payloadHash: String)
data class DecisionRequest(val reason: String? = null, val operationKey: String = "", val payloadHash: String = "")
data class PaymentRequest(val operationKey: String, val payloadHash: String)
