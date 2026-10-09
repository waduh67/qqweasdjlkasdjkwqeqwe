import { expect, it } from 'vitest'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { sku } from '@/api/warehouse/models'
import { operationalSettings, referenceRequest, requestDetail } from '@/api/warehouse/referenceRequests'
import { technicianPage } from '@/api/warehouse/technicians'
import { requestStockPreview } from '@/api/warehouse/referenceRequestStock'
import type { ReferencePosition } from '@/api/warehouse/reference'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { buildRequestLines, buildRequestReview, emptyRequestRow } from './referenceRequestDraft'
import { buildRequestHandover, requestHandoverRemaining } from './referenceRequestMovementDraft'

const cable = sku({ id: id.sku, code: 'DROP', name: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM', revision: 1, state: 'ACTIVE', inspectionRequired: false, allowedOwnershipModes: ['LOAN', 'SALE'], minimumQuantityBase: '0' })
const line = { id: id.line, baseUnit: 'MM', requestedBase: '82501', skuId: id.sku, name: 'Kabel drop', proposedName: null, approvedBase: '70000', receivedBase: '0', fulfilledBase: '0' }
const raw = { id: id.document, revision: 1, kind: 'RESTOCK', state: 'APPROVED', requesterId: id.evidence, requesterName: 'Teknisi FO',
  warehouseId: null, warehouseName: null, technicianId: id.evidence, technicianName: 'Teknisi FO', requiresManagerApproval: true,
  policyRevision: 0, reason: 'Kebutuhan pemasangan', lines: [line], createdAt: '2026-10-09T12:00:00Z', updatedAt: '2026-10-09T12:00:00Z' }
const request = referenceRequest(raw)
const position: ReferencePosition = { stockIdentityId: id.piece, skuId: id.sku, skuCode: 'DROP', skuName: 'Kabel drop', tracking: 'LOT', baseUnit: 'MM',
  quantityBase: '82501', locationId: id.source, locationName: 'Gudang A', holderId: id.source, holderName: 'Gudang A', holderEmail: null,
  holderKind: 'WAREHOUSE', status: 'AVAILABLE', serial: null, mac: null, revision: 1 }

it('keeps exact cable quantities and permits proposed materials only in procurement', () => {
  const row = { ...emptyRequestRow(), sku: cable, quantity: '82,501' }
  expect(buildRequestLines([row], 'RESTOCK')).toEqual([{ skuId: id.sku, baseUnit: 'MM', requestedBase: '82501' }])
  const proposed = { ...row, proposed: true, proposedName: ' Kabel baru ', unit: 'MM' as const }
  expect(buildRequestLines([proposed], 'PROCUREMENT')).toEqual([{ proposedName: 'Kabel baru', baseUnit: 'MM', requestedBase: '82501' }])
  expect(() => buildRequestLines([proposed], 'RESTOCK')).toThrow('pengadaan')
  expect(() => buildRequestLines([{ ...row, sku: { ...cable, state: 'ARCHIVED' } }], 'RESTOCK')).toThrow('aktif')
})

it('reviews every original line, prevents overapproval and maps proposals in the same unit', () => {
  const rows = [{ lineId: id.line, quantity: '70', mapped: null }]
  expect(buildRequestReview(request, rows)).toEqual([{ lineId: id.line, approvedBase: '70000', skuId: id.sku }])
  expect(() => buildRequestReview(request, [{ ...rows[0], quantity: '82,502' }])).toThrow('melebihi')
  expect(() => buildRequestReview(request, [{ ...rows[0], quantity: '0' }])).toThrow('minimal')
  expect(() => buildRequestReview(request, [{ ...rows[0], lineId: id.piece }])).toThrow('setiap')
  const proposal = referenceRequest({ ...raw, kind: 'PROCUREMENT', lines: [{ ...line, skuId: null, approvedBase: '0', proposedName: 'Kabel baru' }] })
  expect(() => buildRequestReview(proposal, rows)).toThrow('Hubungkan')
  expect(buildRequestReview(proposal, [{ ...rows[0], mapped: cable }])[0].skuId).toBe(id.sku)
  expect(() => buildRequestReview(proposal, [{ ...rows[0], mapped: { ...cable, baseUnit: 'EA' } }])).toThrow('satuan')
})

it('limits partial handover to received procurement and rechecks stock identity custody and quantity', () => {
  const procurement = referenceRequest({ ...raw, kind: 'PROCUREMENT', state: 'PARTIALLY_FULFILLED', lines: [{ ...line, receivedBase: '25000', fulfilledBase: '10000' }] })
  const selected = { key: 'one', position, quantity: '12,501' }
  expect(requestHandoverRemaining(procurement, procurement.lines[0])).toBe('15000')
  expect(buildRequestHandover(procurement, procurement.lines[0], [selected], id.source)).toEqual([{ stockIdentityId: id.piece, quantityBase: '12501' }])
  expect(() => buildRequestHandover(procurement, procurement.lines[0], [{ ...selected, quantity: '15,001' }], id.source)).toThrow('sisa')
  expect(() => buildRequestHandover(request, request.lines[0], [selected, { ...selected, key: 'two' }], id.source)).toThrow('dua kali')
  for (const patch of [{ status: 'RESERVED' }, { locationId: id.inspection }, { skuId: id.supplier }, { holderKind: 'TECHNICIAN' as const }]) {
    expect(() => buildRequestHandover(request, request.lines[0], [{ ...selected, position: { ...position, ...patch } }], id.source)).toThrow('tersedia')
  }
  expect(() => buildRequestHandover(request, request.lines[0], [{ ...selected, position: { ...position, quantityBase: '10000' } }], id.source)).toThrow('stok')
})

it('rejects malformed request counters destinations timeline and operational policy', () => {
  for (const patch of [{ warehouseId: id.source }, { technicianName: null }, { lines: [line, line] },
    { lines: [{ ...line, approvedBase: '82502' }] }, { kind: 'PROCUREMENT', lines: [{ ...line, receivedBase: '0', fulfilledBase: '1' }] }]) {
    expect(() => referenceRequest({ ...raw, ...patch })).toThrow(WarehouseDataError)
  }
  const event = { operationId: id.evidence, revision: 1, action: 'REVIEW', actorName: 'Admin', notes: '', movementId: null, recordedAt: raw.updatedAt }
  expect(requestDetail({ request: raw, timeline: [event] }).timeline[0].notes).toBe('')
  expect(() => requestDetail({ request: raw, timeline: [{ ...event, action: 'UNKNOWN' }] })).toThrow(WarehouseDataError)
  for (const days of [0, 366, 1.5]) expect(() => operationalSettings({ revision: 0, requireManagerApproval: true, overdueDays: days })).toThrow(WarehouseDataError)
})

it('reads the compact technician directory without silently accepting an incomplete page contract', () => {
  const row = { content: [{ id: id.evidence, name: 'Teknisi FO', email: 'fo@example.test' }], page: 1, size: 25, totalElements: 26, totalPages: 2 }
  expect(technicianPage(row)).toMatchObject({ page: 1, totalElements: 26, items: row.content })
  expect(() => technicianPage({ ...row, totalPages: 1 })).toThrow(WarehouseDataError)
  expect(() => technicianPage({ ...row, content: null })).toThrow(WarehouseDataError)
})

it('keeps exact preview totals and rejects an incomplete destination identity', () => {
  const preview = { skuId: id.sku, skuName: 'Kabel drop', baseUnit: 'MM', totalWarehouseBase: '82501',
    technicianId: id.evidence, technicianName: 'Teknisi FO', technicianQuantityBase: '12501',
    warehouses: { items: [{ warehouseId: id.source, warehouseName: 'Gudang A', quantityBase: '82501' }], page: 0, size: 25, totalElements: 1 } }
  expect(requestStockPreview(preview)).toMatchObject({ totalWarehouseBase: '82501', technicianQuantityBase: '12501' })
  for (const patch of [{ technicianName: null }, { technicianQuantityBase: null }, { technicianId: null }, { totalWarehouseBase: '82.501' }])
    expect(() => requestStockPreview({ ...preview, ...patch })).toThrow(WarehouseDataError)
})
