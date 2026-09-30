import { array, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { timestamp } from './approvals'
import { CONDITIONS, LEGAL_OWNERS, quantity } from './models'
import { ASSET_STATES, CUSTODIAN_KINDS, stockCost } from './stock'

export const signedDecimal = (value: unknown, path?: string) => decimal(value, path, true)
export function currency(value: unknown, path = 'currency') {
  const result = text(value, path)
  if (!/^[A-Z]{3}$/.test(result)) throw new WarehouseDataError(path)
  return result
}
function dimensions(row: Record<string, unknown>, path: string) {
  return { id: uuid(row.id, path), skuId: uuid(row.skuId, path), name: text(row.name, path),
    stockIdentityId: uuid(row.stockIdentityId, path), serial: nullable(row.serial, text, path), locationId: uuid(row.locationId, path),
    locationName: nullable(row.locationName, text, path), custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, CUSTODIAN_KINDS, path),
    status: oneOf(row.status, ASSET_STATES, path), condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), quantity: quantity(row.quantity, path) }
}
export function reportMovement(value: unknown, path = 'movement') {
  const row = record(value, path)
  return { ...dimensions(row, path), postingId: uuid(row.postingId, path), movementKind: text(row.movementKind, path),
    documentId: uuid(row.documentId, path), documentCode: text(row.documentCode, path), documentRevision: integer(row.documentRevision, path),
    lineId: nullable(row.lineId, uuid, path), compensatesPostingId: nullable(row.compensatesPostingId, uuid, path),
    recordedAt: timestamp(row.recordedAt, path), direction: oneOf(row.direction, ['IN', 'OUT'], path) }
}
export type ReportMovement = ReturnType<typeof reportMovement>
export function reportCard(value: unknown, path = 'card') {
  const row = record(value, path)
  return { ...reportMovement(value, path), openingQuantityBase: signedDecimal(row.openingQuantityBase, path), closingQuantityBase: signedDecimal(row.closingQuantityBase, path) }
}
export function reportCustody(value: unknown, path = 'custody') {
  const row = record(value, path)
  return { ...dimensions(row, path), enteredAt: nullable(row.enteredAt, timestamp, path), ageSeconds: nullable(row.ageSeconds, decimal, path) }
}
export function reportAssignment(value: unknown, path = 'assignment') {
  const row = record(value, path)
  return { id: uuid(row.id, path), assignmentId: uuid(row.assignmentId, path), revision: integer(row.revision, path), assetId: uuid(row.assetId, path),
    skuId: uuid(row.skuId, path), name: text(row.name, path), serial: text(row.serial, path), workOrderId: uuid(row.workOrderId, path), handoverId: nullable(row.handoverId, uuid, path),
    ownershipMode: oneOf(row.ownershipMode, ['LOAN', 'SALE'], path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path), status: oneOf(row.status, ASSET_STATES, path),
    locationId: uuid(row.locationId, path), assignmentState: oneOf(row.assignmentState, ['APPROVED_LOSS', 'RECOVERED', 'CLOSED', 'PENDING_HANDOVER', 'ACTIVE'], path),
    startedAt: timestamp(row.startedAt, path), endedAt: nullable(row.endedAt, timestamp, path) }
}
export function reportCost(value: unknown, path = 'cost') {
  const row = record(value, path), costState = oneOf(row.costState, ['KNOWN', 'UNKNOWN'], path)
  const values = { sourceTotalMinor: nullable(row.sourceTotalMinor, decimal, path), sourceBasisQuantityBase: nullable(row.sourceBasisQuantityBase, decimal, path),
    currency: nullable(row.currency, currency, path), lineTotalMinor: nullable(row.lineTotalMinor, signedDecimal, path) }
  if (costState === 'UNKNOWN' ? Object.values(values).some(v => v !== null) : Object.values(values).some(v => v === null) || BigInt(values.sourceBasisQuantityBase!) <= 0n) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), workOrderId: uuid(row.workOrderId, path), workOrderCode: nullable(row.workOrderCode, text, path),
    postingId: uuid(row.postingId, path), compensatesPostingId: nullable(row.compensatesPostingId, uuid, path), documentId: uuid(row.documentId, path),
    documentRevision: integer(row.documentRevision, path), lineId: nullable(row.lineId, uuid, path), recordedAt: timestamp(row.recordedAt, path),
    skuId: uuid(row.skuId, path), name: text(row.name, path), stockIdentityId: uuid(row.stockIdentityId, path), quantityBase: signedDecimal(row.quantityBase, path),
    baseUnit: oneOf(row.baseUnit, ['EA', 'MM'], path), costState, ...values, rounding: oneOf(row.rounding, ['HALF_UP'], path) }
}
export function reportCosts(value: unknown, path = 'costs') {
  const row = record(value, path)
  return { ...pageOf(reportCost)(value, path), scope: oneOf(row.scope, ['VISIBLE_LOCATIONS'], path), costBasis: oneOf(row.costBasis, ['OPERATIONAL_USE'], path),
    currencyTotals: array(row.currencyTotals, (v, p) => { const r = record(v, p); return { currency: currency(r.currency, p), totalMinor: signedDecimal(r.totalMinor, p) } }, path),
    unknownQuantities: array(row.unknownQuantities, (v, p) => { const r = record(v, p); return { baseUnit: oneOf(r.baseUnit, ['EA', 'MM'], p), quantityBase: signedDecimal(r.quantityBase, p) } }, path, 2) }
}
export function reportPrint(value: unknown, path = 'print') {
  const row = record(value, path)
  return { documentId: uuid(row.documentId, path), documentCode: text(row.documentCode, path), kind: oneOf(row.kind, ['RECEIPT', 'ISSUE', 'RETURN'], path),
    documentRevision: integer(row.documentRevision, path), state: text(row.state, path), operationId: uuid(row.operationId, path), recordedAt: timestamp(row.recordedAt, path),
    sourceDocumentId: nullable(row.sourceDocumentId, uuid, path), sourceRevision: nullable(row.sourceRevision, integer, path),
    workOrderId: nullable(row.workOrderId, uuid, path), workOrderCode: nullable(row.workOrderCode, text, path),
    supplier: nullable(row.supplier, (v, p) => { const r = record(v, p); return { id: nullable(r.id, uuid, p!), name: nullable(r.name, text, p!) } }, path),
    lines: array(row.lines, (v, p = path) => {
      const r = record(v, p)
      return { lineId: uuid(r.lineId, p), lineNumber: integer(r.lineNumber, p), skuId: uuid(r.skuId, p), skuCode: nullable(r.skuCode, text, p), skuName: nullable(r.skuName, text, p),
        nameState: oneOf(r.nameState, ['NOT_CAPTURED', 'SNAPSHOT'], p), stockIdentityId: nullable(r.stockIdentityId, uuid, p), serial: nullable(r.serial, text, p),
        locationId: nullable(r.locationId, uuid, p), quantity: quantity(r.quantity, p), cost: nullable(r.cost, stockCost, p) }
    }, path) }
}
export type ReportPrint = ReturnType<typeof reportPrint>
