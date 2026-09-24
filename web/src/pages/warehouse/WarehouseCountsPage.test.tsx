import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { countDetailsFixture, countFactFixture, countFixture, countIds as id, countLocation, countPositionFixture } from '@/test/warehouseCountFixture'
import type { CountFact, WarehouseCount } from '@/api/warehouse/counts'
import { WarehouseCountsPage } from './WarehouseCountsPage'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value), user: { id: '' } } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: mocks.user }) }))
const root = '/api/v1/warehouse/counts'
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, size = 25, totalElements = items.length) => ({ items, page: number, size, totalElements })
function show(path = `/warehouse/counts?countId=${id.count}`) { return render(<MemoryRouter initialEntries={[path]}><WarehouseCountsPage /></MemoryRouter>) }
function read(path: string, count = countFixture(), facts: CountFact[] = []) {
  if (path.endsWith('/details')) return response(countDetailsFixture(count))
  if (path.includes('/history/page?')) return response(page(facts.map(fact => ({ fact, recordedAt: '2026-09-25T01:02:00Z' })), 0, path.includes('size=100') ? 100 : 25))
  throw new Error(`Unexpected read ${path}`)
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear(); for (const permission of ['inventory.count.view', 'inventory.count.manage']) mocks.permissions.add(permission)
  mocks.user.id = id.requester; tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects inaccessible and duplicate deep links before requesting data', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); mocks.permissions.clear()
  const first = show(); expect(screen.getByRole('alert')).toBeTruthy(); first.unmount()
  mocks.permissions.add('inventory.count.view'); show(`/warehouse/counts?countId=${id.count}&countId=${id.count}`)
  expect(screen.getByText('Alamat stock opname tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})
it('creates reviewed assignments using quantity-free positions and current named counters', async () => {
  mocks.permissions.add('inventory.location.view')
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response(countFixture(), 201)
    if (path.includes('/workbench?')) return response(page([]))
    if (path.includes('/locations?')) return response(page([countLocation]))
    if (path.includes('/positions?')) return response(page([countPositionFixture()]))
    if (path.includes('/counters?')) return response(page([{ id: id.counter, name: 'Penghitung A' }]))
    return read(path)
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/counts')
  fireEvent.click(screen.getByRole('button', { name: 'Buat stock opname' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Lokasi stock opname' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi stock opname' }), { target: { value: id.location } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang dihitung 1' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang dihitung 1' }), { target: { value: id.balance } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Penghitung 1' }), { target: { value: id.counter } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan stock opname' }), { target: { value: 'Pemeriksaan akhir bulan' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau stock opname' }))
  expect((await screen.findByRole('dialog', { name: 'Simpan draft stock opname' })).textContent).toContain('Penghitung A')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan stock opname' })); await screen.findByRole('heading', { name: 'CNT-001' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ locationId: id.location, partialLocation: true, reason: 'Pemeriksaan akhir bulan', entries: [{ balanceId: id.balance, counterId: id.counter }] })
  expect(fetch.mock.calls.some(([path]) => path.includes('/stock') || path.includes('/lots') || path.startsWith('/api/users'))).toBe(false)
})
it('records exact blind measured metres only for the assigned counter and cannot overwrite the fact', async () => {
  mocks.user.id = id.counter; let current = countFixture('COUNTING', 1), facts: CountFact[] = []
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = countFixture('COUNTING', 2); facts = [countFactFixture()]; return response(current) }
    return read(path, current, facts)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Catat hasil Kabel drop · DROP · REEL-A' }))
  expect(screen.queryByRole('button', { name: 'Ajukan hasil hitung' })).toBeNull()
  expect(screen.getByRole('textbox', { name: 'Hasil hitung fisik (m)' })).toHaveProperty('value', '')
  fireEvent.change(screen.getByRole('textbox', { name: 'Hasil hitung fisik (m)' }), { target: { value: '82,500' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Keterangan penghitungan' }), { target: { value: 'Ukur fisik reel' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi lembar hitung' }), { target: { value: 'LEMBAR-001' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau hasil hitung' })); fireEvent.click(await screen.findByRole('button', { name: 'Simpan hasil fisik' }))
  await screen.findByRole('heading', { name: 'CNT-001' })
  expect(screen.queryByRole('button', { name: 'Catat hasil Kabel drop · DROP · REEL-A' })).toBeNull()
  expect(screen.getAllByText('82,500 m').length).toBeGreaterThan(0)
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 1, balanceId: id.balance, quantityBase: '82500', reason: 'Ukur fisik reel', documentReference: 'LEMBAR-001' })
  expect(fetch.mock.calls.every(([path]) => path.startsWith(root) && !path.includes('/review'))).toBe(true)
})
it('restricts draft start and observations to their recorded actors', async () => {
  mocks.user.id = id.counter
  let current = countFixture(); vi.stubGlobal('fetch', vi.fn(async (path: string) => read(path, current)))
  const first = show(); await screen.findByRole('heading', { name: 'CNT-001' })
  expect(screen.queryByRole('button', { name: 'Mulai penghitungan' })).toBeNull(); first.unmount()
  mocks.user.id = id.requester; current = countFixture('COUNTING', 1)
  show(); await screen.findByRole('heading', { name: 'CNT-001' })
  expect(screen.getByRole('button', { name: 'Ajukan hasil hitung' })).toHaveProperty('disabled', true)
  expect(screen.queryByRole('button', { name: 'Catat hasil Kabel drop · DROP · REEL-A' })).toBeNull()
})
it('reloads durable COUNT_STALE and explicitly starts a new round with the returned revision', async () => {
  let current = countFixture('COUNTING', 2)
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      if (path.endsWith('/submit')) { current = countFixture('RECOUNT_REQUIRED', 3); return response({ code: 'COUNT_STALE', message: 'COUNT_STALE' }, 409) }
      current = { ...countFixture('COUNTING', 4), roundRevision: 4 }; return response(current)
    }
    return read(path, current, [countFactFixture()])
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Ajukan hasil hitung' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Konfirmasi stock opname' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Mulai hitung ulang' }))
  expect((await screen.findByRole('dialog', { name: 'Mulai hitung ulang' })).textContent).toContain('Revisi 3')
  fireEvent.click(screen.getByRole('button', { name: 'Konfirmasi stock opname' }))
  expect(await screen.findByRole('button', { name: 'Ajukan hasil hitung' })).toHaveProperty('disabled', true)
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes.map(([path, init]) => [path, JSON.parse(String(init?.body))])).toEqual([[`${root}/${id.count}/submit`, { expectedRevision: 2 }], [`${root}/${id.count}/recount`, { expectedRevision: 3 }]])
})
it('opens frozen comparison only after submission and only for a reviewer permission', async () => {
  mocks.permissions.add('inventory.approval.view'); const count = countFixture('SUBMITTED', 3)
  const fetch = vi.fn(async (path: string) => {
    if (path.endsWith('/review/details')) return response({ references: countDetailsFixture(count).references, review: { count, observations: [{ balanceId: id.balance, counterId: id.counter, bookQuantityBase: '100000', quantityBase: '82500', baseUnit: 'MM', observedDimensionRevision: 1 }] } })
    return read(path, count, [countFactFixture()])
  }); vi.stubGlobal('fetch', fetch); show(); await screen.findByRole('heading', { name: 'CNT-001' })
  expect(fetch.mock.calls.some(([path]) => path.includes('/review/'))).toBe(false)
  expect(screen.getByRole('link', { name: 'Buka persetujuan stock opname' }).getAttribute('href')).toContain(id.count)
  fireEvent.click(screen.getByRole('button', { name: 'Lihat perbandingan setelah pengajuan' }))
  await screen.findByText('100,000 m'); expect(screen.getByText('-17,500 m')).toBeTruthy()
})
it('starts an empty draft without inventing a current revision and pages immutable history', async () => {
  let current: WarehouseCount = countFixture(), historyPage = 0
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = countFixture('COUNTING', 1); return response(current) }
    if (path.includes('/history/page?') && !path.includes('size=100')) {
      historyPage = path.includes('page=1') ? 1 : 0
      return response(page([{ fact: { ...countFactFixture(), documentReference: historyPage ? 'LEMBAR-LAMA' : 'LEMBAR-BARU' }, recordedAt: '2026-09-25T01:02:00Z' }], historyPage, 1, 2))
    }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Mulai penghitungan' })); fireEvent.click(await screen.findByRole('button', { name: 'Konfirmasi stock opname' }))
  await screen.findByRole('button', { name: 'Ajukan hasil hitung' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 0 })
  fireEvent.click(screen.getByText('Riwayat hasil hitung')); const history = screen.getByText('Riwayat hasil hitung').closest('details')!
  await within(history).findByText('Bukti: LEMBAR-BARU'); fireEvent.click(within(history).getByRole('button', { name: 'Berikutnya' }))
  await within(history).findByText('Bukti: LEMBAR-LAMA'); expect(historyPage).toBe(1)
})
