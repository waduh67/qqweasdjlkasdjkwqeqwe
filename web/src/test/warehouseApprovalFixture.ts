import type { ApprovalDetails, ApprovalDocument } from '@/api/warehouse/approvalReads'

const id = (n: number) => `70000000-0000-4000-8000-${n.toString().padStart(12, '0')}`
export const approvalIds = { request: id(1), document: id(2), requester: id(3), checker: id(4), line: id(5), sku: id(6), source: id(7), target: id(8), policy: id(9), effect: id(10), movement: id(11), decision: id(12), other: id(13) }
export function approvalDocumentFixture(revision = 5): ApprovalDocument {
  const ids = approvalIds
  return { id: ids.document, revision, kind: 'RECEIPT', code: 'RCPT-001', state: 'DRAFT', reason: 'Penerimaan pemasok', createdAt: '2026-09-25T01:00:00Z', requester: { id: ids.requester, name: 'Petugas penerima' },
    locations: [{ id: ids.source, code: 'RECEIPT_SOURCE', name: 'Batas penerimaan' }, { id: ids.target, code: 'INSPECT', name: 'Karantina pemeriksaan' }],
    lines: [{ id: ids.line, skuId: ids.sku, code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', quantityBase: '40000', serial: null, lotCode: 'REEL-A', locationId: ids.source, destinationLocationId: ids.target, condition: 'SERVICEABLE', legalOwner: 'ISP' }],
    comparisons: [], receiptId: ids.document, countId: null, transferId: null, returnId: null }
}
export function approvalDetailsFixture(): ApprovalDetails {
  const ids = approvalIds
  return { approval: { requestId: ids.request, sourceDocumentId: ids.document, sourceRevision: 5, revision: 2, status: 'PENDING', code: 'PENDING', expiresAt: '2026-09-26T01:00:00Z', effectOperationId: null },
    document: approvalDocumentFixture(), currentSourceRevision: 5, currentSourceState: 'DRAFT', requestedAt: '2026-09-25T01:01:00Z',
    policy: { id: ids.policy, revision: 3, tiers: [{ number: 1, approvers: [{ id: ids.other, name: 'Pemeriksa awal' }] }, { number: 2, approvers: [{ id: id(14), name: 'Pemeriksa kedua' }] }, { number: 3, approvers: [{ id: ids.checker, name: 'Pemeriksa independen' }] }] },
    actions: { canDecide: true, decisionBlock: null, canRework: false, reworkSourceRevision: 5, currentTier: 3 }, effect: null, cost: null }
}
export function approvalPostedFixture(): ApprovalDetails {
  const details = approvalDetailsFixture(), ids = approvalIds
  return { ...details, approval: { ...details.approval, status: 'APPROVED', code: 'APPROVED', revision: 3, effectOperationId: ids.effect }, currentSourceRevision: 6, currentSourceState: 'RECEIVED_IN_INSPECTION',
    actions: { ...details.actions, canDecide: false, decisionBlock: 'REQUEST_TERMINAL', reworkSourceRevision: 6, currentTier: null },
    effect: { operationId: ids.effect, businessAction: 'RECEIVE', recordedAt: '2026-09-25T01:02:00Z', movementIds: [ids.movement] } }
}
