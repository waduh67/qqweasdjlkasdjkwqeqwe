import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import { CONDITIONS, LEGAL_OWNERS } from './models'
import { baseUnit, ISSUE_STATES, materialSku, materialSubstitution } from './materialModels'
import { CUSTODIAN_KINDS } from './stock'

export function issuePerson(value: unknown, path = 'person') {
  const row = record(value, path)
  return { id: uuid(row.id, path), name: text(row.name, path) }
}
export function stockDimension(value: unknown, path = 'dimension') {
  const row = record(value, path)
  return { skuId: uuid(row.skuId, path), stockIdentityId: uuid(row.stockIdentityId, path), lotId: nullable(row.lotId, uuid, path), locationId: uuid(row.locationId, path),
    custodianId: uuid(row.custodianId, path), custodianKind: oneOf(row.custodianKind, CUSTODIAN_KINDS, path), condition: oneOf(row.condition, CONDITIONS, path), legalOwner: oneOf(row.legalOwner, LEGAL_OWNERS, path) }
}
export function issueLine(value: unknown, path = 'line') {
  const row = record(value, path), sku = materialSku(row.sku, path), unit = baseUnit(row.baseUnit, path), amount = decimal(row.quantityBase, path), dimension = stockDimension(row.dimension, path)
  if (unit !== sku.baseUnit || sku.id !== dimension.skuId || BigInt(amount) === 0n) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), demandLineId: uuid(row.demandLineId, path), planLineId: uuid(row.planLineId, path), reservationId: uuid(row.reservationId, path), reservationRevision: integer(row.reservationRevision, path),
    dimension, sourceIdentityId: uuid(row.sourceIdentityId, path), quantityBase: amount, baseUnit: unit, sku, serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path), locationName: text(row.locationName, path),
    substitution: nullable(row.substitution, materialSubstitution, path), originalSku: nullable(row.originalSku, materialSku, path) }
}
export type IssueLine = ReturnType<typeof issueLine>
export function issueSlip(value: unknown, path = 'issue') {
  const row = record(value, path)
  return { issueId: uuid(row.issueId, path), code: text(row.code, path), revision: integer(row.revision, path), state: oneOf(row.state, ISSUE_STATES, path),
    workOrderId: uuid(row.workOrderId, path), workOrderCode: text(row.workOrderCode, path), workOrderRevision: integer(row.workOrderRevision, path),
    customerId: nullable(row.customerId, uuid, path), customerLabelSnapshot: nullable(row.customerLabelSnapshot, text, path), demandDocumentId: uuid(row.demandDocumentId, path), demandRevision: integer(row.demandRevision, path),
    planId: uuid(row.planId, path), planRevision: integer(row.planRevision, path), sender: issuePerson(row.sender, path), receiver: issuePerson(row.receiver, path), lines: array(row.lines, issueLine, path, 100), recordedAt: timestamp(row.recordedAt, path),
    // Older immutable PICKED snapshots predate destination metadata. Never infer a technician destination.
    destinations: row.destinations === undefined ? [] : array(row.destinations, stockDimension, path, 100) }
}
export type IssueSlip = ReturnType<typeof issueSlip>

function issueTotals(value: unknown, path = 'totals') {
  const row = record(value, path), sku = materialSku(row.sku, path), unit = baseUnit(row.baseUnit, path)
  const pickedBase = decimal(row.pickedBase, path), dispatchedBase = decimal(row.dispatchedBase, path), acceptedBase = decimal(row.acceptedBase, path)
  if (unit !== sku.baseUnit || BigInt(acceptedBase) > BigInt(dispatchedBase)) throw new WarehouseDataError(path)
  return { issueLineId: uuid(row.issueLineId, path), planLineId: uuid(row.planLineId, path), sku, serial: nullable(row.serial, text, path), lotCode: nullable(row.lotCode, text, path), locationName: text(row.locationName, path), baseUnit: unit, pickedBase, dispatchedBase, acceptedBase }
}
export function issueRow(value: unknown, path = 'issue') {
  const row = record(value, path)
  return { id: uuid(row.id, path), issueId: uuid(row.issueId, path), code: text(row.code, path), state: oneOf(row.state, ISSUE_STATES, path), revision: integer(row.revision, path), unpicked: boolean(row.unpicked, path),
    workOrderId: uuid(row.workOrderId, path), workOrderCode: text(row.workOrderCode, path), planRevision: integer(row.planRevision, path), sender: issuePerson(row.sender, path), receiver: issuePerson(row.receiver, path),
    createdAt: timestamp(row.createdAt, path), lines: array(row.lines, issueTotals, path, 100) }
}
export type IssueRow = ReturnType<typeof issueRow>
