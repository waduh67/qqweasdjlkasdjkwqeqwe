import { expect, it } from 'vitest'
import { WarehouseDataError } from '@/api/warehouse/codec'
import type { ReferencePosition } from '@/api/warehouse/reference'
import { referenceReturn, referenceReturnDetail } from '@/api/warehouse/referenceReturns'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { buildReferenceReturn } from './referenceReturnDraft'

const position: ReferencePosition = { stockIdentityId: id.piece, skuId: id.sku, skuCode: 'DROP', skuName: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', quantityBase: '82501',
  locationId: id.source, locationName: 'Teknisi FO', holderId: id.evidence, holderName: 'Teknisi FO', holderEmail: null, holderKind: 'TECHNICIAN', status: 'ISSUED', serial: null, mac: null, revision: 1 }
const selected = { key: 'one', position, quantity: '12,501' }
const raw = { id: id.document, revision: 0, state: 'PENDING', technicianId: id.evidence, technicianName: 'Teknisi FO', sourceLocationId: id.source,
  warehouseId: id.inspection, warehouseName: 'Gudang A', skuId: id.sku, skuName: 'Kabel drop', baseUnit: 'MM', tracking: 'LOT', quantityBase: '12501',
  lines: [{ stockIdentityId: id.piece, quantityBase: '12501', serial: null, mac: null }], reason: 'Sisa pemasangan', createdAt: '2026-10-09T12:00:00Z', updatedAt: '2026-10-09T12:00:00Z', reviewerName: null, reviewNotes: null, movementId: null }

it('returns exact own cable quantities and requires current custody and a single source', () => {
  expect(buildReferenceReturn([selected], id.evidence)).toEqual({ skuId: id.sku, lines: [{ stockIdentityId: id.piece, quantityBase: '12501' }] })
  expect(() => buildReferenceReturn([selected, { ...selected, key: 'two' }], id.evidence)).toThrow('dua kali')
  expect(() => buildReferenceReturn([{ ...selected, quantity: '82,502' }], id.evidence)).toThrow('melebihi')
  expect(() => buildReferenceReturn([{ ...selected, quantity: '0' }], id.evidence)).toThrow('lebih dari nol')
  for (const patch of [{ holderId: id.supplier }, { holderKind: 'WAREHOUSE' as const }, { status: 'CONSUMED' }])
    expect(() => buildReferenceReturn([{ ...selected, position: { ...position, ...patch } }], id.evidence)).toThrow('tangan Anda')
  expect(() => buildReferenceReturn([selected, { ...selected, position: { ...position, stockIdentityId: id.line, locationId: id.inspection } }], id.evidence)).toThrow('lokasi yang sama')
})

it('rejects fractional or repeated serial identities', () => {
  const serial = { ...position, baseUnit: 'EA' as const, tracking: 'SERIAL' as const, quantityBase: '2', serial: 'ONT-1' }
  expect(buildReferenceReturn([{ ...selected, position: serial, quantity: '1' }], id.evidence).lines[0].quantityBase).toBe('1')
  expect(() => buildReferenceReturn([{ ...selected, position: serial, quantity: '2' }], id.evidence)).toThrow('1 unit')
  expect(() => buildReferenceReturn([{ ...selected, position: serial, quantity: '0,5' }], id.evidence)).toThrow('bilangan bulat')
})

it('rejects corrupt return totals and preserves immutable decision history', () => {
  expect(referenceReturn(raw).quantityBase).toBe('12501')
  for (const patch of [{ quantityBase: '12500' }, { lines: [] }, { lines: [...raw.lines, ...raw.lines] }, { state: 'UNKNOWN' }])
    expect(() => referenceReturn({ ...raw, ...patch })).toThrow(WarehouseDataError)
  expect(referenceReturnDetail({ request: raw, timeline: [{ id: id.line, revision: 0, action: 'SUBMIT', actorName: 'Teknisi FO', notes: raw.reason, recordedAt: raw.createdAt }] }).timeline[0].actorName).toBe('Teknisi FO')
  expect(() => referenceReturnDetail({ request: raw, timeline: [{ id: id.line, revision: 0, action: 'TRANSFER', actorName: 'Teknisi FO', notes: raw.reason, recordedAt: raw.createdAt }] })).toThrow(WarehouseDataError)
})
