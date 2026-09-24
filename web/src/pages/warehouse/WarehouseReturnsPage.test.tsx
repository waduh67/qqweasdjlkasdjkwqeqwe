import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import type { WarehouseReturn } from '@/api/warehouse/returns'
import { repairTransit, returnBin, returnDetailsFixture, returnFixture, returnIds as id, returnQuarantine, returnSourceFixture } from '@/test/warehouseReturnFixture'
import { WarehouseReturnsPage } from './WarehouseReturnsPage'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, size = 25, totalElements = items.length) => ({ items, page: number, size, totalElements })
const root = '/api/v1/warehouse/returns'
const vendor = { id: id.vendor, revision: 0, state: 'ACTIVE', code: 'SERVIS', name: 'Penyedia servis', contactReference: null }
function show(path = `/warehouse/returns?returnId=${id.returnCase}`) { return render(<MemoryRouter initialEntries={[path]}><WarehouseReturnsPage /></MemoryRouter>) }
function read(path: string, view = returnFixture()) {
  if (path.endsWith('/details')) return response(returnDetailsFixture(view))
  if (path.includes('/history/page?')) return response(page([view]))
  if (path.includes('/locations?')) return response(page([returnQuarantine, returnBin, repairTransit]))
  if (path.includes('/suppliers?')) return response(page([vendor]))
  throw new Error(`Unexpected read ${path}`)
}
function inspected(): WarehouseReturn {
  return { ...returnFixture(true), revision: 2, condition: 'DAMAGED', inspection: { expectedRevision: 1, measuredQuantityBase: '1', condition: 'DAMAGED',
    destinationLocationId: id.inspection, evidenceReference: 'cek-awal', resetConfirmed: false, observedSerial: 'ONU-001', resetEvidenceReference: null } }
}
function dispatched(): WarehouseReturn {
  return { ...inspected(), revision: 3, state: 'REPAIR', locationId: id.transit, repair: { id: id.repair, vendorId: id.vendor, vendorReference: 'SERV-001',
    repairLocationId: id.transit, dispatchRevision: 3, returnedRevision: null, result: null, receiptReference: null } }
}
function input(label: string, value: string) { fireEvent.change(screen.getByRole('textbox', { name: label }), { target: { value } }) }
async function choose(label: string, value: string) {
  await waitFor(() => expect(screen.getByRole('combobox', { name: label })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: label }), { target: { value } })
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear()
  for (const permission of ['inventory.return.view', 'inventory.return.manage', 'inventory.location.view', 'inventory.receipt.view']) mocks.permissions.add(permission)
  tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('rejects denied or ambiguous links before fetching and keeps a read-only return free of actions', async () => {
  const fetch = vi.fn(async (path: string) => read(path)); vi.stubGlobal('fetch', fetch)
  mocks.permissions.delete('inventory.return.view')
  const denied = show(); expect(screen.getByRole('alert')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled(); denied.unmount()
  mocks.permissions.add('inventory.return.view')
  const bad = show(`/warehouse/returns?returnId=${id.returnCase}&returnId=${id.returnCase}`)
  expect(screen.getByText('Alamat retur tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled(); bad.unmount()
  mocks.permissions.delete('inventory.return.manage'); show(); await screen.findByRole('heading', { name: 'RET-001' })
  expect(screen.queryByRole('button', { name: 'Periksa retur' })).toBeNull()
})
it('shows named server pages and applies source and date filters before reloading page zero', async () => {
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/locations?')) return response(page([returnQuarantine]))
    const second = path.includes('page=1'), details = returnDetailsFixture()
    details.references.code = second ? 'RET-LAST' : 'RET-FIRST'
    return response(page([details], second ? 1 : 0, 1, 2))
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/returns')
  await screen.findByRole('link', { name: 'RET-FIRST' }); expect(screen.getByText('Sisa kabel drop · REEL-001')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' })); await screen.findByRole('link', { name: 'RET-LAST' })
  fireEvent.click(screen.getByText('Filter retur'))
  await choose('Asal retur', 'MATERIAL_RESIDUAL')
  input('Cari kode atau barang retur', 'sisa')
  fireEvent.change(screen.getByLabelText('Dibuat mulai tanggal'), { target: { value: '2026-09-01' } })
  fireEvent.click(screen.getByRole('button', { name: 'Terapkan filter retur' }))
  expect(screen.getByRole('alert').textContent).toContain('tanggal awal dan akhir')
  fireEvent.change(screen.getByLabelText('Sampai tanggal'), { target: { value: '2026-09-25' } })
  fireEvent.click(screen.getByRole('button', { name: 'Terapkan filter retur' }))
  await screen.findByRole('link', { name: 'RET-FIRST' })
  const latest = fetch.mock.calls.at(-1)![0]
  expect(latest).toContain('origin=MATERIAL_RESIDUAL'); expect(latest).toContain('from='); expect(latest).toContain('until='); expect(latest).toContain('page=0')
})
it('intakes the selected acknowledged remnant after review without supplying invented quantity', async () => {
  const source = returnSourceFixture(), view = returnFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response(view, 201)
    if (path.includes('/workbench?')) return response(page([]))
    if (path.includes('/sources?')) return response(page([source]))
    if (path.endsWith(`/locations/${id.inspection}`)) return response(returnQuarantine)
    return read(path, view)
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/returns')
  fireEvent.click(screen.getByRole('button', { name: 'Terima retur baru' }))
  await choose('Sumber retur', source.sourceDocumentId)
  await screen.findByRole('form', { name: 'Penerimaan retur' })
  input('Referensi bukti penerimaan retur', 'BA-RETUR')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan retur' }))
  const dialog = await screen.findByRole('dialog', { name: 'Konfirmasi penerimaan retur' })
  expect(dialog.textContent).toContain('stok fisik tidak ditambahkan lagi')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Catat retur' })); await screen.findByRole('heading', { name: 'RET-001' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ origin: 'MATERIAL_RESIDUAL', sourceDocumentId: id.returnSource, quarantineLocationId: id.inspection, evidenceReference: 'BA-RETUR' })
})
it('requires whole measured cable and reloads actual accepted revision after inspection', async () => {
  let current = returnFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      const body = JSON.parse(String(init.body))
      current = { ...current, revision: 1, state: 'ACCEPTED', locationId: id.source, condition: 'SERVICEABLE', inspection: body }
      return response(current)
    }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Periksa retur' }))
  await choose('Kondisi hasil inspeksi', 'SERVICEABLE'); await choose('Rak barang layak pakai', id.source)
  input('Hasil ukur fisik (m)', '17,501'); input('Referensi bukti tindakan retur', 'ukur-remnant')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' }))
  expect(screen.getByRole('alert').textContent).toContain('seluruh potongan'); expect(screen.queryByRole('dialog')).toBeNull()
  input('Hasil ukur fisik (m)', '17,500'); fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Catat tindakan retur' }))
  await waitFor(() => expect(screen.getByRole('region', { name: 'Detail retur' }).textContent).toContain('Lolos pemeriksaan'))
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ expectedRevision: 0, measuredQuantityBase: '17500', destinationLocationId: id.source, resetConfirmed: false })
})
it('serial Enter does not submit and customer inspection requires reset while retaining quarantine', async () => {
  let current = returnFixture(true)
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = { ...current, revision: 2, condition: 'SERVICEABLE', inspection: JSON.parse(String(init.body)) }; return response(current) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Periksa retur' }))
  await choose('Kondisi hasil inspeksi', 'SERVICEABLE'); await choose('Karantina tujuan', id.inspection)
  input('Hasil ukur fisik (unit)', '1'); input('Serial fisik yang dipindai', 'ONU-001')
  fireEvent.keyDown(screen.getByRole('textbox', { name: 'Serial fisik yang dipindai' }), { key: 'Enter' })
  expect(screen.queryByRole('dialog')).toBeNull()
  input('Referensi bukti reset', 'reset-pabrik'); input('Referensi bukti tindakan retur', 'cek-unit')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' }))
  expect(screen.getByRole('alert').textContent).toContain('Konfirmasikan reset')
  fireEvent.click(screen.getByRole('checkbox', { name: 'Reset perangkat dan hapus konfigurasi lama sudah dilakukan' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Barang tetap di karantina')
  fireEvent.click(screen.getByRole('button', { name: 'Catat tindakan retur' })); await screen.findByRole('heading', { name: 'RET-001' })
  expect(screen.getByRole('region', { name: 'Detail retur' }).textContent).toContain('Milik pelanggan')
  const write = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(JSON.parse(String(write[1]?.body))).toMatchObject({ expectedRevision: 1, measuredQuantityBase: '1', observedSerial: 'ONU-001', resetConfirmed: true, resetEvidenceReference: 'reset-pabrik', destinationLocationId: id.inspection })
})
it('dispatches an inspected serial to the chosen vendor without claiming returned or available stock', async () => {
  let current = inspected()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = dispatched(); return response(current) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Kirim ke servis' }))
  await choose('Penyedia servis', id.vendor); await choose('Lokasi penguasaan servis', id.transit)
  input('Serial fisik yang dipindai', 'ONU-001'); input('Referensi servis penyedia', 'SERV-001'); input('Referensi bukti tindakan retur', 'serah-servis')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('belum tersedia')
  fireEvent.click(screen.getByRole('button', { name: 'Catat tindakan retur' })); await screen.findByRole('button', { name: 'Terima dari servis' })
  expect(screen.queryByRole('button', { name: 'Periksa retur' })).toBeNull()
  const write = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(write[0]).toBe(`${root}/${id.returnCase}/repair-dispatch`)
  expect(JSON.parse(String(write[1]?.body))).toMatchObject({ expectedRevision: 2, vendorId: id.vendor, repairLocationId: id.transit, observedSerial: 'ONU-001' })
})
it('receives the same repaired device into quarantine and calls for a new inspection', async () => {
  let current = dispatched()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      current = { ...current, revision: 4, state: 'RECEIVED_IN_INSPECTION', locationId: id.inspection, repair: { ...current.repair!, returnedRevision: 4, result: 'REPAIRED', receiptReference: 'SERV-KEMBALI' } }
      return response(current)
    }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Terima dari servis' }))
  await choose('Karantina tujuan', id.inspection); await choose('Hasil servis', 'REPAIRED')
  input('Serial fisik yang dipindai', 'ONU-001'); input('Referensi servis penyedia', 'SERV-KEMBALI'); input('Referensi bukti tindakan retur', 'bukti-kembali')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' })); fireEvent.click(await screen.findByRole('button', { name: 'Catat tindakan retur' }))
  await screen.findByText('Perangkat sudah kembali dari servis dan perlu inspeksi serta reset ulang.')
  expect(screen.queryByRole('button', { name: 'Kirim ke servis' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Periksa retur' }))
  await screen.findByText(/pemeriksaan sebelumnya belum memenuhi penerimaan ini/)
  const write = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(write[0]).toBe(`${root}/${id.returnCase}/repair-receive`)
  expect(JSON.parse(String(write[1]?.body))).toMatchObject({ expectedRevision: 3, result: 'REPAIRED', observedSerial: 'ONU-001', quarantineLocationId: id.inspection })
})
it('reloads a conflicting inspection without blind retries or retaining the old form', async () => {
  let current = returnFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = { ...current, revision: 1, condition: 'DAMAGED', inspection: JSON.parse(String(init.body)) }; return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Periksa retur' })); await choose('Karantina tujuan', id.inspection)
  input('Hasil ukur fisik (m)', '17,500'); input('Referensi bukti tindakan retur', 'bukti')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tindakan retur' })); fireEvent.click(await screen.findByRole('button', { name: 'Catat tindakan retur' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' })); await screen.findByRole('heading', { name: 'RET-001' })
  expect(screen.queryByRole('form', { name: 'Inspeksi retur' })).toBeNull()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  expect(screen.getByRole('region', { name: 'Detail retur' }).textContent).toContain('Revisi 1')
})
it('does not inspect a handed-over customer device and pages immutable history', async () => {
  const details = returnDetailsFixture(inspected()); details.references.rmaHandoverId = id.line
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/history/page?')) { const second = path.includes('page=1'); return response(page([second ? returnFixture(true) : inspected()], second ? 1 : 0, 1, 2)) }
    return response(details)
  }); vi.stubGlobal('fetch', fetch); show()
  await screen.findByRole('heading', { name: 'RET-001' }); expect(screen.queryByRole('button', { name: 'Periksa retur' })).toBeNull()
  expect(screen.getByText(/Lokasi dokumen retur adalah catatan sebelumnya/)).toBeTruthy()
  fireEvent.click(screen.getByText('Riwayat retur dan servis'))
  const history = screen.getByText('Riwayat retur dan servis').closest('details')!
  await within(history).findByRole('heading', { name: /Revisi 2/ })
  fireEvent.click(within(history).getByRole('button', { name: 'Berikutnya' })); await within(history).findByRole('heading', { name: /Revisi 1/ })
  expect(fetch.mock.calls.some(([path]) => path.endsWith('/history/page?page=1&size=25'))).toBe(true)
})
