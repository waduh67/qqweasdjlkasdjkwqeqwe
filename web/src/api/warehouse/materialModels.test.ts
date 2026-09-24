import { afterEach, expect, it, vi } from 'vitest'
import { receiptIds as id } from '@/test/warehouseReceiptFixture'
import { tokenStore } from '@/api/client'
import { WarehouseDataError } from './codec'
import { materialPlan, materialSummary, materialTotals } from './materialModels'
import { issueRow, issueSlip } from './issueModels'
import { materialRequestAction, pickMaterials, saveMaterialPlan } from './materials'
import { listMaterialWorkOrders } from './workOrders'

const totals = { planLineId: id.line, skuId: id.sku, baseUnit: 'MM', requestedBase: '200000', reservedUnpickedBase: '50000', reservedPickedBase: '20000', issuedBase: '100000',
  physicallyUsedBase: '60000', returnedBase: '10000', transferredOutBase: '0', disposedBase: '0', stillAccountableBase: '30000', backorderBase: '30000' }
const sku = { id: id.sku, revision: 0, code: 'CABLE', name: 'Kabel drop', baseUnit: 'MM', tracking: 'LOT' }
const plan = { id: id.document, workOrderId: id.source, workOrderCode: 'WO-TEST', workType: 'PREVENTIVE', action: 'PREVENTIVE', customerId: null, workOrderRevision: 1, planRevision: 2,
  materialMode: 'MATERIAL_REQUIRED', reason: null, templateId: null, actorId: id.supplier, recordedAt: '2026-09-24T18:20:00Z', lines: [{ id: id.line, lineNumber: 1, sku, quantityBase: '200000', continuousCut: true, substitution: null, originalSku: null }] }
const dimension = { skuId: id.sku, stockIdentityId: id.piece, lotId: id.evidence, locationId: id.inspection, custodianId: id.inspection, custodianKind: 'WAREHOUSE', condition: 'SERVICEABLE', legalOwner: 'ISP' }
const slip = { issueId: id.document, code: 'ISS-TEST', revision: 1, state: 'PICKED', workOrderId: id.source, workOrderCode: 'WO-TEST', workOrderRevision: 1, customerId: null, customerLabelSnapshot: null,
  demandDocumentId: id.evidence, demandRevision: 2, planId: id.supplier, planRevision: 1, sender: { id: id.source, name: 'Petugas gudang' }, receiver: { id: id.inspection, name: 'Teknisi penerima' }, recordedAt: plan.recordedAt,
  lines: [{ id: id.line, demandLineId: id.evidence, planLineId: id.source, reservationId: id.supplier, reservationRevision: 1, dimension, sourceIdentityId: id.document, quantityBase: '100000', baseUnit: 'MM', sku,
    serial: null, lotCode: 'REEL-1', locationName: 'Rak A', substitution: null, originalSku: null }], destinations: [] }
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('keeps issued and accountable quantities distinct and validates quantitative backorder without rounding', () => {
  expect(materialTotals(totals)).toMatchObject({ issuedBase: '100000', stillAccountableBase: '30000', backorderBase: '30000' })
  expect(() => materialTotals({ ...totals, backorderBase: '130000' })).toThrow(WarehouseDataError)
  expect(() => materialTotals({ ...totals, issuedBase: 100000 })).toThrow(WarehouseDataError)
  expect(materialTotals({ ...totals, requestedBase: '9007199254940993', backorderBase: '9007199254770993' }).requestedBase).toBe('9007199254940993')
})

it('preserves original substitution identity and named immutable snapshots without inventing a customer', () => {
  const replacement = { ...plan, lines: [{ ...plan.lines[0], sku: { ...sku, id: id.inspection, name: 'Kabel pengganti' }, substitution: { originalPlanLineId: id.evidence, originalSkuId: id.sku, reason: 'Jenis setara disetujui' }, originalSku: sku }] }
  expect(materialPlan(replacement)).toMatchObject({ customerId: null, planRevision: 2, lines: [{ sku: { name: 'Kabel pengganti' }, originalSku: { name: 'Kabel drop' }, substitution: { reason: 'Jenis setara disetujui' } }] })
  expect(() => materialPlan({ ...replacement, lines: [{ ...replacement.lines[0], quantityBase: '0' }] })).toThrow(WarehouseDataError)
})

it('preserves an absent plan and demand as unplanned instead of silently declaring no materials or zero revision', () => {
  const value = { workOrderId: id.source, materialMode: 'MATERIAL_REQUIRED', noMaterialReason: null, revisions: { workOrderRevision: 1, planRevision: 0, useRevision: 0, settlementRevision: 0 }, demandState: 'DRAFT',
    installationState: 'NOT_APPLICABLE', qaState: 'PENDING', provisioningState: 'NOT_APPLICABLE', settlementState: 'OPEN', lines: [], plan: null, demandDocumentId: null, demandRevision: null, template: null }
  expect(materialSummary(value)).toMatchObject({ plan: null, materialMode: 'MATERIAL_REQUIRED', demandDocumentId: null, demandRevision: null })
  expect(() => materialSummary({ ...value, revisions: { ...value.revisions, workOrderRevision: undefined } })).toThrow(WarehouseDataError)
})

it('does not infer received quantities from a dispatched material summary', () => {
  const value = { workOrderId: id.source, materialMode: 'MATERIAL_REQUIRED', noMaterialReason: null, revisions: { workOrderRevision: 1, planRevision: 2, useRevision: 0, settlementRevision: 0 }, demandState: 'PART_ISSUED',
    installationState: 'NOT_APPLICABLE', qaState: 'PENDING', provisioningState: 'NOT_APPLICABLE', settlementState: 'OPEN', lines: [totals], plan, demandDocumentId: id.piece, demandRevision: 4, template: null }
  expect(materialSummary(value).lines[0]).not.toHaveProperty('receivedBase')
  expect(materialSummary(value).lines[0]).not.toHaveProperty('acceptedBase')
})

it('decodes the actual direct plan snapshot and reservation acknowledgement contracts', async () => {
  const fetch = vi.fn().mockResolvedValueOnce(response(plan)).mockResolvedValueOnce(response({ documentId: id.evidence, revision: 3, state: 'PART_RESERVED', operationId: id.document, shortage: true, lines: [], reservations: [] }))
  vi.stubGlobal('fetch', fetch)
  const saved = await saveMaterialPlan(id.source, { expectedRevision: 1, workOrderRevision: 1, materialMode: 'MATERIAL_REQUIRED', lines: [{ skuId: id.sku, quantityBase: '200000', baseUnit: 'MM', continuousCut: true }] }).execute()
  expect(saved.planRevision).toBe(2)
  const reserved = await materialRequestAction(id.evidence, 'reserve', { expectedRevision: 2, workOrderRevision: 1, planRevision: 2, lines: [{ demandLineId: id.line, partialQuantityBase: '100000' }] }).execute()
  expect(reserved).toMatchObject({ documentId: id.evidence, revision: 3, shortage: true })
  expect(fetch.mock.calls[1][0]).toBe(`/api/v1/warehouse/material-requests/${id.evidence}/reserve`)
})

it('retries the captured exact picking request after response loss while preserving parent and resulting cut identities', async () => {
  const fetch = vi.fn().mockRejectedValueOnce(new TypeError('response lost')).mockResolvedValueOnce(response(slip)); vi.stubGlobal('fetch', fetch)
  const input = { expectedRevision: 1, workOrderRevision: 1, demandRevision: 2, lines: [{ reservationId: id.supplier, expectedRevision: 0, stockIdentityId: id.document, stockRevision: 0, quantityBase: '100000', baseUnit: 'MM' as const }] }
  const captured = pickMaterials(id.source, input)
  await expect(captured.execute()).rejects.toThrow()
  input.lines[0].quantityBase = '999999'
  const result = await captured.execute()
  expect(result.lines[0]).toMatchObject({ sourceIdentityId: id.document, dimension: { stockIdentityId: id.piece }, quantityBase: '100000' })
  expect(fetch.mock.calls[0][1].body).toBe(fetch.mock.calls[1][1].body)
  expect((fetch.mock.calls[0][1].headers as Headers).get('Idempotency-Key')).toBe((fetch.mock.calls[1][1].headers as Headers).get('Idempotency-Key'))
})

it('preserves named receiver and actual warehouse transit instead of declaring technician receipt', () => {
  const decoded = issueSlip({ ...slip, state: 'DISPATCHED', revision: 2, destinations: [{ ...dimension, locationId: id.evidence, custodianId: id.evidence }] })
  expect(decoded.receiver.name).toBe('Teknisi penerima')
  expect(decoded.destinations[0].custodianKind).toBe('WAREHOUSE')
  expect(decoded.state).toBe('DISPATCHED')
  expect(() => issueSlip({ ...slip, receiver: null })).toThrow(WarehouseDataError)
  expect(() => issueSlip({ ...slip, lines: [{ ...slip.lines[0], baseUnit: 'EA' }] })).toThrow(WarehouseDataError)
})

it('reads the current receipt revision separately from a historical dispatch slip and rejects impossible accepted totals', () => {
  const value = { ...slip, id: slip.issueId, revision: 3, state: 'PART_RECEIVED', unpicked: false, createdAt: slip.recordedAt,
    lines: [{ issueLineId: id.line, planLineId: id.source, sku, serial: null, lotCode: 'R1', locationName: 'Rak A', baseUnit: 'MM', pickedBase: '0', dispatchedBase: '100000', acceptedBase: '60000' }] }
  expect(issueRow(value)).toMatchObject({ revision: 3, state: 'PART_RECEIVED', lines: [{ acceptedBase: '60000', dispatchedBase: '100000' }] })
  expect(issueSlip({ ...slip, revision: 2, state: 'DISPATCHED' }).revision).toBe(2)
  expect(() => issueRow({ ...value, lines: [{ ...value.lines[0], acceptedBase: '100001' }] })).toThrow(WarehouseDataError)
})

it('requests server pages for work order names without truncating a first page into a search result', async () => {
  const fetch = vi.fn().mockResolvedValue(response({ content: [{ id: id.source, code: 'WO-LAST', title: 'Pemeliharaan', status: 'ASSIGNED', type: 'PREVENTIVE', customerId: null, customerName: null, assignees: [{ id: id.inspection, name: 'Teknisi' }] }], page: 1, size: 25, totalElements: 26, totalPages: 2 }))
  vi.stubGlobal('fetch', fetch)
  const result = await listMaterialWorkOrders({ query: 'Pemeliharaan', page: 1 })
  expect(result).toMatchObject({ page: 1, totalElements: 26, items: [{ code: 'WO-LAST', customerId: null, assignees: [{ name: 'Teknisi' }] }] })
  expect(fetch.mock.calls[0][0]).toBe('/api/work-orders?query=Pemeliharaan&page=1&size=25')
})
