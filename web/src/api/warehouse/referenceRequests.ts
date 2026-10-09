import { array, boolean, decimal, integer, nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid, WarehouseDataError } from './codec'
import type { ReceiptDraftLineInput } from './receipts'
import { referenceCommand } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/warehouse'
export const REQUEST_STATES = ['SUBMITTED', 'MANAGER_REVIEW', 'APPROVED', 'REJECTED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'PARTIALLY_FULFILLED', 'FULFILLED'] as const
export type ReferenceRequestState = typeof REQUEST_STATES[number]
export const REQUEST_LABELS = { SUBMITTED: 'Menunggu tinjauan Admin', MANAGER_REVIEW: 'Menunggu Manager', APPROVED: 'Disetujui', REJECTED: 'Ditolak',
  PARTIALLY_RECEIVED: 'Diterima sebagian', RECEIVED: 'Siap diserahkan', PARTIALLY_FULFILLED: 'Terpenuhi sebagian', FULFILLED: 'Terpenuhi' } as const satisfies Record<ReferenceRequestState, string>
export function requestLine(value: unknown) {
  const row = record(value)
  const line = { id: uuid(row.id), baseUnit: oneOf(row.baseUnit, ['EA', 'MM']), requestedBase: decimal(row.requestedBase), skuId: nullable(row.skuId, uuid, 'skuId'),
    name: text(row.name), proposedName: nullable(row.proposedName, text, 'proposedName'), approvedBase: decimal(row.approvedBase), receivedBase: decimal(row.receivedBase), fulfilledBase: decimal(row.fulfilledBase) }
  if (BigInt(line.requestedBase) <= 0n || BigInt(line.approvedBase) > BigInt(line.requestedBase) || BigInt(line.receivedBase) > BigInt(line.approvedBase) || BigInt(line.fulfilledBase) > BigInt(line.approvedBase)) throw new WarehouseDataError('requestLine.quantity')
  return line
}
export function referenceRequest(value: unknown) {
  const row = record(value)
  const result = { id: uuid(row.id), revision: integer(row.revision), kind: oneOf(row.kind, ['RESTOCK', 'PROCUREMENT']), state: oneOf(row.state, REQUEST_STATES),
    requesterId: uuid(row.requesterId), requesterName: text(row.requesterName), warehouseId: nullable(row.warehouseId, uuid, 'warehouseId'), warehouseName: nullable(row.warehouseName, text, 'warehouseName'),
    technicianId: nullable(row.technicianId, uuid, 'technicianId'), technicianName: nullable(row.technicianName, text, 'technicianName'),
    requiresManagerApproval: boolean(row.requiresManagerApproval), policyRevision: integer(row.policyRevision), reason: text(row.reason),
    lines: array(row.lines, requestLine, 'lines', 100), createdAt: timestamp(row.createdAt), updatedAt: timestamp(row.updatedAt) }
  if ((result.warehouseId === null) === (result.technicianId === null) || result.lines.length === 0 ||
    result.warehouseId !== null && (!result.warehouseName || result.kind !== 'PROCUREMENT') ||
    result.technicianId !== null && !result.technicianName) throw new WarehouseDataError('request.destination')
  if (new Set(result.lines.map(line => line.id)).size !== result.lines.length ||
    result.kind === 'PROCUREMENT' && result.lines.some(line => BigInt(line.fulfilledBase) > BigInt(line.receivedBase))) throw new WarehouseDataError('request.lines')
  return result
}
export type ReferenceRequest = ReturnType<typeof referenceRequest>
export type ReferenceRequestLine = ReturnType<typeof requestLine>
export function requestDetail(value: unknown) {
  const row = record(value)
  return { request: referenceRequest(row.request), timeline: array(row.timeline, value => {
    const event = record(value)
    return { operationId: uuid(event.operationId), revision: integer(event.revision), action: oneOf(event.action, ['SUBMIT', 'REVIEW', 'DECIDE', 'RECEIVE', 'HANDOVER']),
      actorName: text(event.actorName), notes: plainText(event.notes), movementId: nullable(event.movementId, uuid, 'movementId'), recordedAt: timestamp(event.recordedAt) }
  }) }
}
export const listReferenceRequests = (search = '', page = 0, state?: ReferenceRequestState) => query(root + '/requests' + parameters({ search, page, state }), pageOf(referenceRequest))
export const getReferenceRequest = (id: string) => query(root + '/requests/' + uuid(id), requestDetail)
export interface RequestLineInput { readonly baseUnit: 'EA' | 'MM'; readonly requestedBase: string; readonly skuId?: string; readonly proposedName?: string }
export interface RequestSubmitInput { readonly kind: 'RESTOCK' | 'PROCUREMENT'; readonly reason: string; readonly lines: readonly RequestLineInput[]; readonly warehouseId?: string; readonly technicianId?: string }
export const submitRequest = (input: RequestSubmitInput) => referenceCommand(root + '/requests', 'POST', input, referenceRequest)
export const reviewRequest = (id: string, input: { readonly expectedRevision: number; readonly notes: string; readonly lines: readonly { readonly lineId: string; readonly approvedBase: string; readonly skuId?: string }[] }) => referenceCommand(root + '/requests/' + uuid(id) + '/review', 'POST', input, referenceRequest)
export const decideRequest = (id: string, input: { readonly expectedRevision: number; readonly approved: boolean; readonly reason: string }) => referenceCommand(root + '/requests/' + uuid(id) + '/decision', 'POST', input, referenceRequest)
export const receiveRequest = (id: string, input: Omit<ReceiptDraftLineInput, 'skuId'> & { readonly expectedRevision: number; readonly lineId: string; readonly warehouseId: string; readonly notes: string; readonly supplierId?: string; readonly reference?: string }) => referenceCommand(root + '/requests/' + uuid(id) + '/receipts', 'POST', input, referenceRequest)
export const handoverRequest = (id: string, input: { readonly expectedRevision: number; readonly lineId: string; readonly warehouseId: string; readonly notes: string; readonly lines: readonly { readonly stockIdentityId: string; readonly quantityBase: string }[] }) => referenceCommand(root + '/requests/' + uuid(id) + '/handovers', 'POST', input, referenceRequest)
export function operationalSettings(value: unknown) {
  const row = record(value), result = { revision: integer(row.revision), requireManagerApproval: boolean(row.requireManagerApproval), overdueDays: integer(row.overdueDays) }
  if (result.overdueDays < 1 || result.overdueDays > 365) throw new WarehouseDataError('settings.overdueDays')
  return result
}
export const getOperationalSettings = () => query(root + '/settings', operationalSettings)
export const saveOperationalSettings = (input: { readonly expectedRevision: number; readonly requireManagerApproval: boolean; readonly overdueDays: number }) => referenceCommand(root + '/settings', 'PUT', input, operationalSettings)
