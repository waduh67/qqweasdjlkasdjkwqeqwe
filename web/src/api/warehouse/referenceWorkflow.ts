import { api } from '../client'
import { array, digest, integer, oneOf, record, text, timestamp, uuid, WarehouseDataError } from './codec'
import { referenceCommand } from './reference'
import { query } from './transport'

const root = '/api/v2/warehouse/workflow'
export function workflowSnapshot(value: unknown) {
  const row = record(value)
  return { tenantId: uuid(row.tenantId), epoch: integer(row.epoch),
    state: oneOf(row.state, ['LEGACY', 'VALIDATING', 'ENFORCED']),
    workflow: oneOf(row.workflow, ['LEGACY', 'DRAINING', 'REFERENCE']) }
}

function rowCount(value: unknown, path: string): number {
  if (!Array.isArray(value)) throw new WarehouseDataError(path)
  for (const item of value) uuid(record(item, path).id, path)
  return value.length
}

export function warehouseActivationReview(value: unknown) {
  const row = record(value), snapshot = record(row.snapshot)
  const expectedEpoch = integer(row.expectedEpoch), issues = array(row.issues, text, 'issues', 100)
  const snapshotIssues = array(snapshot.issues, text, 'snapshot.issues', 100)
  if (integer(snapshot.epoch) !== expectedEpoch || JSON.stringify(issues) !== JSON.stringify(snapshotIssues)) throw new WarehouseDataError('snapshot')
  const documents = snapshot.documents
  if (!Array.isArray(documents)) throw new WarehouseDataError('snapshot.documents')
  const states = new Map<string, number>()
  for (const value of documents) {
    const document = record(value), state = text(document.state)
    uuid(document.id); integer(document.revision); text(document.kind)
    states.set(state, (states.get(state) ?? 0) + 1)
  }
  return { tenantId: uuid(snapshot.tenantId), expectedEpoch, reviewHash: digest(row.reviewHash), issues,
    balances: rowCount(snapshot.balances, 'snapshot.balances'), segments: rowCount(snapshot.segments, 'snapshot.segments'),
    claims: rowCount(snapshot.claims, 'snapshot.claims'), documents: documents.length,
    documentStates: [...states].map(([state, count]) => ({ state, count })) }
}
export type WarehouseActivationReview = ReturnType<typeof warehouseActivationReview>
export const reviewWarehouseActivation = () => query(root + '/review', warehouseActivationReview)

// Drain has no replay receipt. Resolve an uncertain response with readWorkflow before sending again.
export const startWarehouseDrain = (expectedEpoch: number) =>
  api.request<unknown>(root + '/drain', { method: 'POST', body: JSON.stringify({ expectedEpoch: integer(expectedEpoch) }) }).then(workflowSnapshot)

export function warehouseActivation(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), workflow: oneOf(row.workflow, ['REFERENCE']), epoch: integer(row.epoch),
    reviewHash: digest(row.reviewHash), activatedBy: uuid(row.activatedBy), activatedAt: timestamp(row.activatedAt) }
}
export const activateReferenceWarehouse = (review: WarehouseActivationReview, reason: string) =>
  referenceCommand(root + '/activate', 'POST', { expectedEpoch: review.expectedEpoch, reviewHash: review.reviewHash, reason }, warehouseActivation)
