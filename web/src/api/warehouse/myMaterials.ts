import { timestamp } from './approvals'
import { array, boolean, decimal, integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { materialCustody, materialFieldContext } from './materialExecution'
import { baseUnit, materialSku } from './materialModels'
import { policyChoice } from './policy'
import { command, parameters, query } from './transport'

export function myMaterialContext(value: unknown, path = 'myMaterials') {
  const r = record(value, path)
  const result = { id: uuid(r.id, path), code: text(r.code, path), workOrderRevision: integer(r.workOrderRevision, path),
    technicalState: text(r.technicalState, path), qaState: nullable(r.qaState, text, path), currentAssignee: boolean(r.currentAssignee, path),
    active: boolean(r.active, path), field: nullable(r.field, materialFieldContext, path) }
  if (result.field && (!result.currentAssignee || result.field.workOrderId !== result.id || result.field.workOrderRevision !== result.workOrderRevision)) throw new WarehouseDataError(path)
  return result
}
export type MyMaterialContext = ReturnType<typeof myMaterialContext>
export function myMaterialIssueLine(value: unknown, path = 'issueLine') {
  const r = record(value, path)
  const result = { id: uuid(r.id, path), stockIdentityId: uuid(r.stockIdentityId, path), sku: materialSku(r.sku, path), baseUnit: baseUnit(r.baseUnit, path),
    dispatchedBase: decimal(r.dispatchedBase, path), acceptedBase: decimal(r.acceptedBase, path), remainingBase: decimal(r.remainingBase, path),
    serial: nullable(r.serial, text, path), lotCode: nullable(r.lotCode, text, path) }
  if (result.sku.baseUnit !== result.baseUnit || BigInt(result.dispatchedBase) !== BigInt(result.acceptedBase) + BigInt(result.remainingBase) ||
    (result.sku.tracking === 'SERIAL' && (!result.serial || result.dispatchedBase !== '1'))) throw new WarehouseDataError(path)
  return result
}
export function myMaterialIssue(value: unknown, path = 'issue') {
  const r = record(value, path)
  return { id: uuid(r.id, path), code: text(r.code, path), workOrderId: uuid(r.workOrderId, path), workOrderRevision: integer(r.workOrderRevision, path),
    revision: integer(r.revision, path), state: oneOf(r.state, ['DISPATCHED', 'PART_RECEIVED', 'RECEIVED'], path),
    sender: policyChoice(r.sender, path), receiver: policyChoice(r.receiver, path), lines: array(r.lines, myMaterialIssueLine, path) }
}
export type MyMaterialIssue = ReturnType<typeof myMaterialIssue>
export function myMaterialLocation(value: unknown, path = 'location') {
  const r = record(value, path)
  return { id: uuid(r.id, path), code: text(r.code, path), name: text(r.name, path) }
}
export function myMaterialResidual(value: unknown, path = 'residual') {
  const r = record(value, path)
  return { id: uuid(r.id, path), code: text(r.code, path), workOrderId: uuid(r.workOrderId, path), revision: integer(r.revision, path),
    state: oneOf(r.state, ['DISPATCHED', 'RECEIVED', 'RECEIVED_IN_INSPECTION'], path), purpose: oneOf(r.purpose, ['RETURN', 'HANDOVER'], path),
    sender: nullable(r.sender, policyChoice, path), receiver: nullable(r.receiver, policyChoice, path), location: myMaterialLocation(r.location, path),
    sku: materialSku(r.sku, path), quantityBase: decimal(r.quantityBase, path), baseUnit: baseUnit(r.baseUnit, path),
    serial: nullable(r.serial, text, path), lotCode: nullable(r.lotCode, text, path), recordedAt: timestamp(r.recordedAt, path) }
}
export type MyMaterialResidual = ReturnType<typeof myMaterialResidual>
export interface MaterialReceiptInput {
  issueId: string; expectedRevision: number; workOrderRevision: number; evidenceReference: string;
  lines: { issueLineId: string; stockIdentityId: string; baseUnit: 'MM' | 'EA'; acceptedBase: string; missingBase: string; rejectedBase: string; reason?: string; serial?: string }[]
}
export interface MaterialReturnInput {
  workOrderRevision: number; receiptId: string; issueLineId: string; stockIdentityId: string; quantityBase: string; baseUnit: 'MM' | 'EA';
  targetLocationId: string; reason: string; evidenceReference: string; usageId?: string; expectedSenderId?: string; expectedReceiverId?: string
}
function bound<T>(value: T, valid: boolean): T { if (!valid) throw new WarehouseDataError('materialReference'); return value }
const root = '/api/v1/warehouse/my-materials'
const own = (id: string) => `${root}/${uuid(id)}`
const materials = (id: string) => `/api/work-orders/${uuid(id)}/materials`
export const getMyMaterialJobs = (page: number) => query(`${root}${parameters({ page, size: 25 })}`, pageOf((value, path = 'job') => {
  const r = record(value, path); return { id: uuid(r.id, path), code: text(r.code, path), updatedAt: timestamp(r.updatedAt, path) }
}))
export const getMyMaterialContext = (id: string) => query(own(id), value => { const row = myMaterialContext(value); return bound(row, row.id === id) })
export const getMyMaterialSource = (id: string, source: string) => query(`${own(id)}/custody/${uuid(source)}`, value => { const row = materialCustody(value); return bound(row, row.id === source) })
export const getMyMaterialIssue = (id: string, issue: string) => query(`${own(id)}/issues/${uuid(issue)}`, value => { const row = myMaterialIssue(value); return bound(row, row.id === issue && row.workOrderId === id) })
export const getMyReturnLocation = (id: string, location: string) => query(`${own(id)}/return-locations/${uuid(location)}`, value => { const row = myMaterialLocation(value); return bound(row, row.id === location) })
export const getMyMaterialCustody = (id: string, page: number) => query(`${own(id)}/custody${parameters({ page, size: 25 })}`, pageOf(materialCustody))
export const getMyMaterialIssues = (id: string, page: number) => query(`${own(id)}/issues${parameters({ page, size: 10 })}`, pageOf(myMaterialIssue))
export const getMyMaterialResiduals = (id: string, page: number) => query(`${own(id)}/residuals${parameters({ page, size: 10 })}`, pageOf(myMaterialResidual))
export const getPendingMaterialReturns = (page: number) => query(`/api/v1/warehouse/material-returns/pending${parameters({ page, size: 10 })}`, pageOf(myMaterialResidual))
export const getMyReturnLocations = (id: string, page: number) => query(`${own(id)}/return-locations${parameters({ page, size: 25 })}`, pageOf(myMaterialLocation))
export const acknowledgeMyMaterial = (id: string, input: MaterialReceiptInput) => command(`${materials(id)}/acknowledge`, 'POST', input, (value, path = 'receipt') => {
  const r = record(value, path); return { receiptId: uuid(r.receiptId, path), revision: integer(r.revision, path), state: oneOf(r.state, ['PART_RECEIVED', 'RECEIVED'], path) }
})
export const returnMyMaterial = (id: string, input: MaterialReturnInput) => command(`${materials(id)}/return`, 'POST', input, (value, path = 'return') => {
  const r = record(value, path); return { id: uuid(r.id, path), workOrderId: uuid(r.workOrderId, path), purpose: oneOf(r.purpose, ['RETURN'], path) }
})
export const acknowledgeMaterialResidual = (id: string, input: { documentId: string; expectedRevision: number; evidenceReference: string }) =>
  command(`${materials(id)}/residuals/acknowledge`, 'POST', input, (value, path = 'acknowledgement') => {
    const r = record(value, path); return { documentId: uuid(r.documentId, path), state: oneOf(r.state, ['RECEIVED', 'RECEIVED_IN_INSPECTION'], path) }
  })
