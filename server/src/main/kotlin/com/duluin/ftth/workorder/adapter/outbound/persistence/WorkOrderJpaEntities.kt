package com.duluin.ftth.workorder.adapter.outbound.persistence

import com.duluin.ftth.common.infrastructure.persistence.TenantAwareJpaEntity
import com.duluin.ftth.workorder.domain.model.WorkOrderApprovalStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderEventType
import com.duluin.ftth.workorder.domain.model.WorkOrderPriority
import com.duluin.ftth.workorder.domain.model.WorkOrderStatus
import com.duluin.ftth.workorder.domain.model.WorkOrderType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "work_order")
class WorkOrderJpaEntity(
    id: UUID,

    @Column(nullable = false, length = 20, updatable = false)
    var code: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var type: WorkOrderType,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(length = 2000)
    var description: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var priority: WorkOrderPriority,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: WorkOrderStatus,

    @Column(name = "customer_id")
    var customerId: UUID?,

    // Ditautkan sekali saat WO dibuka & tak berubah (updatable=false) — penyelesaian WO
    // yang menggerakkan lifecycle langganan, bukan sebaliknya.
    @Column(name = "subscription_id", updatable = false)
    var subscriptionId: UUID?,

    @Column(name = "incident_id")
    var incidentId: UUID?,

    @Column(name = "area_id")
    var areaId: UUID?,

    @Column(name = "assigned_to")
    var assignedTo: UUID?,

    @Column(name = "assigned_at")
    var assignedAt: Instant?,

    @Column(name = "scheduled_at")
    var scheduledAt: Instant?,

    @Column(name = "started_at")
    var startedAt: Instant?,

    @Column(name = "completed_at")
    var completedAt: Instant?,

    @Column(name = "resolution_note", length = 2000)
    var resolutionNote: String?,

    @Column(name = "cancel_reason", length = 500)
    var cancelReason: String?,

    @Column(name = "rx_before_dbm")
    var rxBeforeDbm: Double?,

    @Column(name = "rx_after_dbm")
    var rxAfterDbm: Double?,

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", length = 20)
    var approvalStatus: WorkOrderApprovalStatus?,

    @Column(name = "approved_by")
    var approvedBy: UUID?,

    @Column(name = "approved_at")
    var approvedAt: Instant?,

    @Column(name = "approval_note", length = 500)
    var approvalNote: String?,

    // Null = dibuat sistem (mis. WO preventif dari degradasi optik), tanpa pengguna.
    @Column(name = "created_by", updatable = false)
    var createdBy: UUID?,
) : TenantAwareJpaEntity(id)

@Entity
@Table(name = "wo_event")
class WorkOrderEventJpaEntity(
    id: UUID,

    @Column(name = "work_order_id", nullable = false, updatable = false)
    var workOrderId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    var type: WorkOrderEventType,

    @Column(nullable = false, length = 500, updatable = false)
    var message: String,

    @Column(name = "actor_id", updatable = false)
    var actorId: UUID?,

    @Column(nullable = false, updatable = false)
    var at: Instant,
) : TenantAwareJpaEntity(id)
