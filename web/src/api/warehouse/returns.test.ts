import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '@/api/client'
import { returnDetailsFixture, returnFixture, returnIds as id, returnSourceFixture } from '@/test/warehouseReturnFixture'
import { WarehouseDataError } from './codec'
import { inspectReturn, listReturnSources, listReturns, returnDetails, returnHistory, returnSource, returnView } from './returns'

afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('keeps exact remnant quantity and rejects unrelated display references', () => {
  const details = returnDetailsFixture()
  expect(returnDetails(details)).toEqual(details)
  expect(() => returnDetails({ ...details, references: { ...details.references, item: { ...details.references.item, id: id.line } } })).toThrow(WarehouseDataError)
  expect(() => returnDetails({ ...details, references: { ...details.references, locations: [] } })).toThrow(WarehouseDataError)
  expect(() => returnView({ ...details.returnCase, quantityBase: 17500 })).toThrow(WarehouseDataError)
  expect(() => returnView({ ...details.returnCase, quantityBase: '17.5' })).toThrow(WarehouseDataError)
})
it('binds residual source quarantine and requires an actual single serialized removal', () => {
  const remnant = returnSourceFixture(), asset = returnSourceFixture(true)
  expect(returnSource(remnant)).toMatchObject({ quantityBase: '17500', quarantineLocationId: id.inspection, legalOwner: 'ISP' })
  expect(returnSource(asset)).toMatchObject({ quantityBase: '1', quarantineLocationId: null, legalOwner: 'CUSTOMER' })
  expect(() => returnSource({ ...remnant, quarantineLocationId: id.source })).toThrow(WarehouseDataError)
  expect(() => returnSource({ ...asset, quantityBase: '2' })).toThrow(WarehouseDataError)
  expect(() => returnSource({ ...asset, item: { ...asset.item, serial: null } })).toThrow(WarehouseDataError)
})
it('keeps the old inspection after repair receipt and does not turn a handover reference into current stock', () => {
  const asset = returnFixture(true)
  const repaired = { ...asset, revision: 4, condition: 'DAMAGED', inspection: { expectedRevision: 1, measuredQuantityBase: '1', condition: 'DAMAGED', destinationLocationId: id.inspection,
    evidenceReference: 'awal', observedSerial: 'ONU-001', resetConfirmed: false, resetEvidenceReference: null },
    repair: { id: id.repair, vendorId: id.vendor, vendorReference: 'SERV-001', repairLocationId: id.transit, dispatchRevision: 3, returnedRevision: 4, result: 'REPAIRED', receiptReference: 'SERV-KEMBALI' } }
  const decoded = returnView(repaired)
  expect(decoded.inspection?.expectedRevision).toBe(1)
  expect(decoded.legalOwner).toBe('CUSTOMER')
  const details = returnDetailsFixture(decoded)
  expect(returnDetails({ ...details, references: { ...details.references, rmaHandoverId: id.line } }).returnCase).toEqual(decoded)
  expect(() => returnView({ ...repaired, state: 'REPAIR' })).toThrow(WarehouseDataError)
  expect(() => returnView({ ...repaired, repair: { ...repaired.repair, returnedRevision: 5 } })).toThrow(WarehouseDataError)
})
it('uses bounded discovery and history and retries only the captured inspection bytes and key', async () => {
  const view = returnFixture(), details = returnDetailsFixture(view)
  const page = (items: unknown[], total = items.length) => new Response(JSON.stringify({ items, page: 0, size: 25, totalElements: total }))
  const inspected = { ...view, revision: 1, condition: 'DAMAGED', inspection: { expectedRevision: 0, measuredQuantityBase: '17500', condition: 'DAMAGED', destinationLocationId: id.inspection, evidenceReference: 'ukur', resetConfirmed: false } }
  const fetch = vi.fn().mockResolvedValueOnce(page([returnSourceFixture()])).mockResolvedValueOnce(page([details])).mockResolvedValueOnce(page([view]))
    .mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(new Response(JSON.stringify(inspected)))
  vi.stubGlobal('fetch', fetch)
  await listReturnSources({ query: 'kabel', locationId: id.inspection })
  await listReturns({ state: 'RECEIVED_IN_INSPECTION' })
  await returnHistory(id.returnCase)
  expect(fetch.mock.calls[0][0]).toContain('/returns/sources?query=kabel&locationId=')
  expect(fetch.mock.calls[1][0]).toContain('/returns/workbench?state=RECEIVED_IN_INSPECTION')
  expect(fetch.mock.calls[2][0]).toContain('/history/page?page=0&size=25')
  const input = { ...inspected.inspection, condition: 'DAMAGED' as const }
  const operation = inspectReturn(id.returnCase, input)
  input.measuredQuantityBase = '20000'
  await expect(operation.execute()).rejects.toThrow('response lost')
  await expect(operation.execute()).resolves.toMatchObject({ quantityBase: '17500', revision: 1 })
  for (const [path, init] of fetch.mock.calls.slice(3)) {
    expect(path).toBe(`/api/v1/warehouse/returns/${id.returnCase}/inspect`)
    expect(JSON.parse(init.body)).toMatchObject({ expectedRevision: 0, measuredQuantityBase: '17500' })
    expect((init.headers as Headers).get('Idempotency-Key')).toBe(operation.key)
  }
})
