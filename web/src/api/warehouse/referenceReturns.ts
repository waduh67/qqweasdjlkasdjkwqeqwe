import { array, decimal, integer, nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid, WarehouseDataError } from './codec'
import { referenceCommand } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/warehouse/returns'
export const RETURN_STATES = ['PENDING', 'RECEIVED', 'REJECTED'] as const
export const RETURN_LABELS = { PENDING: 'Menunggu penerimaan', RECEIVED: 'Diterima gudang', REJECTED: 'Ditolak' } as const
export function referenceReturn(value: unknown) {
  const row = record(value)
  const result = { id: uuid(row.id), revision: integer(row.revision), state: oneOf(row.state, RETURN_STATES),
    technicianId: uuid(row.technicianId), technicianName: text(row.technicianName), sourceLocationId: uuid(row.sourceLocationId),
    warehouseId: uuid(row.warehouseId), warehouseName: text(row.warehouseName), skuId: uuid(row.skuId), skuName: text(row.skuName),
    baseUnit: oneOf(row.baseUnit, ['EA', 'MM']), tracking: oneOf(row.tracking, ['BULK', 'LOT', 'SERIAL']), quantityBase: decimal(row.quantityBase),
    reason: text(row.reason), createdAt: timestamp(row.createdAt), updatedAt: timestamp(row.updatedAt),
    reviewerName: nullable(row.reviewerName, text, 'reviewerName'), reviewNotes: nullable(row.reviewNotes, plainText, 'reviewNotes'),
    movementId: nullable(row.movementId, uuid, 'movementId'), lines: array(row.lines, value => {
      const line = record(value)
      return { stockIdentityId: uuid(line.stockIdentityId), quantityBase: decimal(line.quantityBase),
        serial: nullable(line.serial, text, 'serial'), mac: nullable(line.mac, text, 'mac') }
    }, 'lines', 100) }
  if (result.lines.length === 0 || new Set(result.lines.map(line => line.stockIdentityId)).size !== result.lines.length ||
    result.lines.some(line => BigInt(line.quantityBase) <= 0n || result.tracking === 'SERIAL' && (line.quantityBase !== '1' || !line.serial)) ||
    result.lines.reduce((sum, line) => sum + BigInt(line.quantityBase), 0n) !== BigInt(result.quantityBase)) throw new WarehouseDataError('return.lines')
  return result
}
export type ReferenceReturn = ReturnType<typeof referenceReturn>
export function referenceReturnDetail(value: unknown) {
  const row = record(value)
  return { request: referenceReturn(row.request), timeline: array(row.timeline, value => {
    const event = record(value)
    return { id: uuid(event.id), revision: integer(event.revision), action: oneOf(event.action, ['SUBMIT', 'DECIDE']),
      actorName: text(event.actorName), notes: plainText(event.notes), recordedAt: timestamp(event.recordedAt) }
  }) }
}
export const listReferenceReturns = (search = '', page = 0, state?: typeof RETURN_STATES[number]) => query(root + parameters({ search, page, state }), pageOf(referenceReturn))
export const getReferenceReturn = (id: string) => query(root + '/' + uuid(id), referenceReturnDetail)
export const submitReferenceReturn = (input: { readonly warehouseId: string; readonly skuId: string; readonly reason: string; readonly lines: readonly { readonly stockIdentityId: string; readonly quantityBase: string }[] }) => referenceCommand(root, 'POST', input, referenceReturn)
export const decideReferenceReturn = (id: string, input: { readonly expectedRevision: number; readonly received: boolean; readonly notes: string }) => referenceCommand(root + '/' + uuid(id) + '/decision', 'POST', input, referenceReturn)
