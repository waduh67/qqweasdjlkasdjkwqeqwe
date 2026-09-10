package com.duluin.ftth.inventory.application.port.outbound

import com.duluin.ftth.inventory.WarehouseApprovalAttempt
import com.duluin.ftth.inventory.WarehouseApprovalStatus
import java.time.Instant

class ReceiptPostingApproval internal constructor(val attempt: WarehouseApprovalAttempt, val expiresAt: Instant,
    val policyHash: String, val sourceHash: String, val cutoverEpoch: Long, internal val transactionId: String)

class ApprovalPostingStopped(val approval: ReceiptPostingApproval, val status: WarehouseApprovalStatus) :
    RuntimeException("Approval posting stopped: ${status.name}")
