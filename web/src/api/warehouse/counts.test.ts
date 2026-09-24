import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { countDetailsFixture, countFactFixture, countFixture, countIds as id, countPositionFixture } from '@/test/warehouseCountFixture'
import { WarehouseDataError } from './codec'
import { countDetails, countFact, countPosition, countReviewDetails, countHistory, countWorkbench, observeCount } from './counts'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('binds named references to actual count entries and leaves quantities out of blind contracts', () => {
  const details = countDetailsFixture(), position = countPositionFixture()
  expect(countDetails(details)).toEqual(details); expect(countPosition(position)).toEqual(position)
  expect(JSON.stringify(details)).not.toMatch(/quantity|capacity|reserved|cost/i)
  expect(JSON.stringify(position)).not.toMatch(/quantity|capacity|reserved|cost/i)
  expect(() => countDetails({ ...details, references: { ...details.references, location: { ...details.references.location, id: id.other } } })).toThrow(WarehouseDataError)
  expect(() => countDetails({ ...details, references: { ...details.references, counters: [] } })).toThrow(WarehouseDataError)
  expect(() => countDetails({ ...details, references: { ...details.references, lines: [{ ...details.references.lines[0], item: { ...position.item, skuId: id.other } }] } })).toThrow(WarehouseDataError)
  expect(() => countFact({ ...countFactFixture(), quantityBase: 82500 })).toThrow(WarehouseDataError)
})
it('requires submitted state and complete matched comparison with exact quantities', () => {
  const details = countDetailsFixture(countFixture('SUBMITTED', 3))
  const review = { count: details.count, observations: [{ balanceId: id.balance, counterId: id.counter, bookQuantityBase: '100000', quantityBase: '82500', baseUnit: 'MM', observedDimensionRevision: 2 }] }
  expect(countReviewDetails({ review, references: details.references }).review.observations[0].quantityBase).toBe('82500')
  expect(() => countReviewDetails({ review: { ...review, count: countFixture('COUNTING', 2) }, references: details.references })).toThrow(WarehouseDataError)
  expect(() => countReviewDetails({ review: { ...review, observations: [] }, references: details.references })).toThrow(WarehouseDataError)
  expect(() => countReviewDetails({ review: { ...review, observations: [{ ...review.observations[0], counterId: id.other }] }, references: details.references })).toThrow(WarehouseDataError)
})
it('pages safe reads and preserves original observation bytes and key after an ambiguous response', async () => {
  const page = (items: unknown[]) => new Response(JSON.stringify({ items, page: 0, size: 25, totalElements: items.length }))
  const fetch = vi.fn().mockResolvedValueOnce(page([countDetailsFixture()])).mockResolvedValueOnce(page([]))
    .mockRejectedValueOnce(new TypeError('connection lost')).mockResolvedValueOnce(new Response(JSON.stringify(countFixture('COUNTING', 2))))
  vi.stubGlobal('fetch', fetch)
  await countWorkbench({ state: 'DRAFT', locationId: id.location }); await countHistory(id.count)
  expect(fetch.mock.calls[0][0]).toContain('/counts/workbench?state=DRAFT&locationId=')
  expect(fetch.mock.calls[1][0]).toContain('/history/page?page=0&size=25')
  const input = { expectedRevision: 1, balanceId: id.balance, quantityBase: '82500', reason: 'Hasil fisik', documentReference: 'LEMBAR' }
  const command = observeCount(id.count, input); input.quantityBase = '100000'
  await expect(command.execute()).rejects.toThrow('connection lost'); await expect(command.execute()).resolves.toMatchObject({ revision: 2 })
  for (const [path, init] of fetch.mock.calls.slice(2)) {
    expect(path).toBe(`/api/v1/warehouse/counts/${id.count}/observe`)
    expect(JSON.parse(init.body)).toMatchObject({ expectedRevision: 1, quantityBase: '82500' })
    expect((init.headers as Headers).get('Idempotency-Key')).toBe(command.key)
  }
})
