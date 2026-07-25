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
  /** Redaman optik (dBm) sebelum & sesudah pengerjaan; `null` bila belum diukur. */
  rxBeforeDbm: number | null
  rxAfterDbm: number | null
  createdAt: string
}

export interface WorkOrderEventView {
  type: string
  message: string
  at: string
}

export interface TechnicianWorkloadView {
  technicianId: string
  technicianName: string | null
  openCount: number
}

/** Ringkasan papan dispatch (`GET /api/work-orders/dashboard`). */
export interface WorkOrderDashboardView {
  total: number
  open: number
  unassignedOpen: number
  byStatus: Record<WorkOrderStatus, number>
  byType: Record<WorkOrderType, number>
  workloads: TechnicianWorkloadView[]
}

export interface WorkOrderDetail {
  workOrder: WorkOrderView
  timeline: WorkOrderEventView[]
}

/** Bukti pengerjaan (Phase 4.2): foto & tanda tangan. Byte diambil via `api.blob`. */
export type EvidenceKind = 'BEFORE' | 'AFTER' | 'LOCATION' | 'SERIAL' | 'OTHER'

export interface EvidenceView {
  id: string
  workOrderId: string
  kind: EvidenceKind
  caption: string | null
  contentType: string
  sizeBytes: number
  latitude: number | null
  longitude: number | null
  capturedAt: string | null
  uploadedBy: string
  uploadedByName: string | null
  createdAt: string
}

export interface SignatureView {
  id: string
  workOrderId: string
  signerName: string
  contentType: string
  sizeBytes: number
  signedBy: string
  signedByName: string | null
  signedAt: string
  createdAt: string
}
