import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { receiptIds as id, receiptFixture } from '@/test/warehouseReceiptFixture'
import { replenishmentFixture } from '@/test/warehouseReplenishmentFixture'
import { reportPage } from '@/test/warehouseReportFixture'
import { WarehouseReplenishmentPage } from './WarehouseReplenishmentPage'
const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
function show(path = `/warehouse/replenishment?requestId=${id.document}`) { return render(<MemoryRouter initialEntries={[path]}><WarehouseReplenishmentPage /></MemoryRouter>) }
beforeEach(() => { mocks.permissions.clear(); mocks.permissions.add('inventory.request.view'); mocks.permissions.add('inventory.request.manage'); tokenStore.clear(); HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }; HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') } })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('reviews exact current request and rule revisions, preserving the same command after ambiguous response', async () => {
  let writes = 0
  const fetch = vi.fn(async (_path: string, init: RequestInit) => {
    if (init.method === 'POST') { writes++; if (writes === 1) throw new TypeError('lost'); return response({ ...replenishmentFixture().request!, revision: 5 }) }
    return response(replenishmentFixture())
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Konfirmasi kebutuhan' }))
  expect(screen.getByRole('dialog').textContent).toContain('tidak menambah stok')
  fireEvent.click(screen.getByRole('button', { name: 'Konfirmasi tindakan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const commands = fetch.mock.calls.filter(([, init]) => init.method === 'POST')
  expect(JSON.parse(commands[0][1].body as string)).toEqual({ expectedRevision: 3, expectedRuleRevision: 4, quantityBase: '100000' })
  expect(commands[0][1].body).toBe(commands[1][1].body)
  expect((commands[0][1].headers as Headers).get('Idempotency-Key')).toBe((commands[1][1].headers as Headers).get('Idempotency-Key'))
})
it('blocks stale suggestions, recomputes with actual revision and reloads after 409', async () => {
  const data = replenishmentFixture(); data.suggestedQuantityBase = '50000'
  const fetch = vi.fn(async (_path: string, init: RequestInit) => init.method === 'POST' ? response({ code: 'STALE_REVISION' }, 409) : response(data)); vi.stubGlobal('fetch', fetch); show()
  expect(await screen.findByRole('button', { name: 'Konfirmasi kebutuhan' })).toHaveProperty('disabled', true)
  expect(screen.getByText(/Saran tersimpan berbeda/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Hitung ulang saran' })); fireEvent.click(screen.getByRole('button', { name: 'Konfirmasi tindakan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(JSON.parse(fetch.mock.calls.find(([, init]) => init.method === 'POST')![1].body as string)).toEqual({ expectedRevision: 4 })
})
it('edits named rules without master permissions and reviews exact millimetres and actual revision', async () => {
  const fetch = vi.fn(async (_path: string, init: RequestInit) => response(init.method === 'PUT' ? { ...replenishmentFixture().rule, revision: 5, minimumBase: '50500' } : replenishmentFixture())); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Ubah aturan' }))
  fireEvent.change(screen.getByRole('textbox', { name: /^Minimum \(m\)/ }), { target: { value: '50,500' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau aturan' }))
  expect(within(screen.getByRole('dialog')).getByText('50,500 m')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Simpan aturan' })); await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const write = fetch.mock.calls.find(([, init]) => init.method === 'PUT')!
  expect(JSON.parse(write[1].body as string)).toMatchObject({ skuId: id.sku, locationId: id.inspection, expectedRevision: 4, minimumBase: '50500', baseUnit: 'MM' })
  expect(fetch.mock.calls.every(([path]) => path.includes('/replenishments/'))).toBe(true)
})
it('binds accepted demand to a fresh exact receipt line and keeps archive blocked until cancellation', async () => {
  mocks.permissions.add('inventory.receipt.view')
  const data = replenishmentFixture(); data.request!.acceptedAt = '2026-09-24T17:00:00Z'; data.request!.acceptedBy = id.supplier
  const receipt = receiptFixture(); receipt.id = id.evidence; receipt.revision = 8; receipt.state = 'RECEIVED_IN_INSPECTION'; receipt.lines[0].quantityBase = '100000'
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (path.includes('/receipts?')) return response(reportPage([receipt]))
    if (path.endsWith(`/receipts/${id.evidence}`)) return response(receipt)
    if (init.method === 'POST') return response({ ...data.request!, receivingDocumentId: id.evidence, receivingLineId: id.line, revision: 4 })
    return response(data)
  }); vi.stubGlobal('fetch', fetch); show()
  expect(await screen.findByRole('button', { name: 'Arsipkan aturan' })).toHaveProperty('disabled', true)
  fireEvent.click(screen.getByRole('button', { name: 'Hubungkan penerimaan' }))
  await screen.findByRole('option', { name: 'SJ-001 · Distributor kabel' })
  fireEvent.change(screen.getByRole('combobox', { name: 'Dokumen penerimaan pengisian' }), { target: { value: id.evidence } })
  await screen.findByRole('option', { name: 'Kabel drop · R1' })
  fireEvent.change(screen.getByRole('combobox', { name: 'Baris penerimaan pengisian' }), { target: { value: id.line } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau hubungan penerimaan' })); fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Hubungkan penerimaan' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  expect(JSON.parse(fetch.mock.calls.find(([, init]) => init.method === 'POST')![1].body as string)).toEqual({ expectedRevision: 3, documentId: id.evidence, documentRevision: 8, lineId: id.line })
})
it('uses server state filters before pagination and displays named read-only rows', async () => {
  mocks.permissions.delete('inventory.request.manage')
  const data = replenishmentFixture()
  const fetch = vi.fn(async (path: string) => response(reportPage(path.includes('state=CANCELLED') ? [] : [{ request: data.request, sku: data.sku, location: data.location }]))); vi.stubGlobal('fetch', fetch)
  show('/warehouse/replenishment?state=PENDING')
  const link = await screen.findByRole('link', { name: 'Kabel pengisian · CABLE' }); expect(link.getAttribute('href')).toContain(`requestId=${id.document}`)
  expect(screen.getByText('Rak pengisian')).toBeTruthy(); expect(screen.queryByRole('button', { name: 'Tambah aturan minimum' })).toBeNull()
  fireEvent.change(screen.getByRole('combobox', { name: 'Status pengisian' }), { target: { value: 'CANCELLED' } })
  await screen.findByText('Belum ada catatan pengisian sesuai filter'); expect(fetch.mock.calls.at(-1)![0]).toContain('state=CANCELLED')
})
it('does not request denied or malformed routes and exposes no read-only mutation buttons', async () => {
  const fetch = vi.fn(async () => response(replenishmentFixture())); vi.stubGlobal('fetch', fetch)
  mocks.permissions.clear(); let view = show(); expect(screen.getByText('Akses gudang dibatasi')).toBeTruthy(); view.unmount()
  mocks.permissions.add('inventory.request.view'); view = show('/warehouse/replenishment?requestId=bad'); expect(screen.getByText('Alamat pengisian stok tidak dikenal.')).toBeTruthy(); view.unmount()
  expect(fetch).not.toHaveBeenCalled(); show(); await screen.findByRole('heading', { name: 'Kabel pengisian · CABLE' })
  for (const name of ['Konfirmasi kebutuhan', 'Hitung ulang saran', 'Arsipkan aturan', 'Ubah aturan', 'Batalkan permintaan']) expect(screen.queryByRole('button', { name })).toBeNull()
})

it('creates a named exact-unit rule after validating thresholds and never invents a create revision', async () => {
  mocks.permissions.add('inventory.sku.view'); mocks.permissions.add('inventory.location.view')
  const data = replenishmentFixture(); data.rule.revision = 0; data.request = null
  const sku = { id: id.sku, code: 'CABLE', name: 'Kabel pengisian', revision: 0, state: 'ACTIVE', baseUnit: 'MM', tracking: 'LOT', category: null, model: null, allowedOwnershipModes: ['LOAN'], inspectionRequired: true, minimumQuantityBase: '0' }
  const location = { id: id.inspection, code: 'BIN', name: 'Rak pengisian', revision: 0, state: 'ACTIVE', kind: 'BIN', parentLocationId: null, siteId: null, areaId: null, custodianId: null, issueEligible: true }
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (path.includes('/skus?')) return response(reportPage([sku]))
    if (path.includes('/locations?')) return response(reportPage([location]))
    if (init.method === 'POST') return response(data.rule)
    if (path.endsWith(`/workbench/rules/${id.source}`)) return response(data)
    return response(reportPage([]))
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/replenishment?view=rules')
  fireEvent.click(await screen.findByRole('button', { name: 'Tambah aturan minimum' }))
  await screen.findByRole('option', { name: 'Kabel pengisian · CABLE' })
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang untuk pengisian' }), { target: { value: id.sku } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Gudang tujuan pengisian' })).toHaveProperty('disabled', false))
  fireEvent.change(screen.getByRole('combobox', { name: 'Gudang tujuan pengisian' }), { target: { value: id.inspection } })
  for (const [label, value] of [['Minimum', '50,500'], ['Target', '25'], ['Maksimum', '100'], ['Kelipatan kemasan', '25']]) fireEvent.change(screen.getByRole('textbox', { name: new RegExp(`^${label} \\(m\\)`) }), { target: { value } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau aturan' })); expect(screen.getByText('Minimum harus ≤ target ≤ maksimum.')).toBeTruthy()
  fireEvent.change(screen.getByRole('textbox', { name: /^Target \(m\)/ }), { target: { value: '100' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau aturan' })); fireEvent.click(screen.getByRole('button', { name: 'Simpan aturan' }))
  await screen.findByRole('heading', { name: 'Kabel pengisian · CABLE' })
  const body = JSON.parse(fetch.mock.calls.find(([, init]) => init.method === 'POST')![1].body as string)
  expect(body).toMatchObject({ skuId: id.sku, locationId: id.inspection, minimumBase: '50500', targetBase: '100000', packageMultipleBase: '25000', baseUnit: 'MM' })
  expect(body).not.toHaveProperty('expectedRevision')
})
