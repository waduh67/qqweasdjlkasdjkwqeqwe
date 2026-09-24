import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { baseUnit, materialPlan, materialSku, MATERIAL_MODES } from './materialModels'
import { policyChoice } from './policy'
import { command, parameters, query } from './transport'

export function materialFieldContext(value: unknown, path = 'fieldContext') {
  const r = record(value, path)
  return { workOrderId: uuid(r.workOrderId, path), workOrderRevision: integer(r.workOrderRevision, path), plan: nullable(r.plan, materialPlan, path),
    planState: nullable(r.planState, (value, path) => oneOf(value, ['DRAFT', 'SUBMITTED'], path), path), useRevision: integer(r.useRevision, path),
    latestUsageId: nullable(r.latestUsageId, uuid, path), reworkId: nullable(r.reworkId, uuid, path), evidenceRevision: nullable(r.evidenceRevision, text, path) }
}
export type MaterialFieldContext = ReturnType<typeof materialFieldContext>
export function materialCustody(value: unknown, path = 'custody') {
  const r = record(value, path), location = record(r.location, path)
  const result = { id: uuid(r.id, path), receiptId: uuid(r.receiptId, path), issueId: uuid(r.issueId, path), issueCode: text(r.issueCode, path), issueLineId: uuid(r.issueLineId, path),
    planId: uuid(r.planId, path), planLineId: uuid(r.planLineId, path), sku: materialSku(r.sku, path), sourceUsageId: nullable(r.sourceUsageId, uuid, path),
    quantityBase: decimal(r.quantityBase, path), baseUnit: baseUnit(r.baseUnit, path), stockRevision: integer(r.stockRevision, path),
    location: { id: uuid(location.id, path), code: text(location.code, path), name: nullable(location.name, text, path) },
    serial: nullable(r.serial, text, path), lotCode: nullable(r.lotCode, text, path), initialUseSource: boolean(r.initialUseSource, path) }
  if (result.quantityBase === '0' || result.sku.baseUnit !== result.baseUnit || (result.sku.tracking === 'SERIAL' && (result.quantityBase !== '1' || !result.serial))) throw new WarehouseDataError(path)
  return result
}
export type MaterialCustody = ReturnType<typeof materialCustody>
function usageLine(value: unknown, path = 'usageLine') {
  const r = record(value, path)
  return { id: uuid(r.id, path), receiptId: uuid(r.receiptId, path), issueLineId: uuid(r.issueLineId, path), sku: materialSku(r.sku, path), quantityBase: decimal(r.quantityBase, path), baseUnit: baseUnit(r.baseUnit, path), residualBase: decimal(r.residualBase, path) }
}
export function materialUsage(value: unknown, path = 'usage') {
  const r = record(value, path)
  return { id: uuid(r.id, path), workOrderId: uuid(r.workOrderId, path), planId: uuid(r.planId, path), planRevision: integer(r.planRevision, path), useRevision: integer(r.useRevision, path), materialMode: oneOf(r.materialMode, MATERIAL_MODES, path),
    actor: nullable(r.actor, policyChoice, path), evidenceReference: text(r.evidenceReference, path), reason: nullable(r.reason, text, path), recordedAt: timestamp(r.recordedAt, path), lines: array(r.lines, usageLine, path, 100) }
}
const settlementStates = ['OPEN', 'SETTLING', 'CLOSED', 'OVERDUE'] as const
function obligation(value: unknown, path = 'obligation') {
  const r = record(value, path)
  return { issueLineId: uuid(r.issueLineId, path), stockIdentityId: uuid(r.stockIdentityId, path), baseUnit: baseUnit(r.baseUnit, path), issuedBase: decimal(r.issuedBase, path), usedBase: decimal(r.usedBase, path), returnedBase: decimal(r.returnedBase, path),
    transferredBase: decimal(r.transferredBase, path), disposedBase: decimal(r.disposedBase, path), stillAccountableBase: decimal(r.stillAccountableBase, path), transitBase: decimal(r.transitBase, path), acknowledgedBase: decimal(r.acknowledgedBase, path), settledReturnBase: r.settledReturnBase === undefined ? '0' : decimal(r.settledReturnBase, path) }
}
export function materialObligations(value: unknown, path = 'obligations') {
  const r = record(value, path)
  return { workOrderId: uuid(r.workOrderId, path), revision: integer(r.revision, path), materialState: oneOf(r.materialState, settlementStates, path), dueAt: nullable(r.dueAt, timestamp, path), outstandingBase: decimal(r.outstandingBase, path),
    reservedUnpickedBase: decimal(r.reservedUnpickedBase, path), pickedBase: decimal(r.pickedBase, path), lines: array(r.lines, obligation, path) }
}
function materialSettlement(value: unknown, path = 'settlement') {
  const r = record(value, path)
  return { workOrderId: uuid(r.workOrderId, path), technicalState: text(r.technicalState, path), qaState: nullable(r.qaState, text, path), provisioningState: text(r.provisioningState, path),
    materialState: oneOf(r.materialState, settlementStates, path), revision: integer(r.revision, path), outstandingBase: decimal(r.outstandingBase, path), obligations: materialObligations(r.obligations, path) }
}
export type MaterialSettlement = ReturnType<typeof materialSettlement>
export interface MaterialUseLine { receiptId: string; issueLineId: string; stockIdentityId: string; quantityBase: string; baseUnit: 'MM' | 'EA' }
export interface MaterialUseInput { expectedRevision: number; planRevision: number; workOrderRevision: number; materialMode: 'NONE' | 'MATERIAL_REQUIRED'; evidenceReference: string; lines: MaterialUseLine[]; reason?: string }
export interface MaterialUseDeltaInput extends MaterialUseLine { expectedRevision: number; workOrderRevision: number; previousUsageId: string; evidenceReference: string; reason: string; reworkId?: string; evidenceRevision?: string }
const root = (id: string) => `/api/work-orders/${uuid(id)}/materials`
function usageOutcome(value: unknown, path = 'usageOutcome') { const r = record(value, path); return { usageId: uuid(r.usageId, path), workOrderId: uuid(r.workOrderId, path), useRevision: integer(r.useRevision, path) } }
export const getMaterialFieldContext = (id: string) => query(`${root(id)}/workbench`, materialFieldContext)
export const getMaterialCustody = (id: string, page: number) => query(`${root(id)}/workbench/custody${parameters({ page, size: 25 })}`, pageOf(materialCustody))
export const getMaterialUsage = (id: string, page: number) => query(`${root(id)}/workbench/usage${parameters({ page, size: 10 })}`, pageOf(materialUsage))
export const getMaterialSettlement = (id: string) => query(`${root(id)}/settlement`, materialSettlement)
export const reportMaterialUse = (id: string, input: MaterialUseInput) => command(`${root(id)}/report-use`, 'POST', input, usageOutcome)
export const appendMaterialUse = (id: string, input: MaterialUseDeltaInput) => command(`${root(id)}/correct-use`, 'POST', input, usageOutcome)
export const closeMaterialSettlement = (id: string, input: { expectedRevision: number; workOrderRevision: number; reason: string }) => command(`${root(id)}/settlement`, 'POST', input, materialObligations)
