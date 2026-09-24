import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, record, text, uuid, WarehouseDataError } from './codec'
import { TRACKING } from './models'

export const MATERIAL_MODES = ['NONE', 'MATERIAL_REQUIRED'] as const
export const DEMAND_STATES = ['DRAFT', 'SUBMITTED', 'PART_RESERVED', 'RESERVED', 'PART_ISSUED', 'ISSUED', 'SETTLING', 'CLOSED', 'CANCELLED'] as const
export const ISSUE_STATES = ['DRAFT', 'PICKED', 'UNPICKED', 'DISPATCHED', 'PART_RECEIVED', 'RECEIVED'] as const
export const baseUnit = (value: unknown, path = 'baseUnit') => oneOf(value, ['MM', 'EA'], path)

export function materialSku(value: unknown, path = 'sku') {
  const row = record(value, path)
  return { id: uuid(row.id, path), revision: integer(row.revision, path), code: text(row.code, path), name: text(row.name, path), tracking: oneOf(row.tracking, TRACKING, path), baseUnit: baseUnit(row.baseUnit, path) }
}
export function materialSubstitution(value: unknown, path = 'substitution') {
  const row = record(value, path)
  return { originalPlanLineId: uuid(row.originalPlanLineId, path), originalSkuId: uuid(row.originalSkuId, path), reason: text(row.reason, path) }
}
export type MaterialSubstitution = ReturnType<typeof materialSubstitution>
export function materialPlanLine(value: unknown, path = 'line') {
  const row = record(value, path), amount = decimal(row.quantityBase, path)
  if (BigInt(amount) === 0n) throw new WarehouseDataError(path)
  return { id: uuid(row.id, path), lineNumber: integer(row.lineNumber, path), sku: materialSku(row.sku, path), quantityBase: amount,
    continuousCut: boolean(row.continuousCut, path), substitution: nullable(row.substitution, materialSubstitution, path), originalSku: nullable(row.originalSku, materialSku, path) }
}
export function materialPlan(value: unknown, path = 'plan') {
  const row = record(value, path)
  return { id: uuid(row.id, path), workOrderId: uuid(row.workOrderId, path), workOrderCode: text(row.workOrderCode, path), workType: text(row.workType, path), action: text(row.action, path),
    customerId: nullable(row.customerId, uuid, path), workOrderRevision: integer(row.workOrderRevision, path), planRevision: integer(row.planRevision, path), materialMode: oneOf(row.materialMode, MATERIAL_MODES, path),
    reason: nullable(row.reason, text, path), templateId: nullable(row.templateId, uuid, path), actorId: uuid(row.actorId, path), lines: array(row.lines, materialPlanLine, path, 100), recordedAt: timestamp(row.recordedAt, path) }
}
export type MaterialPlan = ReturnType<typeof materialPlan>
export function materialTemplate(value: unknown, path = 'template') {
  const row = record(value, path)
  return { id: uuid(row.id, path), workType: text(row.workType, path), action: text(row.action, path), revision: integer(row.revision, path), lines: array(row.lines, materialPlanLine, path, 100) }
}
export function materialRevisions(value: unknown, path = 'revisions') {
  const row = record(value, path)
  return { workOrderRevision: integer(row.workOrderRevision, path), planRevision: integer(row.planRevision, path), useRevision: integer(row.useRevision, path), settlementRevision: integer(row.settlementRevision, path) }
}
export function materialTotals(value: unknown, path = 'totals') {
  const row = record(value, path)
  const amounts = { requestedBase: decimal(row.requestedBase, path), reservedUnpickedBase: decimal(row.reservedUnpickedBase, path), reservedPickedBase: decimal(row.reservedPickedBase, path),
    issuedBase: decimal(row.issuedBase, path), physicallyUsedBase: decimal(row.physicallyUsedBase, path), returnedBase: decimal(row.returnedBase, path), transferredOutBase: decimal(row.transferredOutBase, path),
    disposedBase: decimal(row.disposedBase, path), stillAccountableBase: decimal(row.stillAccountableBase, path), backorderBase: decimal(row.backorderBase, path) }
  if (BigInt(amounts.requestedBase) !== BigInt(amounts.reservedUnpickedBase) + BigInt(amounts.reservedPickedBase) + BigInt(amounts.issuedBase) + BigInt(amounts.backorderBase)) throw new WarehouseDataError(path)
  return { planLineId: uuid(row.planLineId, path), demandLineId: nullable(row.demandLineId, uuid, path), skuId: uuid(row.skuId, path), baseUnit: baseUnit(row.baseUnit, path), ...amounts }
}
export type MaterialTotals = ReturnType<typeof materialTotals>
export function materialSummary(value: unknown, path = 'material') {
  const row = record(value, path)
  return { workOrderId: uuid(row.workOrderId, path), materialMode: oneOf(row.materialMode, MATERIAL_MODES, path), noMaterialReason: nullable(row.noMaterialReason, text, path),
    revisions: materialRevisions(row.revisions, path), demandState: oneOf(row.demandState, DEMAND_STATES, path), installationState: oneOf(row.installationState, ['NOT_APPLICABLE', 'NOT_INSTALLED', 'PROVISIONAL', 'INSTALLED', 'REMOVED'], path),
    qaState: oneOf(row.qaState, ['PENDING', 'APPROVED', 'REJECTED'], path), provisioningState: oneOf(row.provisioningState, ['NOT_APPLICABLE', 'PENDING', 'SUCCEEDED', 'FAILED'], path),
    settlementState: oneOf(row.settlementState, ['OPEN', 'RESIDUAL_PENDING', 'CLOSED'], path), lines: array(row.lines, materialTotals, path), plan: nullable(row.plan, materialPlan, path),
    demandDocumentId: nullable(row.demandDocumentId, uuid, path), demandRevision: nullable(row.demandRevision, integer, path), template: nullable(row.template, materialTemplate, path) }
}
export type MaterialSummary = ReturnType<typeof materialSummary>
export function materialHistory(value: unknown, path = 'history') {
  const row = record(value, path)
  return { plan: materialPlan(row.plan, path), state: oneOf(row.state, ['DRAFT', 'SUBMITTED'], path), demandDocumentId: nullable(row.demandDocumentId, uuid, path) }
}
