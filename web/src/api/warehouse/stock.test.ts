import { afterEach, expect, it, vi } from 'vitest'
import { tokenStore } from '../client'
import { receiptIds } from '@/test/warehouseReceiptFixture'
import { WarehouseDataError } from './codec'
import { formatBaseQuantity } from './quantity'
import { lotDetail, position, stockAsset, stockCost, stockEvent, stockSegment, unknownStock } from './stock'
import { listStock } from './masters'

const id = receiptIds
const q = (quantityBase = '1000000', baseUnit: 'EA' | 'MM' = 'MM') => ({ quantityBase, baseUnit, displayQuantity: formatBaseQuantity(quantityBase, baseUnit, '.'), displayUnit: baseUnit === 'MM' ? 'M' : 'EA' })
const row = { id: id.document, skuId: id.sku, skuCode: 'CABLE', name: 'Kabel drop', tracking: 'LOT', stockIdentityId: id.piece, lotId: id.line, serial: null,
  locationId: id.inspection, locationName: 'Pemeriksaan barang', custodianId: id.inspection, custodianKind: 'WAREHOUSE', condition: 'QUARANTINE', legalOwner: 'ISP', status: 'QUARANTINE',
  physical: q(), reservedUnpicked: q('0'), reservedPicked: q('0'), available: q('0') }
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('keeps projection and stock identity IDs distinct and rejects inconsistent quantity units', () => {
  expect(position(row)).toMatchObject({ id: id.document, stockIdentityId: id.piece, physical: q(), available: q('0') })
  expect(() => position({ ...row, available: q('1', 'EA') })).toThrow(WarehouseDataError)
})

it('distinguishes inaccessible cost from unknown and exact known cost without guessing zero', () => {
  expect(stockCost({ state: 'UNKNOWN', totalMinor: null, costBasisQuantityBase: null, currency: null })).toEqual({ state: 'UNKNOWN' })
  expect(stockCost({ state: 'KNOWN', totalMinor: '9007199254740993', costBasisQuantityBase: '1000000', currency: 'IDR' })).toMatchObject({ totalMinor: '9007199254740993' })
  expect(() => stockCost({ state: 'KNOWN', totalMinor: 1000006, costBasisQuantityBase: '1000000', currency: 'IDR' })).toThrow(WarehouseDataError)
  expect(() => stockCost({ state: 'UNKNOWN', totalMinor: '0' })).toThrow(WarehouseDataError)
})

it('preserves unknown legacy units and hidden cost instead of assuming EA and zero cost', () => {
  const asset = stockAsset({ id: id.piece, assetId: id.piece, skuId: null, skuCode: null, name: null, serial: 'OLD-RAW', mac: null, status: 'AVAILABLE', condition: 'SERVICEABLE', legalOwner: 'UNKNOWN',
    locationId: id.inspection, locationName: 'Pemeriksaan', custodianId: id.inspection, custodianKind: 'WAREHOUSE', quantity: { quantityBase: null, baseUnit: null, displayQuantity: null, displayUnit: null }, admission: 'LEGACY_UNRESOLVED', installedOnuId: null, origin: null })
  expect(asset.quantity).toBeNull(); expect(asset.cost).toBeNull()
  const unknown = { id: id.document, source: 'BALANCE', skuId: id.sku, name: 'Kabel lama', locationId: id.inspection, status: 'AVAILABLE', rawQuantity: '37', quantityBase: null, baseUnit: null,
    legalOwner: 'UNKNOWN', admission: 'LEGACY_UNRESOLVED', serial: null, available: false, reason: 'UNVERIFIED_UNIT_OR_ORIGIN_OR_TITLE' }
  expect(unknownStock(unknown)).toMatchObject({ rawQuantity: '37', baseUnit: null, available: false })
  expect(() => unknownStock({ ...unknown, available: true })).toThrow(WarehouseDataError)
})

it('keeps inconsistent conservation visible and exposes truncated child pages without treating them as the whole tree', () => {
  const lot = { id: id.line, skuId: id.sku, code: 'R1', name: 'Kabel drop', received: q(), receivedAt: '2026-09-24T17:00:00Z', admission: 'VERIFIED', origin: null,
    conservation: { consistent: false, physicalQuantityBase: '999999', rootQuantityBase: '1000000', activeQuantityBase: '1000000', terminalQuantityBase: '0', rootCount: 1, splitCount: 1 } }
  expect(lotDetail(lot).conservation).toMatchObject({ consistent: false, physicalQuantityBase: '999999' })
  const segment = stockSegment({ id: id.piece, stockIdentityId: id.piece, lotId: id.line, parentSegmentId: null, kind: 'REEL', state: 'SPLIT', quantity: q(), createdAt: lot.receivedAt, origin: null,
    children: [id.source], childCount: 101, conserved: false })
  expect(segment).toMatchObject({ state: 'SPLIT', childCount: 101, children: [id.source], conserved: false })
})

it('decodes composite reservation history IDs without fabricating movement fields or revisions on material facts', () => {
  const event = { id: `${id.evidence}:${id.document}`, kind: 'RESERVATION', eventId: id.evidence, eventKind: 'PICK', reservationId: id.document, documentId: id.source, documentRevision: 2,
    operationId: id.supplier, recordedAt: '2026-09-24T17:00:00Z', stockIdentityId: id.piece, reservedUnpickedBase: '200000', reservedPickedBase: '100000', baseUnit: 'MM', state: 'OPEN' }
  expect(stockEvent(event)).toMatchObject({ kind: 'RESERVATION', id: event.id, reservedPickedBase: '100000' })
  expect(() => stockEvent({ ...event, id: id.evidence })).toThrow(WarehouseDataError)
  expect(stockEvent({ id: id.evidence, kind: 'MATERIAL_FACT', postingId: id.document, recordedAt: event.recordedAt, stockIdentityId: id.piece, installed: true, returned: false, useRevision: 1, compensationId: null, quantity: q('82500') })).not.toHaveProperty('documentRevision')
})

it('sends the reservation bucket to the server before pagination and preserves actual minimum quantity', async () => {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [{ ...row, id: id.sku, minimumQuantityBase: '250000', statusBuckets: { AVAILABLE: '1000000' }, conditionBuckets: { SERVICEABLE: '1000000' }, ownerBuckets: { ISP: '1000000' } }], page: 1, size: 1, totalElements: 2 }), { headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', fetch)
  const result = await listStock({ bucket: 'RESERVED', page: 1, size: 1, skuId: id.sku })
  expect(fetch.mock.calls[0][0]).toBe(`/api/v1/warehouse/stock?bucket=RESERVED&page=1&size=1&skuId=${id.sku}`)
  expect(result.items[0].minimumQuantityBase).toBe('250000')
})
