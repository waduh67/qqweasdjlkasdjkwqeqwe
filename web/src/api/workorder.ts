/** Work order sisi operator/dispatcher (module `workorder`). */

export type WorkOrderType = 'PSB' | 'REPAIR' | 'MIGRATION' | 'DISMANTLE' | 'PREVENTIVE'
export type WorkOrderStatus = 'DRAFT' | 'ASSIGNED' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
export type WorkOrderPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

export interface WorkOrderView {
  id: string
  code: string
  type: WorkOrderType
  status: WorkOrderStatus
  priority: WorkOrderPriority
  title: string
  description: string | null
  customerId: string | null
  customerName: string | null
  incidentId: string | null
  areaId: string | null
  assignedTo: string | null
  assignedToName: string | null
  scheduledAt: string | null
  assignedAt: string | null
  startedAt: string | null
  completedAt: string | null
  resolutionNote: string | null
  cancelReason: string | null
  createdAt: string
}

export interface WorkOrderEventView {
  type: string
  message: string
  at: string
}

export interface WorkOrderDetail {
  workOrder: WorkOrderView
  timeline: WorkOrderEventView[]
}
