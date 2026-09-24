import { integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { parameters, query } from './transport'

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
