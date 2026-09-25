import { array, integer, nullable, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { timestamp } from './approvals'
import { materialUsage, obligation } from './materialExecution'
import { materialPlan, materialSku } from './materialModels'
import type { PlanLineInput } from './materials'
import { command, parameters, query } from './transport'
import { policyChoice } from './policy'

const root = (id: string) => `/api/work-orders/${uuid(id)}/materials`
export function materialReworkBasis(value: unknown, path = 'reworkBasis') {
  const r = record(value, path)
  return { expectedRevision: integer(r.expectedRevision, path), workOrderRevision: integer(r.workOrderRevision, path), previousPlanId: uuid(r.previousPlanId, path), previousUsageId: uuid(r.previousUsageId, path), expectedUsageRevision: integer(r.expectedUsageRevision, path), previousEvidenceRevision: text(r.previousEvidenceRevision, path), evidenceRevision: text(r.evidenceRevision, path) }
}
export type MaterialReworkInput = ReturnType<typeof materialReworkBasis> & { reason: string; deltas: PlanLineInput[] }
export const getMaterialReworkBasis = (id: string) => query(`${root(id)}/rework-context`, materialReworkBasis)
export const appendMaterialRework = (id: string, input: MaterialReworkInput) => command(`${root(id)}/rework`, 'POST', input, (value, path = 'rework') => {
  const r = record(value, path), result = { reworkId: uuid(r.reworkId, path), plan: materialPlan(r.plan, path), previousPlan: materialPlan(r.previousPlan, path) }
  if (result.plan.id !== result.reworkId || result.plan.workOrderId !== id || result.previousPlan.workOrderId !== id) throw new WarehouseDataError(path)
  return result
})
export function materialObligationView(value: unknown, path = 'obligationView') {
  const r = record(value, path)
  const result = { id: uuid(r.id, path), issueCode: text(r.issueCode, path), sku: materialSku(r.sku, path), serial: nullable(r.serial, text, path), lotCode: nullable(r.lotCode, text, path), obligation: obligation(r.obligation, path) }
  if (result.id !== result.obligation.issueLineId || result.sku.baseUnit !== result.obligation.baseUnit) throw new WarehouseDataError(path)
  return result
}
export const getMaterialObligationRows = (id: string, page: number) => query(`${root(id)}/workbench/obligations${parameters({ page, size: 10 })}`, pageOf(materialObligationView))
function deploymentView(value: unknown, path = 'deployment') {
  const r = record(value, path), result = { authorizationId: uuid(r.authorizationId, path), workOrderId: uuid(r.workOrderId, path),
    assignmentId: uuid(r.assignmentId, path), assetId: uuid(r.assetId, path), useRevision: integer(r.useRevision, path),
    sku: materialSku(r.sku, path), serial: text(r.serial, path), actor: nullable(r.actor, policyChoice, path), recordedAt: timestamp(r.recordedAt, path) }
  if (result.sku.tracking !== 'SERIAL' || result.sku.baseUnit !== 'EA' || result.useRevision === 0) throw new WarehouseDataError(path)
  return result
}
export const getMaterialApprovalReview = (id: string) => query(`${root(id)}/workbench/approval-review`, (value, path = 'approvalReview') => {
  const r = record(value, path)
  return nullable(r.review, (value, path = 'review') => {
    const r = record(value, path), result = { id: uuid(r.id, path), workOrderRevision: integer(r.workOrderRevision, path), usage: nullable(r.usage, materialUsage, path),
      deployments: array(r.deployments ?? [], deploymentView, path) }
    if ((result.usage && result.usage.workOrderId !== id) || result.deployments.some(row => row.workOrderId !== id)
      || (!result.usage && !result.deployments.length) || (result.usage?.materialMode === 'NONE' && result.deployments.length > 0)
      || new Set(result.deployments.map(row => row.authorizationId)).size !== result.deployments.length) throw new WarehouseDataError(path)
    return result
  }, path)
})
