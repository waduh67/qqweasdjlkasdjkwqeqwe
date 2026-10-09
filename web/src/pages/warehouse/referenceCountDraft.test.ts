import { expect, it } from 'vitest'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { countSnapshot, referenceCount } from '@/api/warehouse/referenceCounts'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { buildReferenceCount } from './referenceCountDraft'

const position = { balanceId: id.line, dimension: { stockIdentityId: id.piece, skuId: id.sku, locationId: id.source }, quantityBase: '82501',
  status: 'AVAILABLE', balanceRevision: 1, pieceRevision: 2, serial: null, mac: null }
const raw = { id: id.document, skuId: id.sku, skuName: 'Kabel drop', locationId: id.source, locationName: 'Gudang utama',
  baseUnit: 'MM', tracking: 'LOT', bookBase: '82501', positions: [position], snapshotHash: 'a'.repeat(64), loadedAt: '2026-10-09T10:00:00Z' }
const draft = { quantity: '70,000', serials: '', reason: '  Hitungan fisik akhir hari  ' }

it('counts exact metres, permits zero and unchanged stock, and requires a physical count and reason', () => {
  const snapshot = countSnapshot(raw)
  expect(buildReferenceCount(snapshot, draft)).toEqual({ snapshotId: id.document, physicalBase: '70000', serials: [], reason: 'Hitungan fisik akhir hari' })
  expect(buildReferenceCount(snapshot, { ...draft, quantity: '0' }).physicalBase).toBe('0')
  expect(buildReferenceCount(snapshot, { ...draft, quantity: '82.501' }).physicalBase).toBe('82501')
  for (const quantity of ['', '-1', '1,2345', '1.234,500']) expect(() => buildReferenceCount(snapshot, { ...draft, quantity })).toThrow()
  for (const reason of ['', '  ', 'x'.repeat(1001)]) expect(() => buildReferenceCount(snapshot, { ...draft, reason })).toThrow('alasan opname')
})

it('requires exact physical serials and rejects fractional units, duplicate serials and MACs', () => {
  const snapshot = countSnapshot({ ...raw, baseUnit: 'EA', tracking: 'SERIAL', bookBase: '1', positions: [{ ...position, quantityBase: '1', serial: 'OLD-ONT' }] })
  expect(buildReferenceCount(snapshot, { ...draft, quantity: '1', serials: 'NEW-ONT, 02:00:11:22:33:44' }).serials).toEqual([{ serial: 'NEW-ONT', mac: '02:00:11:22:33:44' }])
  expect(buildReferenceCount(snapshot, { ...draft, quantity: '0' }).serials).toEqual([])
  for (const serials of ['', 'ONT-1\nONT-2']) expect(() => buildReferenceCount(snapshot, { ...draft, quantity: '1', serials })).toThrow('sama dengan jumlah unit')
  expect(() => buildReferenceCount(snapshot, { ...draft, quantity: '0,5', serials: 'ONT-1' })).toThrow('bilangan bulat')
  expect(() => buildReferenceCount(snapshot, { ...draft, quantity: '2', serials: 'ONT-1\nont-1' })).toThrow('Serial ganda')
  expect(() => buildReferenceCount(snapshot, { ...draft, quantity: '2', serials: 'ONT-1,02:00:11:22:33:44\nONT-2,020011223344' })).toThrow('MAC ganda')
})

it('rejects corrupt snapshots and audits instead of displaying guessed quantities', () => {
  for (const patch of [{ bookBase: '82500' }, { positions: [position, position] }, { positions: [{ ...position, quantityBase: '0' }] },
    { positions: [{ ...position, dimension: { ...position.dimension, locationId: id.inspection } }] }]) expect(() => countSnapshot({ ...raw, ...patch })).toThrow(WarehouseDataError)
  const audit = { id: id.inspection, snapshot: raw, physicalBase: '70000', differenceBase: '-12501', serials: [], reason: draft.reason, actorId: id.evidence,
    actorName: 'Admin', recordedAt: '2026-10-09T10:01:00Z', movementIds: [id.supplier] }
  expect(referenceCount(audit).differenceBase).toBe('-12501')
  expect(() => referenceCount({ ...audit, differenceBase: '-12500' })).toThrow(WarehouseDataError)
  expect(() => referenceCount({ ...audit, serials: [{ serial: 'ONT-1', mac: null }] })).toThrow(WarehouseDataError)
})

it('reads complete snapshots with more than the default decoder page limit', () => {
  const positions = Array.from({ length: 1001 }, (_, index) => ({ ...position, quantityBase: '1', balanceId: '00000000-0000-4000-8000-' + index.toString().padStart(12, '0') }))
  expect(countSnapshot({ ...raw, bookBase: '1001', positions }).positions).toHaveLength(1001)
})
