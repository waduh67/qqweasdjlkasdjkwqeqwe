import { api } from './client'
import type { PageResponse } from './types'

export type AttendanceDecision = 'ACCEPTED' | 'REVIEW_REQUIRED' | 'REJECTED'
export type VisitState = 'PLANNED' | 'CHECKED_IN' | 'ON_SITE' | 'CHECKED_OUT' | 'SUBMITTED' | 'CANCELLED' | 'CONFLICT'

export interface VisitView {
  readonly id: string
  readonly state: VisitState
  readonly revision: number
  readonly attendanceDecision: AttendanceDecision | null
  readonly serverReceivedAt: string | null
}
export interface VisitListView extends VisitView { readonly workOrderId: string; readonly orderId: string; readonly scheduledAt: string | null; readonly session: { readonly startedAt: string | null; readonly endedAt: string | null; readonly submittedAt: string | null } }

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
export const listVisits = (status?: VisitState) => api.get<PageResponse<VisitListView>>(`/api/v1/fieldservice/visits?scope=SELF&size=50${status ? `&status=${status}` : ''}`)
export const getWorkSession = (id: string) => api.get<WorkSessionView>(`/api/v1/fieldservice/visits/${id}/work-session`)
export const checkInVisit = (id: string, revision: number, decision: AttendanceDecision, reason: string | null) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/check-in`, { ...operation('visit.check-in', revision), decision, reason })
export const markVisitOnSite = (id: string, revision: number) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/on-site`, operation('visit.on-site', revision))
export const checkOutVisit = (id: string, revision: number) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/check-out`, operation('visit.check-out', revision))
export const submitVisit = (id: string, revision: number) =>
  api.post<VisitView>(`/api/v1/fieldservice/visits/${id}/submit`, operation('visit.submit', revision))
