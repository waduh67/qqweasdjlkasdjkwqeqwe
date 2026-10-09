import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { WarehouseDataError } from './codec'
import { activateReferenceWarehouse, startWarehouseDrain, warehouseActivationReview } from './referenceWorkflow'

const id = '00000000-0000-4000-8000-000000000001', hash = 'a'.repeat(64)
const review = { expectedEpoch: 2, reviewHash: hash, issues: [], snapshot: {
  tenantId: id, epoch: 2, issues: [], balances: [{ id }], segments: [{ id }], claims: [{ id }],
  documents: [{ id, revision: 1, kind: 'RECEIPT', state: 'PUTAWAY' }],
} }
const receipt = { id, workflow: 'REFERENCE', epoch: 3, reviewHash: hash, activatedBy: id, activatedAt: '2026-10-09T10:00:00Z' }
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
let fetchMock: ReturnType<typeof vi.fn>
beforeEach(() => { tokenStore.clear(); fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock) })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('summarizes more than 1000 historical rows while retaining the server hash and epoch', () => {
  const documents = Array.from({ length: 1001 }, (_, index) => ({ id, revision: index, kind: 'RECEIPT', state: index % 2 ? 'CLOSED' : 'PUTAWAY' }))
  const parsed = warehouseActivationReview({ ...review, snapshot: { ...review.snapshot, documents, balances: documents } })
  expect(parsed).toMatchObject({ expectedEpoch: 2, reviewHash: hash, balances: 1001, documents: 1001,
    documentStates: [{ state: 'PUTAWAY', count: 501 }, { state: 'CLOSED', count: 500 }] })
})
it.each([
  { ...review, snapshot: { ...review.snapshot, epoch: 3 } },
  { ...review, issues: ['OPEN_LEGACY_DOCUMENTS'] },
  { ...review, snapshot: { ...review.snapshot, balances: null } },
  { ...review, reviewHash: 'invalid' },
])('rejects incomplete or inconsistent reviews', value => {
  expect(() => warehouseActivationReview(value)).toThrow(WarehouseDataError)
})
it('sends drain once without treating it as a replayable command', async () => {
  fetchMock.mockRejectedValue(new TypeError('Lost response'))
  await expect(startWarehouseDrain(2)).rejects.toThrow('Lost response')
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [path, options] = fetchMock.mock.calls[0] ?? []
  expect(path).toBe('/api/v2/warehouse/workflow/drain')
  expect(options.body).toBe(JSON.stringify({ expectedEpoch: 2 }))
  expect(options.headers.has('Idempotency-Key')).toBe(false)
})
it('captures exact activation bytes, shares pending sends, and rejects retry after session change', async () => {
  const command = activateReferenceWarehouse(warehouseActivationReview(review), 'Tim sudah siap')
  fetchMock.mockRejectedValueOnce(new TypeError('Lost response')).mockResolvedValueOnce(response(receipt))
  await expect(command.execute()).rejects.toThrow('Lost response')
  const pending = command.execute()
  expect(command.execute()).toBe(pending)
  await expect(pending).resolves.toEqual(receipt)
  expect(fetchMock).toHaveBeenCalledTimes(2)
  for (const [path, options] of fetchMock.mock.calls) {
    expect(path).toBe('/api/v2/warehouse/workflow/activate')
    expect(options.body).toBe(JSON.stringify({ expectedEpoch: 2, reviewHash: hash, reason: 'Tim sudah siap' }))
    expect(options.headers.get('Idempotency-Key')).toBe(command.key)
  }
  tokenStore.clear()
  expect(() => command.execute()).toThrow()
  expect(fetchMock).toHaveBeenCalledTimes(2)
})
