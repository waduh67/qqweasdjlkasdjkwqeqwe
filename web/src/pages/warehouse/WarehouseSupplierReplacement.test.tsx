import { useState } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { returnDetailsFixture, returnFixture, returnIds as id, returnQuarantine } from '@/test/warehouseReturnFixture'
import { receiptFixture } from '@/test/warehouseReceiptFixture'
import type { ReturnDetails, SupplierReplacement } from '@/api/warehouse/returns'
import { WarehouseSupplierReplacements } from './WarehouseSupplierReplacement'
import { buildReplacement } from './replacementDraft'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const source = { ...returnQuarantine, id: id.source, code: 'RECEIPT_SOURCE', name: 'Batas penerimaan', kind: 'TRANSIT' as const }
function details(): ReturnDetails {
  return returnDetailsFixture({ ...returnFixture(true), revision: 3, state: 'REPAIR', locationId: id.transit, repair: {
    id: id.repair, vendorId: id.vendor, vendorReference: 'SERV-001', repairLocationId: id.transit, dispatchRevision: 3, returnedRevision: null, result: null, receiptReference: null } })
}
function Fixture() {
  const [revision, setRevision] = useState(0)
  return <WarehouseSupplierReplacements key={revision} details={details()} reload={() => setRevision(row => row + 1)} />
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear()
  for (const permission of ['inventory.return.view', 'inventory.return.manage', 'inventory.receipt.view', 'inventory.receipt.manage', 'inventory.location.view']) mocks.permissions.add(permission)
  tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('binds one different serial to the same SKU and keeps unknown cost distinct from explicit zero', () => {
  const build = (serial: string, cost: { totalMinor: string; currency: string } | null = null) => buildReplacement(details(), source, returnQuarantine, serial, '', 'SJ-BARU', 'BA-BARU', cost)
  expect(build('ONU-BARU')).toMatchObject({ expectedRevision: 3, skuId: id.sku, serial: 'ONU-BARU', inspectionLocationId: id.inspection })
  expect(build('ONU-BARU')).not.toHaveProperty('cost')
  expect(build('ONU-BARU', { totalMinor: '0', currency: 'idr' }).cost).toEqual({ totalMinor: '0', currency: 'IDR' })
  expect(() => build('onu-001')).toThrow('berbeda dari perangkat lama')
  expect(() => build('A\nB')).toThrow('satu serial')
  expect(() => buildReplacement(details(), source, returnQuarantine, '', 'AA:BB:CC:DD:EE:FF', 'SJ', 'BA', null)).toThrow()
})
it('creates only a draft then reloads its named receipt link while retaining customer ownership', async () => {
  const rows: SupplierReplacement[] = []
  const ref: SupplierReplacement = { id: id.line, returnId: id.returnCase, repairCaseId: id.repair, receiptId: id.evidence, originalAssetId: id.piece, legalOwner: 'CUSTOMER', replacementAssetId: null }
  const receipt = { ...receiptFixture(), id: id.evidence, externalReference: 'SJ-PENGGANTI', supplierName: 'Penyedia servis' }
  receipt.lines = receipt.lines.map(line => ({ ...line, skuId: id.sku, skuName: 'ONU pelanggan', serial: 'ONU-BARU' }))
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { rows.push(ref); return response(ref, 201) }
    if (path.includes('/replacement-receipts?')) return response(rows)
    if (path.includes('/locations?')) return response({ items: [source, returnQuarantine], page: 0, size: 25, totalElements: 2 })
    if (path.endsWith(`/receipts/${id.evidence}`)) return response(receipt)
    throw new Error(`Unexpected read ${path}`)
  }); vi.stubGlobal('fetch', fetch); render(<MemoryRouter><Fixture /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Siapkan penerimaan pengganti' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Batas penerimaan pengganti' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Batas penerimaan pengganti' }), { target: { value: id.source } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Karantina perangkat pengganti' }), { target: { value: id.inspection } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Serial perangkat pengganti' }), { target: { value: 'ONU-BARU' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi surat pengganti' }), { target: { value: 'SJ-PENGGANTI' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi bukti pengganti' }), { target: { value: 'BA-PENGGANTI' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau draft pengganti' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Perangkat lama tetap tercatat')
  expect(screen.queryByRole('checkbox', { name: 'Nilai pengganti diketahui' })).toBeNull()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Buat draft pengganti' }))
  const link = await screen.findByRole('link', { name: 'Buka penerimaan SJ-PENGGANTI' })
  expect(link.getAttribute('href')).toContain(`receiptId=${id.evidence}`)
  expect(screen.getByText('Masih draft. Belum ada perangkat pengganti yang diterima.')).toBeTruthy()
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(writes[0][0]).toBe(`/api/v1/warehouse/returns/${id.returnCase}/replacement-receipts`)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 3, skuId: id.sku, serial: 'ONU-BARU', sourceLocationId: id.source, inspectionLocationId: id.inspection, externalReference: 'SJ-PENGGANTI', evidenceReference: 'BA-PENGGANTI' })
})
it('shows why receipt creation is unavailable without receipt management permission', async () => {
  mocks.permissions.delete('inventory.receipt.manage')
  const fetch = vi.fn().mockResolvedValue(response([])); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><Fixture /></MemoryRouter>)
  expect(await screen.findByRole('button', { name: 'Siapkan penerimaan pengganti' })).toHaveProperty('disabled', true)
  expect(screen.getByText(/memerlukan izin kelola retur, kelola penerimaan/)).toBeTruthy()
})
