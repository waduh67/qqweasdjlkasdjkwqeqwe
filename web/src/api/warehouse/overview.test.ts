import { afterEach, expect, it, vi } from 'vitest'
import { listStockShortages, stockShortage } from './overview'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
afterEach(() => vi.unstubAllGlobals())
it('requires exact positive difference and preserves large quantities without numeric conversion', () => {
  const row = { id: id.sku, skuId: id.sku, skuCode: 'C', name: 'Cable', baseUnit: 'MM', availableBase: '9007199254740993', minimumBase: '9007199254740994', shortageBase: '1' }
  expect(stockShortage(row).availableBase).toBe('9007199254740993')
  expect(() => stockShortage({ ...row, shortageBase: '2' })).toThrow()
  expect(() => stockShortage({ ...row, shortageBase: '0' })).toThrow()
  expect(() => stockShortage({ ...row, skuId: id.source })).toThrow()
})
it('requests bounded server pages with explicit location and SKU filters', async () => {
  const fetch = vi.fn(async (_path: string) => new Response(JSON.stringify({ items: [], page: 2, size: 5, totalElements: 0 }), { headers: { 'Content-Type': 'application/json' } })); vi.stubGlobal('fetch', fetch)
  await listStockShortages({ page: 2, size: 5, skuId: id.sku, locationId: id.inspection })
  const url = new URL(fetch.mock.calls[0][0], 'http://test')
  expect(url.pathname).toBe('/api/v1/warehouse/stock/shortages'); expect(url.searchParams.get('locationId')).toBe(id.inspection); expect(url.searchParams.get('size')).toBe('5')
})
