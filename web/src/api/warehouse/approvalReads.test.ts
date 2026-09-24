import { afterEach, expect, it, vi } from 'vitest'
import { approvalDetailsFixture, approvalDocumentFixture, approvalIds as id, approvalPostedFixture } from '@/test/warehouseApprovalFixture'
import { tokenStore } from '@/api/client'
import { approvalDetails, approvalDocument, approvalSource, evaluateApprovalSource } from './approvalReads'
import { decideApproval } from './approvals'
import { WarehouseDataError } from './codec'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('binds source revisions and only accepts actual effects for the matching document action', () => {
  const details = approvalDetailsFixture(), posted = approvalPostedFixture()
  expect(approvalDetails(details)).toEqual(details); expect(approvalDetails(posted)).toEqual(posted)
  expect(() => approvalDetails({ ...details, document: { ...details.document, revision: 4 } })).toThrow(WarehouseDataError)
  expect(() => approvalDetails({ ...details, actions: { ...details.actions, canDecide: false } })).toThrow(WarehouseDataError)
  expect(() => approvalDetails({ ...posted, effect: { ...posted.effect, businessAction: 'LOSS' } })).toThrow(WarehouseDataError)
  expect(() => approvalDetails({ ...posted, effect: null })).toThrow(WarehouseDataError)
  expect(() => approvalDetails({ ...posted, effect: { ...posted.effect, movementIds: [] } })).toThrow(WarehouseDataError)
  expect(() => approvalSource({ document: details.document, canRequest: true, requestBlock: 'REQUESTER_REQUIRED' })).toThrow(WarehouseDataError)
})
it('rejects count capacity in blind source lines and comparison before submission', () => {
  const prior = approvalDocumentFixture(), count = { ...prior, kind: 'COUNT', receiptId: null, countId: id.document, transferId: null, lines: prior.lines.map(line => ({ ...line, quantityBase: null })) }
  expect(approvalDocument(count).lines[0].quantityBase).toBeNull()
  expect(() => approvalDocument({ ...count, lines: prior.lines })).toThrow(WarehouseDataError)
  const comparison = { balanceId: id.other, skuId: id.sku, counter: { id: id.checker, name: 'Penghitung' }, baseUnit: 'MM', bookQuantityBase: '100000', quantityBase: '82500', documentReference: 'LEMBAR' }
  expect(() => approvalDocument({ ...count, comparisons: [comparison] })).toThrow(WarehouseDataError)
  expect(() => approvalDocument({ ...count, state: 'SUBMITTED' })).toThrow(WarehouseDataError)
  expect(approvalDocument({ ...count, state: 'SUBMITTED', comparisons: [comparison] }).comparisons[0].quantityBase).toBe('82500')
})
it('retains exact captured decision and refuses an evaluation for a different source revision', async () => {
  const posted = approvalPostedFixture()
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('lost')).mockResolvedValueOnce(new Response(JSON.stringify(posted.approval)))
    .mockResolvedValueOnce(new Response(JSON.stringify({ sourceDocumentId: id.document, sourceRevision: 0, code: 'APPROVAL_REQUIRED', requiredAction: 'REQUEST_APPROVAL' })))
  vi.stubGlobal('fetch', fetch)
  const input = { requestId: id.request, expectedRevision: 2, decision: 'APPROVE' as const, reason: 'Bukti diperiksa' }
  const operation = decideApproval(input); input.expectedRevision = 99
  await expect(operation.execute()).rejects.toThrow('lost'); await expect(operation.execute()).resolves.toMatchObject({ effectOperationId: id.effect })
  for (const [, init] of fetch.mock.calls.slice(0, 2)) {
    expect(JSON.parse(init.body)).toEqual({ requestId: id.request, expectedRevision: 2, decision: 'APPROVE', reason: 'Bukti diperiksa' })
    expect((init.headers as Headers).get('Idempotency-Key')).toBe(operation.key)
  }
  await expect(evaluateApprovalSource(id.document, 5)).rejects.toThrow(WarehouseDataError)
})
