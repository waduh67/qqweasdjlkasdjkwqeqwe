import { api } from './client'

export type InventoryApprovalDecision = 'APPROVE' | 'REJECT'

export interface InventoryLocationView {
  readonly id: string
  readonly code: string
  readonly kind: string
}

export interface InventoryItemView {
  readonly id: string
  readonly skuId: string
  readonly serialNumber: string
  readonly macAddress: string | null
  readonly status: string
}

export interface InventoryStockView {
  readonly skuId: string
  readonly locationId: string
  readonly quantities: Readonly<Record<string, number>>
}

export interface InventoryReservationView {
  readonly assetId: string
  readonly skuId: string
  readonly locationId: string
  readonly custodianId: string
}

export interface InventoryCustodyView {
  readonly assetId: string
  readonly skuId: string
  readonly status: string
  readonly ownerKind: string
  readonly ownerId: string
  readonly locationId: string
}

export interface InventoryApprovalRequest {
  readonly approvalId: string
  readonly type: string
  readonly amount: number
  readonly requesterId: string
  readonly custodianId: string | null
  readonly requestedAt: string
  readonly expiresAt: string
  readonly status: string
  readonly revision: number
  readonly decisions: readonly {
    readonly tier: number
    readonly approverId: string
    readonly decision: InventoryApprovalDecision
    readonly reason: string | null
    readonly decidedAt: string
  }[]
}

export const listWarehouses = () => api.get<InventoryLocationView[]>('/api/inventory/warehouses')
export const listInventoryItems = () => api.get<InventoryItemView[]>('/api/inventory/items')
export const listInventoryStock = () => api.get<InventoryStockView[]>('/api/inventory/stock')
export const listReservations = () => api.get<InventoryReservationView[]>('/api/inventory/reservations')
export const listCustody = () => api.get<InventoryCustodyView[]>('/api/inventory/custody')
export const listPendingApprovals = () => api.get<InventoryApprovalRequest[]>('/api/inventory/approvals/pending')

export const decideInventoryApproval = (id: string, decision: InventoryApprovalDecision, reason: string | null, operationKey: string, operationHash: string) =>
  api.post<InventoryApprovalRequest>(`/api/inventory/approvals/${id}/decision`, {
    decision,
    operationKey,
    operationHash,
    reason,
    movementId: null,
  })
