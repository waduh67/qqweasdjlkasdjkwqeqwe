import { selectControl } from '@/test/selectControl'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { WarehouseLocationEditor } from './WarehouseLocationEditor'
import { WarehouseScopePanel } from './WarehouseScopePanel'
import { WarehouseCatalogPage } from './WarehouseCatalogPage'
import { WarehouseWorkflowProvider } from './WarehouseWorkflowContext'

const mocks = vi.hoisted(() => {
  const permissions = new Set(['inventory.location.view', 'inventory.location.manage', 'iam.area.view', 'iam.user.view', 'network.site.view'])
  return { can: (permission: string) => permissions.has(permission), toast: vi.fn(), owner: false, profile: {
    id: '489bd4ce-2d4c-400d-8a7e-3019cf6e45d4', name: 'Admin gudang', email: 'admin@example.test', platformAdmin: false,
    areaIds: ['84a4943e-19b8-498d-b420-f1e6a9fda00d'],
  } }
})
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: mocks.profile }) }))
vi.mock('@/system', () => ({ useToast: () => ({ success: mocks.toast }) }))
const id = '797b131a-ddaf-46e4-90a0-e20c6ef3c5ea'
const areaId = mocks.profile.areaIds[0]
const parent = { id, code: 'MAIN', name: 'Gudang utama', revision: 0, state: 'ACTIVE', kind: 'WAREHOUSE', parentLocationId: null, areaId, siteId: null, custodianId: null, issueEligible: true }
const grant = { id: '593eec9e-810d-4dd6-a4fb-c064a86c0eec', userId: mocks.profile.id, locationId: id, active: false, revision: 5 }
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => ({ items, page: 0, size: 25, totalElements: items.length })
function directory(path: string) {
  if (path === '/api/v2/warehouse/workflow') return response({ owner: mocks.owner, snapshot: { tenantId: '2ac7f1ab-d2dc-430c-bfac-b1d01ba3b918', epoch: 0, state: 'LEGACY', workflow: 'LEGACY' } })
  if (path === '/api/areas') return response([{ id: areaId, code: 'AREA', name: 'Area timur', parentId: null }])
  if (path.startsWith('/api/v1/warehouse/locations?')) return response(page([parent]))
  if (path.startsWith('/api/users?') || path.startsWith('/api/sites?')) return response({ content: [], page: 0, size: 25, totalElements: 0 })
  if (path.includes('/settings/scopes/')) return response([grant])
  throw new Error('Unexpected fixture request: ' + path)
}
beforeEach(() => {
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: { configurable: true, value: function (this: HTMLDialogElement) { this.open = true } },
    close: { configurable: true, value: function (this: HTMLDialogElement) { this.open = false } },
  })
  mocks.toast.mockClear(); tokenStore.clear(); mocks.owner = false; mocks.profile.areaIds = [areaId]
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('keeps one named location panel through loading and closes an untouched form without a discard prompt', async () => {
  let releaseAreas!: (value: Response) => void
  const areas = new Promise<Response>(resolve => { releaseAreas = resolve })
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/areas' ? areas : directory(path)))
  const onClose = vi.fn()
  render(<MemoryRouter><WarehouseWorkflowProvider><WarehouseLocationEditor row={null} readOnly={false} onClose={onClose} onSaved={vi.fn()} onReload={vi.fn()} /></WarehouseWorkflowProvider></MemoryRouter>)
  const panel = screen.getByRole('dialog', { name: 'Tambah lokasi' })
  expect((screen.getByRole('tab', { name: 'Tinjau + buat' }) as HTMLButtonElement).disabled).toBe(true)
  expect(screen.queryByRole('textbox', { name: 'Nama lokasi' })).toBeNull()
  await act(async () => { releaseAreas(directory('/api/areas')) })
  await screen.findByRole('textbox', { name: 'Nama lokasi' })
  expect(screen.getByRole('dialog', { name: 'Tambah lokasi' })).toBe(panel)
  expect((screen.getByRole('tab', { name: 'Tinjau + buat' }) as HTMLButtonElement).disabled).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Tutup' }))
  expect(onClose).toHaveBeenCalledOnce()
  expect(screen.queryByRole('dialog', { name: 'Buang perubahan?' })).toBeNull()
})

it('selecting a named bin parent uses its real area and requires review before creation', async () => {
  const fetch = vi.fn(async (path: string, init: RequestInit) => init.method === 'POST' ? response({ ...parent, id: '39f74e3d-0e6b-4a90-a535-9989c74fa043', kind: 'BIN', code: 'BIN-A', name: 'Rak A', parentLocationId: id }) : directory(path))
  vi.stubGlobal('fetch', fetch)
  const onSaved = vi.fn()
  render(<MemoryRouter><WarehouseWorkflowProvider><WarehouseLocationEditor row={null} readOnly={false} onClose={vi.fn()} onSaved={onSaved} onReload={vi.fn()} /></WarehouseWorkflowProvider></MemoryRouter>)
  fireEvent.change(await screen.findByRole('textbox', { name: 'Kode lokasi' }), { target: { value: 'BIN-A' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Nama lokasi' }), { target: { value: 'Rak A' } })
  await selectControl(screen.getByRole('combobox', { name: 'Jenis lokasi' }), { target: { value: 'BIN' } })
  fireEvent.click(screen.getByRole('combobox', { name: 'Lokasi induk' }));
  await screen.findByRole('option', { name: 'Gudang utama · MAIN' })
  await selectControl(screen.getByRole('combobox', { name: 'Lokasi induk' }), { target: { value: id } })
  expect((screen.getByRole('combobox', { name: 'Area lokasi' }) as HTMLSelectElement).value).toBe(areaId)
  fireEvent.submit(document.querySelector('form')!)
  expect(fetch.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan lokasi' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  const mutation = fetch.mock.calls.find(([, init]) => init.method === 'POST')!
  expect(JSON.parse(mutation[1].body as string)).toEqual({ code: 'BIN-A', name: 'Rak A', kind: 'BIN', areaId, parentLocationId: id, siteId: null, custodianId: null, issueEligible: true })
})

it('lets a verified tenant owner with empty profile area grants select an area and review a location', async () => {
  mocks.owner = true; mocks.profile.areaIds = []
  const fetch = vi.fn(async (path: string, init: RequestInit) => init.method === 'POST' ? response(parent) : directory(path))
  vi.stubGlobal('fetch', fetch)
  const onSaved = vi.fn()
  render(<MemoryRouter><WarehouseWorkflowProvider><WarehouseLocationEditor row={null} readOnly={false} onClose={vi.fn()} onSaved={onSaved} onReload={vi.fn()} /></WarehouseWorkflowProvider></MemoryRouter>)
  const area = await screen.findByRole('combobox', { name: 'Area lokasi' })
  await within(area).findByRole('option', { name: 'Area timur · AREA' })
  fireEvent.change(screen.getByRole('textbox', { name: 'Kode lokasi' }), { target: { value: 'MAIN' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Nama lokasi' }), { target: { value: 'Gudang utama' } })
  await selectControl(area, { target: { value: areaId } })
  fireEvent.submit(document.querySelector('form')!)
  expect(fetch.mock.calls.filter(([, init]) => init.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan lokasi' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  const mutation = fetch.mock.calls.find(([, init]) => init.method === 'POST')!
  expect(JSON.parse(mutation[1].body as string).areaId).toBe(areaId)
})

it('keeps an ordinary user with no area grants restricted even when the directory returns areas', async () => {
  mocks.profile.areaIds = []
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method && init.method !== 'GET') throw new Error('Unexpected fixture mutation: ' + path)
    return directory(path)
  })
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseWorkflowProvider><WarehouseLocationEditor row={null} readOnly={false} onClose={vi.fn()} onSaved={vi.fn()} onReload={vi.fn()} /></WarehouseWorkflowProvider></MemoryRouter>)
  const area = await screen.findByRole('combobox', { name: 'Area lokasi' })
  expect(within(area).queryByRole('option', { name: 'Area timur · AREA' })).toBeNull()
  expect(area).toHaveProperty('required', true)
  fireEvent.submit(document.querySelector('form')!)
  expect(fetch.mock.calls.every(([, init]) => !init || !init.method || init.method === 'GET')).toBe(true)
  expect(screen.queryByRole('button', { name: 'Simpan lokasi' })).toBeNull()
})

it.each([true, false])('shows the missing-area catalogue warning only for ordinary users (owner=%s)', async owner => {
  mocks.owner = owner; mocks.profile.areaIds = []
  vi.stubGlobal('fetch', vi.fn(async (path: string) => directory(path)))
  render(<MemoryRouter><WarehouseWorkflowProvider><WarehouseCatalogPage /></WarehouseWorkflowProvider></MemoryRouter>)
  await screen.findByRole('gridcell', { name: /^Gudang utama$/ })
  await waitFor(() => expect(screen.queryByText(/Belum ada area gudang/)).toEqual(owner ? null : expect.any(HTMLElement)))
})

it('an existing revoked grant is reactivated using its actual revision instead of treating it as new', async () => {
  const fetch = vi.fn(async (path: string, init: RequestInit) => init.method === 'PUT' ? response({ ...grant, active: true, revision: 6 }) : directory(path))
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseScopePanel /></MemoryRouter>)
  fireEvent.click(screen.getByRole('combobox', { name: 'Lokasi' }));
  await screen.findByRole('option', { name: 'Gudang utama · MAIN' })
  await selectControl(screen.getByRole('combobox', { name: 'Lokasi' }), { target: { value: id } })
  await screen.findByText('Pemberian akses langsung: dicabut · Revisi 5')
  fireEvent.click(screen.getByRole('button', { name: 'Berikan akses langsung' }))
  fireEvent.click(screen.getByRole('button', { name: /^Berikan akses$/ }))
  await waitFor(() => expect(mocks.toast).toHaveBeenCalledWith('Akses gudang disimpan'))
  const mutation = fetch.mock.calls.find(([, init]) => init.method === 'PUT')!
  expect(mutation[0]).toBe(`/api/v1/warehouse/settings/scopes/${mocks.profile.id}/${id}`)
  expect(JSON.parse(mutation[1].body as string)).toEqual({ expectedRevision: 5, active: true })
})

it('an inaccessible scope list never becomes an empty grant list or enables a guessed revision-zero command', async () => {
  const fetch = vi.fn(async (path: string) => path.includes('/settings/scopes/') ? response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404) : directory(path))
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseScopePanel /></MemoryRouter>)
  fireEvent.click(screen.getByRole('combobox', { name: 'Lokasi' }));
  await screen.findByRole('option', { name: 'Gudang utama · MAIN' })
  await selectControl(screen.getByRole('combobox', { name: 'Lokasi' }), { target: { value: id } })
  await screen.findByText('Data tidak ditemukan dalam cakupan gudang Anda.')
  expect(screen.queryByRole('button', { name: 'Berikan akses langsung' })).toBeNull()
  expect(screen.queryByText('Belum ada pemberian akses langsung untuk lokasi ini.')).toBeNull()
  expect(mocks.toast).not.toHaveBeenCalled()
})

it('scope conflict reloads the current grant and does not silently resubmit against the newer revision', async () => {
  let reads = 0
  const fetch = vi.fn(async (path: string, init: RequestInit) => {
    if (init.method === 'PUT') return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409)
    if (path.includes('/settings/scopes/')) return response([{ ...grant, revision: ++reads === 1 ? 5 : 6 }])
    return directory(path)
  })
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseScopePanel /></MemoryRouter>)
  fireEvent.click(screen.getByRole('combobox', { name: 'Lokasi' }));
  await screen.findByRole('option', { name: 'Gudang utama · MAIN' })
  await selectControl(screen.getByRole('combobox', { name: 'Lokasi' }), { target: { value: id } })
  fireEvent.click(await screen.findByRole('button', { name: 'Berikan akses langsung' }))
  fireEvent.click(screen.getByRole('button', { name: /^Berikan akses$/ }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await screen.findByText('Pemberian akses langsung: dicabut · Revisi 6')
  expect(fetch.mock.calls.filter(([, init]) => init.method === 'PUT')).toHaveLength(1)
  expect(mocks.toast).not.toHaveBeenCalled()
})
