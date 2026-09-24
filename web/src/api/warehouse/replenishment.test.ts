import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { acceptReplenishment, bindReplenishmentReceipt, getReplenishment, replenishmentDetails, replenishmentEvaluation, replenishmentRequest } from './replenishment'
import { replenishmentFixture, replenishmentRequestFixture } from '@/test/warehouseReplenishmentFixture'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('checks current named identities and immutable captured rule binding without replacing old snapshots', () => {
  const data = replenishmentFixture()
  expect(replenishmentDetails({ ...data, rule: { ...data.rule, revision: 5 }, position: { ...data.position, availableBase: '60000' }, suggestedQuantityBase: '0' }).request?.availableBase).toBe('0')
  expect(() => replenishmentDetails({ ...data, sku: { ...data.sku, id: id.supplier } })).toThrow()
  expect(() => replenishmentRequest({ ...data.request, ruleRevision: 5 })).toThrow()
  expect(() => replenishmentRequest({ ...data.request, acceptedAt: '2026-09-24T17:00:00Z' })).toThrow()
})
it('preserves the acceptance key quantity and both revisions after response loss', async () => {
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(response(replenishmentRequestFixture())); vi.stubGlobal('fetch', fetch)
  const data = replenishmentFixture(), operation = acceptReplenishment(data.request!, data.rule)
  data.request!.revision = 99; data.rule.revision = 99
  await expect(operation.execute()).rejects.toThrow(); await operation.execute()
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ expectedRevision: 3, expectedRuleRevision: 4, quantityBase: '100000' })
  expect((fetch.mock.calls[0][1].headers as Headers).get('Idempotency-Key')).toBe((fetch.mock.calls[1][1].headers as Headers).get('Idempotency-Key'))
})
it('decodes both canonical recompute outcomes and refuses a mismatched detail id', async () => {
  expect(replenishmentEvaluation(replenishmentRequestFixture()).request?.id).toBe(id.document)
  expect(replenishmentEvaluation({ ruleId: id.source, position: { availableBase: '60000', reservedBase: '0', confirmedInboundBase: '0' }, request: null }).request).toBeNull()
  vi.stubGlobal('fetch', vi.fn(async () => response(replenishmentFixture())))
  await expect(getReplenishment('requests', id.supplier)).rejects.toThrow()
})
it('binds an actual receipt revision and line without inventing quantity or target location', () => {
  const operation = bindReplenishmentReceipt(replenishmentRequestFixture(), id.evidence, 8, id.line)
  expect(JSON.parse(operation.body)).toEqual({ expectedRevision: 3, documentId: id.evidence, documentRevision: 8, lineId: id.line })
})
