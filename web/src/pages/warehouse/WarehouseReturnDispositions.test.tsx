import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import type { Disposition } from '@/api/warehouse/dispositions'
import { returnDetailsFixture, returnFixture, returnIds as id, returnQuarantine } from '@/test/warehouseReturnFixture'
import { WarehouseReturnDispositions } from './WarehouseReturnDispositions'

const permissions = vi.hoisted(() => new Set<string>())
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => permissions.has(permission) }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => response({ items, page: 0, size: 25, totalElements: items.length })
const lost = { ...returnQuarantine, id: id.evidence, kind: 'LOST', code: 'LOST', name: 'Barang hilang' }
const disposition: Disposition = { id: id.rma, code: 'DSP-001', revision: 0, state: 'DRAFT', action: 'LOSS', sourceDocumentId: id.document, sourceRevision: 0,
  stockIdentityId: id.piece, skuId: id.sku, quantityBase: '17500', baseUnit: 'MM', sourceLocationId: id.inspection, destinationLocationId: lost.id, legalOwner: 'ISP',
  reason: 'Hasil pemeriksaan kehilangan', evidenceReference: 'BA-HILANG', recordedAt: '2026-09-25T02:00:00Z' }
beforeEach(() => {
  permissions.clear()
  for (const permission of ['inventory.custody.view', 'inventory.custody.manage', 'inventory.return.manage', 'inventory.approval.request', 'inventory.approval.view', 'inventory.location.view']) permissions.add(permission)
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
async function fill(target: string) {
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Lokasi tujuan disposisi' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi tujuan disposisi' }), { target: { value: target } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan disposisi / koreksi' }), { target: { value: disposition.reason } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi bukti disposisi' }), { target: { value: disposition.evidenceReference } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau permintaan disposisi' }))
}
it('creates a reviewed source-bound loss request without claiming a physical posting', async () => {
  let saved = false
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { saved = true; return response(disposition, 201) }
    if (path.includes('/locations?')) return page([lost])
    return page(saved ? [disposition] : [])
  }); vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseReturnDispositions details={returnDetailsFixture()} reload={vi.fn()} /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Ajukan kehilangan / scrap' }))
  expect(screen.getByRole('option', { name: 'Scrap barang rusak' })).toHaveProperty('disabled', true)
  await fill(lost.id)
  const dialog = await screen.findByRole('dialog', { name: 'Konfirmasi permintaan disposisi' })
  expect(dialog.textContent).toContain('17,500 m'); expect(dialog.textContent).toContain('Retur revisi 0')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan permintaan disposisi' }))
  expect(await screen.findByRole('link', { name: 'Buka persetujuan DSP-001' })).toHaveProperty('href', expect.stringContaining(id.rma))
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ sourceDocumentId: id.document, expectedRevision: 0, stockIdentityId: id.piece, quantityBase: '17500', baseUnit: 'MM', destinationLocationId: lost.id, action: 'LOSS', reason: disposition.reason, evidenceReference: disposition.evidenceReference })
  expect(screen.getByText('Draf')).toBeTruthy()
})
it('requests compensation using the persisted disposition and return revisions while preserving the original posting reference', async () => {
  const original = { ...disposition, revision: 1, state: 'POSTED' }, returned = { ...returnFixture(), revision: 4, state: 'LOST' as const, locationId: lost.id }
  const corrected = { ...disposition, id: id.field, code: 'REV-001', originalDispositionId: original.id, originalPostingId: id.repair, returnId: returned.id, sourceLocationId: lost.id, destinationLocationId: returnQuarantine.id }
  let saved = false
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { saved = true; return response(corrected, 201) }
    if (path.includes('/locations?')) return page([returnQuarantine])
    if (path.includes('/compensations')) return page(saved ? [corrected] : [])
    return page([original])
  }); vi.stubGlobal('fetch', fetch)
  const reload = vi.fn()
  render(<MemoryRouter><WarehouseReturnDispositions details={returnDetailsFixture(returned)} reload={reload} /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Ajukan koreksi ke karantina' }))
  await fill(returnQuarantine.id)
  expect((await screen.findByRole('dialog')).textContent).toContain('Retur revisi 4')
  fireEvent.click(screen.getByRole('button', { name: 'Simpan permintaan disposisi' }))
  await screen.findByRole('link', { name: 'Buka persetujuan REV-001' })
  expect(screen.getByText(`Pembukuan yang dikoreksi: ${id.repair}`)).toBeTruthy()
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(writes[0][0]).toBe(`/api/v1/warehouse/dispositions/${original.id}/compensations`)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 1, expectedReturnRevision: 4, destinationLocationId: returnQuarantine.id, reason: disposition.reason, evidenceReference: disposition.evidenceReference })
  expect(reload).not.toHaveBeenCalled()
})
it('requires current revisions after conflict instead of inventing another request', async () => {
  const fetch = vi.fn(async (path: string, init?: RequestInit) => init?.method === 'POST' ? response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) : path.includes('/locations?') ? page([lost]) : page([]))
  vi.stubGlobal('fetch', fetch); const reload = vi.fn()
  render(<MemoryRouter><WarehouseReturnDispositions details={returnDetailsFixture()} reload={reload} /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Ajukan kehilangan / scrap' })); await fill(lost.id)
  fireEvent.click(await screen.findByRole('button', { name: 'Simpan permintaan disposisi' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  expect(reload).toHaveBeenCalledOnce(); expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('keeps customer property and read-only operators out of ISP disposition actions', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => page([])))
  const first = render(<MemoryRouter><WarehouseReturnDispositions details={returnDetailsFixture(returnFixture(true))} reload={vi.fn()} /></MemoryRouter>)
  expect(screen.queryByRole('button', { name: 'Ajukan kehilangan / scrap' })).toBeNull()
  expect(screen.getByText('Barang pelanggan memerlukan penyelesaian kepemilikan sebelum disposisi ISP.')).toBeTruthy()
  first.unmount(); permissions.delete('inventory.custody.manage')
  render(<MemoryRouter><WarehouseReturnDispositions details={returnDetailsFixture()} reload={vi.fn()} /></MemoryRouter>)
  expect(screen.queryByRole('button', { name: 'Ajukan kehilangan / scrap' })).toBeNull()
})
