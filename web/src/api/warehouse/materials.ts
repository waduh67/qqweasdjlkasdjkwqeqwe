import { boolean, integer, oneOf, pageOf, record, uuid } from './codec'
import { baseUnit, DEMAND_STATES, materialHistory, materialPlan, materialSummary, type MaterialSubstitution } from './materialModels'
import { issueSlip } from './issueModels'
import { command, parameters, query } from './transport'

export interface PlanLineInput { skuId: string; quantityBase: string; baseUnit: ReturnType<typeof baseUnit>; continuousCut: boolean; substitution?: MaterialSubstitution }
export interface MaterialPlanInput { expectedRevision: number; workOrderRevision: number; materialMode: 'MATERIAL_REQUIRED' | 'NONE'; reason?: string | null; lines?: PlanLineInput[] | null }
export interface MaterialPlanCommand { expectedRevision: number; workOrderRevision: number; reason?: string | null }
export interface ReserveSelection { demandLineId: string; partialQuantityBase?: string; stockIdentityId?: string }
export interface ReservationCommand { expectedRevision: number; workOrderRevision: number; planRevision: number; lines?: ReserveSelection[]; reason?: string; allocations?: { reservationId: string; expectedRevision: number; quantityBase: string }[] }
export interface PickInput { expectedRevision: number; workOrderRevision: number; demandRevision: number; lines: { reservationId: string; expectedRevision: number; stockIdentityId: string; stockRevision: number; quantityBase: string; baseUnit: ReturnType<typeof baseUnit>; scan?: string }[] }
export interface IssueTransition { issueId: string; expectedRevision: number; workOrderRevision: number; planRevision: number; demandRevision: number; partial: boolean; reason: string }
const root = (id: string) => `/api/work-orders/${uuid(id)}/materials`
export const getMaterials = (workOrderId: string) => query(root(workOrderId), materialSummary)
export const getMaterialHistory = (workOrderId: string, page = 0) => query(`${root(workOrderId)}/history${parameters({ page })}`, pageOf(materialHistory))
export const saveMaterialPlan = (workOrderId: string, input: MaterialPlanInput) => command(`${root(workOrderId)}/plan`, 'PUT', input, materialPlan)
export const submitMaterialRequest = (workOrderId: string, input: MaterialPlanCommand) => command(`${root(workOrderId)}/submit-request`, 'POST', input, materialSummary)
export const materialRequestAction = (documentId: string, action: 'reserve' | 'release', input: ReservationCommand) => command(`/api/v1/warehouse/material-requests/${uuid(documentId)}/${action}`, 'POST', input, (value, path) => {
  const row = record(value, path)
  return { documentId: uuid(row.documentId, path), revision: integer(row.revision, path), operationId: uuid(row.operationId, path), state: oneOf(row.state, DEMAND_STATES, path), shortage: boolean(row.shortage, path) }
})
export const pickMaterials = (workOrderId: string, input: PickInput) => command(`${root(workOrderId)}/pick`, 'POST', input, issueSlip)
export const transitionIssue = (workOrderId: string, action: 'unpick' | 'dispatch', input: IssueTransition) => command(`${root(workOrderId)}/${action}`, 'POST', input, issueSlip)
export const getIssueSlip = (workOrderId: string, issueId: string) => query(`${root(workOrderId)}/issues/${uuid(issueId)}/slip`, issueSlip)
