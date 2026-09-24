import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { transferDetailsFixture, transferFixture, transferIds as id } from '@/test/warehouseTransferFixture'
import { WarehouseDataError } from './codec'
import { listTransfers, receiveTransfer, transferDetails, transferView } from './transfers'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('distinguishes untouched drafts, partial transit and independently resolved remainder', () => {
  const draft = transferFixture()
  expect(transferView(draft)).toEqual(draft)
  const partial = { ...draft, state: 'PART_RECEIVED', revision: 2, lines: [{ ...draft.lines[0], receivedBase: '60000', inTransitBase: '40000', remainingIdentityId: id.demandLine }] }
  expect(transferView(partial).lines[0].inTransitBase).toBe('40000')
  const resolved = { ...partial, state: 'DISCREPANCY', lines: [{ ...partial.lines[0], inTransitBase: '0', resolvedBase: '40000', remainingIdentityId: null }] }
  expect(transferView(resolved).lines[0].receivedBase).toBe('60000')
  expect(() => transferView({ ...partial, lines: [{ ...partial.lines[0], inTransitBase: '50000' }] })).toThrow(WarehouseDataError)
  expect(() => transferView({ ...partial, lines: [{ ...partial.lines[0], remainingIdentityId: null }] })).toThrow(WarehouseDataError)
  expect(() => transferView({ ...draft, lines: [{ ...draft.lines[0], quantityBase: 100000 }] })).toThrow(WarehouseDataError)
  expect(() => transferView({ ...draft, lines: [] })).toThrow(WarehouseDataError)
})
it('rejects reference mismatches instead of attaching another document location or item name', () => {
  const valid = transferDetailsFixture()
  expect(transferDetails(valid)).toEqual(valid)
  expect(() => transferDetails({ ...valid, references: { ...valid.references, locations: valid.references.locations.slice(1) } })).toThrow(WarehouseDataError)
  expect(() => transferDetails({ ...valid, references: { ...valid.references, lines: [{ ...valid.references.lines[0], lineId: id.sku }] } })).toThrow(WarehouseDataError)
  expect(() => transferDetails({ ...valid, references: { ...valid.references, people: [] } })).toThrow(WarehouseDataError)
})
it('reads scoped server pages and keeps the captured partial receipt unchanged after response loss', async () => {
  const draft = transferFixture(), partial = { ...draft, state: 'PART_RECEIVED', revision: 2, lines: [{ ...draft.lines[0], receivedBase: '60000', inTransitBase: '40000' }] }
  const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ items: [transferDetailsFixture()], page: 1, size: 1, totalElements: 2 })))
    .mockRejectedValueOnce(new TypeError('lost response')).mockResolvedValueOnce(new Response(JSON.stringify(partial)))
  vi.stubGlobal('fetch', fetch)
  expect((await listTransfers({ page: 1, size: 1, state: 'DRAFT', locationId: id.source })).totalElements).toBe(2)
  expect(fetch.mock.calls[0][0]).toContain(`page=1&size=1&state=DRAFT&locationId=${id.source}`)
  const input = { expectedRevision: 1, evidenceReference: 'SJ-001', lines: [{ lineId: id.line, quantityBase: '60000', baseUnit: 'MM' as const }] }
  const operation = receiveTransfer(id.document, input)
  input.lines[0].quantityBase = '100000'
  await expect(operation.execute()).rejects.toThrow('lost response')
  await expect(operation.execute()).resolves.toMatchObject({ state: 'PART_RECEIVED', lines: [{ receivedBase: '60000', inTransitBase: '40000' }] })
  for (const [path, init] of fetch.mock.calls.slice(1)) {
    expect(path).toBe(`/api/v1/warehouse/transfers/${id.document}/receive`)
    expect(JSON.parse(init.body)).toMatchObject({ expectedRevision: 1, lines: [{ quantityBase: '60000' }] })
    expect((init.headers as Headers).get('Idempotency-Key')).toBe(operation.key)
  }
})
