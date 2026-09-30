import { integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { command, parameters, query } from './transport'

export const APPROVAL_STATES = ['PENDING', 'APPROVED', 'REJECTED', 'REWORK_REQUIRED', 'EXPIRED', 'STALE'] as const
export function timestamp(value: unknown, path = 'timestamp') {
  const result = text(value, path)
  if (!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)$/.test(result) || !Number.isFinite(Date.parse(result))) throw new WarehouseDataError(path)
  return result
}
export function approval(value: unknown, path = 'approval') {
  const row = record(value, path)
  return {
    requestId: uuid(row.requestId, `${path}.requestId`), sourceDocumentId: uuid(row.sourceDocumentId, `${path}.sourceDocumentId`),
    sourceRevision: integer(row.sourceRevision, `${path}.sourceRevision`), revision: integer(row.revision, `${path}.revision`),
    status: oneOf(row.status, APPROVAL_STATES, `${path}.status`), expiresAt: timestamp(row.expiresAt, `${path}.expiresAt`),
    code: text(row.code, `${path}.code`), effectOperationId: nullable(row.effectOperationId, uuid, `${path}.effectOperationId`),
  }
}
export type WarehouseApproval = ReturnType<typeof approval>
export const listApprovals = (filter: { page?: number; size?: number; status?: WarehouseApproval['status'] } = {}) =>
  query(`/api/v1/warehouse/approvals${parameters({ ...filter })}`, pageOf(approval))

export interface ApprovalSource { sourceDocumentId: string; sourceRevision: number }
export interface ApprovalDecision { requestId: string; expectedRevision: number; decision: 'APPROVE' | 'REJECT'; reason?: string; evidenceReference?: string }
export const getApproval = (id: string) => query(`/api/v1/warehouse/approvals/${uuid(id)}`, approval)
export const requestApproval = (input: ApprovalSource) => command('/api/v1/warehouse/approvals/request', 'POST', input, approval)
export const decideApproval = (input: ApprovalDecision) => command('/api/v1/warehouse/approvals/decide', 'POST', input, approval)
export const reworkApproval = (requestId: string, expectedSourceRevision: number) => command('/api/v1/warehouse/approvals/rework', 'POST',
  { requestId, expectedRevision: expectedSourceRevision }, (value, path = 'rework') => {
    const row = record(value, path)
    return { requestId: uuid(row.requestId, path), sourceDocumentId: uuid(row.sourceDocumentId, path), sourceRevision: integer(row.sourceRevision, path), status: oneOf(row.status, ['DRAFT'], path) }
  })
