import { api } from './client'
import { array, boolean, integer, nullable, oneOf, record, text, timestamp, uuid, WarehouseDataError } from './warehouse/codec'
import { parameters, query } from './warehouse/transport'

export type AttendanceDecision = 'ACCEPTED' | 'REVIEW_REQUIRED' | 'REJECTED'
export type VisitState = 'PLANNED' | 'CHECKED_IN' | 'ON_SITE' | 'CHECKED_OUT' | 'SUBMITTED' | 'CANCELLED' | 'CONFLICT'

export interface VisitView {
  readonly id: string
  readonly state: VisitState
  readonly revision: number
  readonly attendanceDecision: AttendanceDecision | null
  readonly serverReceivedAt: string | null
}
export type VisitHistory = 'ACTIVE' | 'HISTORY' | 'ALL'
export interface VisitListView {
  readonly id: string
  readonly workOrderId: string
  readonly orderId: string | null
  readonly state: VisitState
  readonly revision: number
  readonly scheduledAt: string | null
  readonly workOrderCode: string
  readonly title: string
  readonly customerName: string | null
  readonly areaName: string | null
  readonly purpose: 'FULFILLMENT' | 'WORK_ORDER'
  readonly actionable: boolean
  readonly session: { readonly startedAt: string | null; readonly endedAt: string | null; readonly submittedAt: string | null }
}
function visitListItem(value: unknown): VisitListView {
  const row = record(value), session = record(row.session)
  return { id: uuid(row.id), workOrderId: uuid(row.workOrderId), orderId: nullable(row.orderId, uuid, 'orderId'),
    state: oneOf(row.state, ['PLANNED', 'CHECKED_IN', 'ON_SITE', 'CHECKED_OUT', 'SUBMITTED', 'CANCELLED', 'CONFLICT']), revision: integer(row.revision),
    scheduledAt: nullable(row.scheduledAt, timestamp, 'scheduledAt'), workOrderCode: text(row.workOrderCode), title: text(row.title),
    customerName: nullable(row.customerName, text, 'customerName'), areaName: nullable(row.areaName, text, 'areaName'),
    purpose: oneOf(row.purpose, ['FULFILLMENT', 'WORK_ORDER']), actionable: boolean(row.actionable),
    session: { startedAt: nullable(session.startedAt, timestamp, 'startedAt'), endedAt: nullable(session.endedAt, timestamp, 'endedAt'), submittedAt: nullable(session.submittedAt, timestamp, 'submittedAt') } }
}

export interface WorkSessionView {
  readonly id: string
  readonly visitId: string
  readonly startedAt: string | null
  readonly endedAt: string | null
  readonly submittedAt: string | null
}

export interface VisitOperation {
  readonly namespace: string
  readonly operationKey: string
  readonly payloadHash: string
  readonly revision: number
}

const operation = (namespace: string, revision: number): VisitOperation => ({
  namespace,
  operationKey: crypto.randomUUID(),
  payloadHash: crypto.randomUUID().replaceAll('-', '').repeat(2),
  revision,
})

export const getVisit = (id: string) => api.get<VisitView>(`/api/v1/fieldservice/visits/${id}`)
export const listVisits = (history: VisitHistory = 'ACTIVE', page = 0) => query('/api/v1/fieldservice/visits' + parameters({ scope: 'SELF', history, page, size: 25 }), value => {
  const row = record(value), result = { content: array(row.content, visitListItem, 'visits', 25), page: integer(row.page), size: integer(row.size), totalElements: integer(row.totalElements) }
  if (result.page !== page || result.size !== 25 || result.content.length > result.totalElements) throw new WarehouseDataError('visit page')
  return result
})
export const getWorkSession = (id: string) => api.get<WorkSessionView>(`/api/v1/fieldservice/visits/${id}/work-session`)
export const checkInVisit = (id: string, revision: number, decision: AttendanceDecision, reason: string | null) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/check-in`, { ...operation('visit.check-in', revision), decision, reason })
export const markVisitOnSite = (id: string, revision: number) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/on-site`, operation('visit.on-site', revision))
export const checkOutVisit = (id: string, revision: number) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/check-out`, operation('visit.check-out', revision))
export const submitVisit = (id: string, revision: number) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/submit`, operation('visit.submit', revision))
