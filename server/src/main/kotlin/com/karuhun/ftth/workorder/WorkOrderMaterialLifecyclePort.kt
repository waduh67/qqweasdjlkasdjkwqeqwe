package com.karuhun.ftth.workorder

import java.util.UUID

interface WorkOrderMaterialLifecyclePort {
    fun beforeChange(workOrderId: UUID, change: MaterialLifecycleChange)
}

enum class MaterialLifecycleChange { CANCEL, REASSIGN, REWORK, RESUBMIT }
