import { timestamp } from './approvals'
import { decimal, integer, nullable, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { materialCustody } from './materialExecution'
import { baseUnit, materialSku } from './materialModels'
import { myMaterialLocation, type MaterialReturnInput } from './myMaterials'
import { policyChoice } from './policy'
import { command, parameters, query } from './transport'

function handoverSource(value: unknown, path = 'source') {
  const r = record(value, path), source = materialCustody(r.source, path), id = uuid(r.id, path)
  if (id !== source.id) throw new WarehouseDataError(path)
  return { id, sender: policyChoice(r.sender, path), source }
}
function handoverTarget(value: unknown, path = 'target') {
  const r = record(value, path); return { ...myMaterialLocation(r, path), receiver: policyChoice(r.receiver, path) }
}
export function handoverGrant(value: unknown, path = 'grant') {
  const r = record(value, path), input = record(r.request, path)
  const request: MaterialReturnInput = { workOrderRevision: integer(input.workOrderRevision, path), receiptId: uuid(input.receiptId, path), issueLineId: uuid(input.issueLineId, path),
    stockIdentityId: uuid(input.stockIdentityId, path), quantityBase: decimal(input.quantityBase, path), baseUnit: baseUnit(input.baseUnit, path), targetLocationId: uuid(input.targetLocationId, path),
    reason: text(input.reason, path), evidenceReference: text(input.evidenceReference, path), usageId: nullable(input.usageId, uuid, path) ?? undefined,
    expectedSenderId: nullable(input.expectedSenderId ?? null, uuid, path) ?? undefined, expectedReceiverId: nullable(input.expectedReceiverId ?? null, uuid, path) ?? undefined }
  const result = { id: uuid(r.id, path), workOrderId: uuid(r.workOrderId, path), request, sender: policyChoice(r.sender, path), receiver: policyChoice(r.receiver, path), dispatcher: policyChoice(r.dispatcher, path),
    location: myMaterialLocation(r.location, path), sku: materialSku(r.sku, path), serial: nullable(r.serial, text, path), lotCode: nullable(r.lotCode, text, path),
    currentQuantityBase: decimal(r.currentQuantityBase, path), stockRevision: integer(r.stockRevision, path), recordedAt: timestamp(r.recordedAt, path) }
  if (request.quantityBase === '0' || input.authorizationId != null || result.location.id !== request.targetLocationId || request.baseUnit !== result.sku.baseUnit ||
    new Set([result.sender.id, result.receiver.id, result.dispatcher.id]).size !== 3 ||
    (request.expectedSenderId !== undefined && request.expectedSenderId !== result.sender.id) || (request.expectedReceiverId !== undefined && request.expectedReceiverId !== result.receiver.id) ||
    (request.expectedSenderId === undefined) !== (request.expectedReceiverId === undefined) || (result.sku.tracking === 'SERIAL' && (!result.serial || request.quantityBase !== '1'))) throw new WarehouseDataError(path)
  return result
}
export type MaterialHandoverSource = ReturnType<typeof handoverSource>
export type MaterialHandoverTarget = ReturnType<typeof handoverTarget>
export type MaterialHandoverGrant = ReturnType<typeof handoverGrant>
const root = (id: string) => `/api/work-orders/${uuid(id)}/materials`
export const getMaterialHandoverSources = (id: string, page: number) => query(`${root(id)}/handover-workbench/sources${parameters({ page, size: 25 })}`, pageOf(handoverSource))
export const getMaterialHandoverTargets = (id: string, page: number) => query(`${root(id)}/handover-workbench/targets${parameters({ page, size: 25 })}`, pageOf(handoverTarget))
export const getMaterialHandoverGrants = (id: string, page: number) => query(`${root(id)}/handover-workbench/pending${parameters({ page, size: 10 })}`, pageOf(handoverGrant))
export const getMaterialHandoverGrant = (id: string, grant: string) => query(`${root(id)}/handover-workbench/pending/${uuid(grant)}`, value => {
  const result = handoverGrant(value); if (result.id !== grant || result.workOrderId !== id) throw new WarehouseDataError('grant'); return result
})
export const authorizeMaterialHandover = (id: string, input: MaterialReturnInput) => command(`${root(id)}/handover/authorize`, 'POST', input, (value, path = 'authorization') => {
  const r = record(value, path), workOrderId = uuid(r.workOrderId, path); if (workOrderId !== id) throw new WarehouseDataError(path)
  return { authorizationId: uuid(r.authorizationId, path), workOrderId }
})
export const dispatchMaterialHandover = (grant: MaterialHandoverGrant) => command(`${root(grant.workOrderId)}/handover`, 'POST', { ...grant.request, authorizationId: grant.id }, (value, path = 'dispatch') => {
  const r = record(value, path), workOrderId = uuid(r.workOrderId, path); if (workOrderId !== grant.workOrderId || r.purpose !== 'HANDOVER') throw new WarehouseDataError(path)
  return { id: uuid(r.id, path), workOrderId }
})
