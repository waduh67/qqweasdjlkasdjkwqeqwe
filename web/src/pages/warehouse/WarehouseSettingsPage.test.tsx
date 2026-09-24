import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { policyDetailsFixture, policyLocation, policyVersionFixture } from '@/test/warehousePolicyFixture'
import { WarehouseSettingsPage } from './WarehouseSettingsPage'

const permissions = vi.hoisted(() => new Set<string>())
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (value: string) => permissions.has(value) }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[]) => response({ items, page: 0, size: 25, totalElements: items.length })
function show() { return render(<MemoryRouter><WarehouseSettingsPage /></MemoryRouter>) }
beforeEach(() => {
  permissions.clear(); for (const permission of ['inventory.approval.view', 'inventory.approval.manage', 'inventory.location.view']) permissions.add(permission)
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('reads named saved policy with no edit action for read-only users and no request for denied policy access', async () => {
  const fetch = vi.fn(async () => response(policyDetailsFixture())); vi.stubGlobal('fetch', fetch)
  permissions.delete('inventory.approval.manage')
  const first = show()
  const saved = await screen.findByRole('region', { name: 'Kebijakan tersimpan' })
  expect(saved.textContent).toContain('Versi 7'); expect(saved.textContent).toContain('Pemeriksa gudang'); expect(saved.textContent).toContain('Gudang A')
  expect(screen.queryByRole('button', { name: 'Ubah kebijakan persetujuan' })).toBeNull()
  first.unmount(); permissions.delete('inventory.approval.view'); fetch.mockClear(); show()
  expect(screen.getByText('Izin lihat persetujuan diperlukan untuk membaca kebijakan gudang.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})
it('configures an independent named approver from an empty policy with captured revision zero and review', async () => {
  let saved = false
  const version = policyVersionFixture(1); version.rules[0].tiers[0].minimumMinor = '1'
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'PUT') { saved = true; return response(version) }
    if (path.includes('/locations?')) return page([policyLocation])
    if (path.includes('/policy/approvers?')) return page([{ id: id.checker, name: 'Pemeriksa gudang' }])
    return response(policyDetailsFixture(saved ? version : null))
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Buat kebijakan persetujuan' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Lokasi kebijakan' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Lokasi kebijakan' }), { target: { value: id.source } })
  fireEvent.click(screen.getByRole('button', { name: 'Tambahkan lokasi kebijakan' }))
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Pemeriksa aturan 1 tahap 1' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Pemeriksa aturan 1 tahap 1' }), { target: { value: id.checker } })
  fireEvent.click(screen.getByRole('button', { name: 'Tambah pemeriksa aturan 1 tahap 1' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau kebijakan' }))
  const dialog = await screen.findByRole('dialog', { name: 'Konfirmasi perubahan kebijakan' })
  expect(dialog.textContent).toContain('Tersimpan · Versi 0'); expect(dialog.textContent).toContain('Rencana perubahan'); expect(dialog.textContent).toContain('Pemeriksa gudang')
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan kebijakan' }))
  await waitFor(() => expect(screen.getByRole('region', { name: 'Kebijakan tersimpan' }).textContent).toContain('Versi 1'))
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 0, currency: 'IDR', expiryHours: 24, warehouseIds: [id.source], rules: [{ operation: 'ADJUSTMENT', tiers: [{ minimumMinor: '1', userIds: [id.checker], roleIds: [] }] }] })
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/v1/warehouse/'))).toBe(true)
})
it('preserves a reviewed exact threshold through ambiguous retry then reloads stale policy without guessing a revision', async () => {
  let writes = 0, revision = 7
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'PUT') { writes++; if (writes === 1) throw new TypeError('response lost'); revision = 8; return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) }
    if (path.includes('/locations?')) return page([policyLocation])
    if (path.includes('/policy/approvers?')) return page([{ id: id.checker, name: 'Pemeriksa gudang' }])
    return response(policyDetailsFixture(policyVersionFixture(revision)))
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Ubah kebijakan persetujuan' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Batas nilai minimum aturan 1 tahap 1' }), { target: { value: '900719925474099312345678' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau kebijakan' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('900.719.925.474.099.312.345.678')
  fireEvent.click(screen.getByRole('button', { name: 'Simpan kebijakan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await waitFor(() => expect(screen.getByRole('region', { name: 'Kebijakan tersimpan' }).textContent).toContain('Versi 8'))
  const mutations = fetch.mock.calls.filter(([, init]) => init?.method === 'PUT')
  expect(mutations).toHaveLength(2); expect(mutations[0][1]?.body).toBe(mutations[1][1]?.body)
  expect(JSON.parse(String(mutations[0][1]?.body))).toMatchObject({ expectedRevision: 7, rules: [{ tiers: [{ minimumMinor: '900719925474099312345678' }] }] })
  expect((mutations[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((mutations[1][1]!.headers as Headers).get('Idempotency-Key'))
})
it('adds a role-bound second tier but rejects a decreasing threshold before sending', async () => {
  const fetch = vi.fn(async (path: string) => path.includes('/locations?') ? page([policyLocation]) : path.includes('/policy/approvers?') ? page([{ id: id.other, name: 'Supervisor gudang' }]) : response(policyDetailsFixture()))
  vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Ubah kebijakan persetujuan' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tambah tahap aturan 1' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Batas nilai minimum aturan 1 tahap 2' }), { target: { value: '50' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Jenis pemeriksa aturan 1 tahap 2' }), { target: { value: 'ROLE' } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Pemeriksa aturan 1 tahap 2' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Pemeriksa aturan 1 tahap 2' }), { target: { value: id.other } })
  fireEvent.click(screen.getByRole('button', { name: 'Tambah pemeriksa aturan 1 tahap 2' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau kebijakan' }))
  expect(screen.getByRole('alert').textContent).toContain('meningkat')
  fireEvent.change(screen.getByRole('textbox', { name: 'Batas nilai minimum aturan 1 tahap 2' }), { target: { value: '200' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau kebijakan' }))
  const review = await screen.findByRole('dialog')
  expect(within(review).getByText(/Role Supervisor gudang/)).toBeTruthy()
  expect(fetch.mock.calls.some(([path]) => path.includes('kind=ROLE'))).toBe(true)
})
