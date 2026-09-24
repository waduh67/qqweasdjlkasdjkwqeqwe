import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { getReportPrint, exportReport, listReport, reportParams } from './reports'
import { reportCard, reportCost, reportCosts } from './reportModels'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { reportCostFixture, reportMovementFixture, reportPage, reportPrintFixture } from '@/test/warehouseReportFixture'
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('preserves exact signed cost and opening balances, and rejects fabricated known or zero unknown costs', () => {
  const cost = reportCostFixture()
  expect(reportCost(cost).sourceTotalMinor).toBe('9007199254740993')
  expect(reportCost(cost).quantityBase).toBe('-82500')
  const unknown = { ...cost, costState: 'UNKNOWN', sourceTotalMinor: null, sourceBasisQuantityBase: null, currency: null, lineTotalMinor: null }
  expect(reportCost(unknown).lineTotalMinor).toBeNull()
  expect(() => reportCost({ ...unknown, lineTotalMinor: '0' })).toThrow()
  expect(() => reportCost({ ...cost, sourceBasisQuantityBase: '0' })).toThrow()
  expect(reportCard({ ...reportMovementFixture(), openingQuantityBase: '-1', closingQuantityBase: '59999' }).openingQuantityBase).toBe('-1')
  expect(reportCosts({ ...reportPage([unknown]), scope: 'VISIBLE_LOCATIONS', costBasis: 'OPERATIONAL_USE', currencyTotals: [], unknownQuantities: [{ baseUnit: 'MM', quantityBase: '82500' }] }).currencyTotals).toEqual([])
})
it('exports all filtered rows with authenticated blob transport, dropping only pagination', async () => {
  const fetch = vi.fn(async (_path: string, _init: RequestInit) => new Response('"name"\r\n"Kabel"\r\n', { headers: { 'Content-Type': 'text/csv;charset=UTF-8' } }))
  vi.stubGlobal('fetch', fetch); tokenStore.setAccessToken('report-session')
  await exportReport('movements', { page: 3, size: 25, locationId: id.inspection, serial: 'ABC', from: '2026-01-01T00:00:00Z', until: '2026-02-01T00:00:00Z' })
  const url = new URL(fetch.mock.calls[0][0], 'http://test')
  expect(url.searchParams.has('page')).toBe(false); expect(url.searchParams.has('size')).toBe(false)
  expect(url.searchParams.get('locationId')).toBe(id.inspection); expect(url.searchParams.get('serial')).toBe('ABC')
  expect((fetch.mock.calls[0][1].headers as Headers).get('Authorization')).toBe('Bearer report-session')
})
it('binds prints to the requested revision and reads report-only DTOs without master lookups', async () => {
  const fetch = vi.fn(async (path: string) => response(path.endsWith('/print') ? reportPrintFixture() : reportPage([reportMovementFixture()])))
  vi.stubGlobal('fetch', fetch)
  expect((await listReport('movements')).page.items).toHaveLength(1)
  expect((await getReportPrint(id.document, 7)).documentCode).toBe('RC-7')
  await expect(getReportPrint(id.document, 6)).rejects.toThrow()
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/v1/warehouse/reports/'))).toBe(true)
})
it('rejects duplicate, blank, malformed and incomplete deep links without broadening their intended scope', () => {
  for (const value of ['locationId=x', 'locationId=', 'kind=movements&kind=stock', 'kind=stock-card&sort=name', 'workOrderId='+id.source, 'page=-1', 'revision=7', 'from=2026-01-01T00:00:00Z', 'page=9007199254740993', 'unknown=1']) expect(() => reportParams(new URLSearchParams(value))).toThrow()
  expect(reportParams(new URLSearchParams(`kind=work-order-costs&workOrderId=${id.source}`)).filter.workOrderId).toBe(id.source)
})
