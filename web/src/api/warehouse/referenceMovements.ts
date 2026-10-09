import { boolean, decimal, integer, nullable, oneOf, pageOf, plainText, record, text, timestamp, uuid, WarehouseDataError } from './codec'
import { conversion, cost, type ReceiptDraftLineInput } from './receipts'
import { referenceCommand } from './reference'
import { parameters, query } from './transport'

export const MOVEMENT_KINDS = ['RECEIPT', 'TRANSFER'] as const
export type ReferenceMovementKind = typeof MOVEMENT_KINDS[number]
const root = '/api/v2/warehouse'
export function movementPosted(value: unknown) {
  const row = record(value)
  const result = { id: uuid(row.id), operationId: uuid(row.operationId), revision: integer(row.revision), kind: oneOf(row.kind, MOVEMENT_KINDS),
    state: oneOf(row.state, ['PUTAWAY', 'RECEIVED']), warehouseId: uuid(row.warehouseId),
    sourceWarehouseId: nullable(row.sourceWarehouseId, uuid, 'sourceWarehouseId'), notes: plainText(row.notes), recordedAt: timestamp(row.recordedAt) }
  if (result.revision < 1 || (result.kind === 'RECEIPT' ? result.state !== 'PUTAWAY' || result.sourceWarehouseId !== null
    : result.state !== 'RECEIVED' || !result.sourceWarehouseId || result.sourceWarehouseId === result.warehouseId)) throw new WarehouseDataError('movementPosted')
  return result
}
export type ReferenceMovementPosted = ReturnType<typeof movementPosted>
export function movementSummary(value: unknown) {
  const row = record(value)
  return { ...movementPosted(value), code: text(row.code), actorName: text(row.actorName), warehouseName: text(row.warehouseName),
    sourceWarehouseName: nullable(row.sourceWarehouseName, text, 'sourceWarehouseName'), supplierName: nullable(row.supplierName, text, 'supplierName'),
    reference: nullable(row.reference, text, 'reference'), costVisible: boolean(row.costVisible) }
}
export type ReferenceMovement = ReturnType<typeof movementSummary>
export function movementLine(value: unknown) {
  const row = record(value)
  const line = { id: uuid(row.id), lineNumber: integer(row.lineNumber), skuId: uuid(row.skuId), skuCode: text(row.skuCode), skuName: text(row.skuName),
    tracking: oneOf(row.tracking, ['BULK', 'LOT', 'SERIAL']), baseUnit: oneOf(row.baseUnit, ['EA', 'MM']), quantityBase: decimal(row.quantityBase),
    serial: nullable(row.serial, text, 'serial'), mac: nullable(row.mac, text, 'mac'), lotCode: nullable(row.lotCode, text, 'lotCode'),
    conversion: nullable(row.conversion, conversion, 'conversion'), cost: nullable(row.cost, cost, 'cost') }
  if (line.lineNumber < 1 || BigInt(line.quantityBase) <= 0n || (line.tracking === 'SERIAL' && (line.baseUnit !== 'EA' || line.quantityBase !== '1' || !line.serial))) throw new WarehouseDataError('movementLine')
  if (line.conversion && BigInt(line.conversion.numerator) * BigInt(line.conversion.packageQuantity) % BigInt(line.conversion.denominator) !== 0n) throw new WarehouseDataError('movementLine.conversion')
  return line
}
export type ReferenceMovementLine = ReturnType<typeof movementLine>
export const listReferenceMovements = (kind: ReferenceMovementKind, search = '', page = 0) =>
  query(`${root}/movements${parameters({ kind, search, page })}`, pageOf(movementSummary))
export const getReferenceMovement = (id: string) => query(`${root}/movements/${uuid(id)}`, movementSummary)
export const referenceMovementLines = (id: string, page = 0) => query(`${root}/movements/${uuid(id)}/lines${parameters({ page })}`, pageOf(movementLine))
export const postReferenceReceipt = (input: { readonly warehouseId: string; readonly lines: readonly ReceiptDraftLineInput[]; readonly notes: string; readonly supplierId?: string; readonly reference?: string }) =>
  referenceCommand(`${root}/receipts`, 'POST', input, movementPosted)
export const postReferenceTransfer = (input: { readonly sourceWarehouseId: string; readonly warehouseId: string; readonly lines: readonly { readonly stockIdentityId: string; readonly quantityBase: string }[]; readonly notes: string }) =>
  referenceCommand(`${root}/transfers`, 'POST', input, movementPosted)
