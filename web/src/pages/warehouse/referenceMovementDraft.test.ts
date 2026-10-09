import { expect, it } from 'vitest'
import type { ReferencePosition } from '@/api/warehouse/reference'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { movementLine, movementPosted } from '@/api/warehouse/referenceMovements'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { buildReceiptLines, emptyReceiptRow } from './receiptDraft'
import { buildReferenceTransferLines } from './referenceTransferDraft'

const cable = { id: id.sku, name: 'Kabel drop', code: 'DROP', tracking: 'LOT', baseUnit: 'MM' } as const
const position: ReferencePosition = { stockIdentityId: id.piece, skuId: id.sku, skuCode: 'DROP', skuName: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM',
  quantityBase: '82501', locationId: id.source, locationName: 'Gudang A', holderId: id.source, holderName: 'Gudang A', holderEmail: null,
  holderKind: 'WAREHOUSE', status: 'AVAILABLE', serial: null, mac: null, revision: 1 }
const posted = { id: id.document, operationId: id.evidence, revision: 1, kind: 'RECEIPT', state: 'PUTAWAY', warehouseId: id.inspection,
  sourceWarehouseId: null, notes: '', recordedAt: '2026-10-09T12:00:00Z' }

it('allows automatic reference lots while historical receipt drafts still require a named lot', () => {
  const row = { ...emptyReceiptRow(), sku: cable, quantity: '82,501' }
  expect(buildReceiptLines([row], false, true)).toMatchObject([{ quantityBase: '82501', lotCode: null }])
  expect(() => buildReceiptLines([row], false)).toThrow('kode lot')
  expect(() => buildReceiptLines([{ ...row, useConversion: true, numerator: '82501', denominator: '2' }], false, true)).toThrow('harus tepat')
})

it('transfers an exact partial cable amount and rejects overdraw, duplicate identities and unavailable custody', () => {
  const row = { key: 'one', position, quantity: '12,501' }
  expect(buildReferenceTransferLines([row], id.source, id.inspection)).toEqual([{ stockIdentityId: id.piece, quantityBase: '12501' }])
  expect(() => buildReferenceTransferLines([{ ...row, quantity: '82,502' }], id.source, id.inspection)).toThrow('melebihi')
  expect(() => buildReferenceTransferLines([row, { ...row, key: 'two' }], id.source, id.inspection)).toThrow('dua kali')
  expect(() => buildReferenceTransferLines([row], id.source, id.source)).toThrow('berbeda')
  for (const patch of [{ locationId: id.supplier }, { holderKind: 'TECHNICIAN' as const }, { status: 'RESERVED' }]) {
    expect(() => buildReferenceTransferLines([{ ...row, position: { ...position, ...patch } }], id.source, id.inspection)).toThrow('gudang asal')
  }
})

it('requires exactly one unit when transferring a serialized device', () => {
  const serial = { ...position, tracking: 'SERIAL' as const, baseUnit: 'EA' as const, quantityBase: '2', serial: 'ONU-1' }
  expect(() => buildReferenceTransferLines([{ key: 'one', position: serial, quantity: '2' }], id.source, id.inspection)).toThrow('1 unit')
})

it('reads optional empty movement notes but rejects mismatched posted kind state source and revision', () => {
  expect(movementPosted(posted).notes).toBe('')
  expect(movementPosted({ ...posted, kind: 'TRANSFER', state: 'RECEIVED', sourceWarehouseId: id.source }).kind).toBe('TRANSFER')
  for (const patch of [{ revision: 0 }, { state: 'DRAFT' }, { kind: 'TRANSFER' }, { sourceWarehouseId: id.source }, { kind: 'TRANSFER', state: 'RECEIVED', sourceWarehouseId: id.inspection }]) {
    expect(() => movementPosted({ ...posted, ...patch })).toThrow(WarehouseDataError)
  }
})

it('rejects malformed physical serial lines instead of showing an empty successful document', () => {
  const line = { id: id.line, lineNumber: 1, skuId: id.sku, skuCode: 'ONU', skuName: 'ONU', tracking: 'SERIAL', baseUnit: 'EA', quantityBase: '1', serial: 'ONU-1', mac: null, lotCode: null, conversion: null, cost: null }
  expect(movementLine(line).serial).toBe('ONU-1')
  for (const patch of [{ quantityBase: '0' }, { quantityBase: '2' }, { serial: null }, { baseUnit: 'MM' }, { lineNumber: 0 }]) {
    expect(() => movementLine({ ...line, ...patch })).toThrow(WarehouseDataError)
  }
})
