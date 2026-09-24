import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { delegationFixture, delegationPolicyFixture, delegationViewFixture } from '@/test/warehouseSettingsFixture'
import { WarehouseSettingsPage } from './WarehouseSettingsPage'

const permissions = vi.hoisted(() => new Set<string>())
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (value: string) => permissions.has(value) }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], index = 0, total = items.length, size = 25) => response({ items, page: index, size, totalElements: total })
function show() { return render(<MemoryRouter><WarehouseSettingsPage /></MemoryRouter>) }
beforeEach(() => {
  permissions.clear(); permissions.add('inventory.approval.view'); permissions.add('inventory.approval.manage')
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('loads named history only on demand with real server pages and no broad IAM reads', async () => {
  const fetch = vi.fn(async (path: string) => {
    if (path.includes('/policy-history?')) return path.includes('page=1') ? page([], 1, 11, 10) : page([delegationPolicyFixture()], 0, 11, 10)
    return response(delegationPolicyFixture())
  }); vi.stubGlobal('fetch', fetch); show()
  await screen.findByRole('region', { name: 'Kebijakan tersimpan' })
  expect(fetch.mock.calls).toHaveLength(1)
  fireEvent.click(screen.getByRole('button', { name: 'Lihat riwayat kebijakan' }))
  const history = screen.getByRole('region', { name: 'Riwayat kebijakan' })
  await waitFor(() => expect(history.textContent).toContain('Disimpan oleh Pengatur kebijakan'))
  expect(history.textContent).toContain('Versi 7'); expect(history.textContent).toContain('Supervisor gudang')
  fireEvent.click(within(history).getByRole('button', { name: 'Berikutnya' }))
  await waitFor(() => expect(history.textContent).toContain('Belum ada versi kebijakan'))
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/v1/warehouse/'))).toBe(true)
})
it('shows scoped delegation paging and state filters to read-only users, and hides settings reads without permission', async () => {
  permissions.delete('inventory.approval.manage')
  const fetch = vi.fn(async (path: string) => path.includes('/workbench/delegations?') ? path.includes('state=ACTIVE') && !path.includes('page=1') ? page([delegationViewFixture()], 0, 26) : page([]) : response(delegationPolicyFixture()))
  vi.stubGlobal('fetch', fetch); const first = show()
  fireEvent.click(await screen.findByRole('button', { name: 'Kelola delegasi pemeriksa' }))
  const list = screen.getByRole('region', { name: 'Delegasi pemeriksa' })
  await waitFor(() => expect(list.textContent).toContain('Pemeriksa pengganti'))
  expect(screen.queryByRole('button', { name: 'Tambah delegasi' })).toBeNull(); expect(screen.queryByRole('button', { name: 'Cabut delegasi' })).toBeNull()
  fireEvent.click(within(list).getByRole('button', { name: 'Berikutnya' }))
  await screen.findByText('Tidak ada delegasi sesuai filter')
  fireEvent.change(screen.getByRole('combobox', { name: 'Status delegasi' }), { target: { value: 'REVOKED' } })
  await waitFor(() => expect(fetch.mock.calls.some(([path]) => path.includes('page=0&state=REVOKED'))).toBe(true))
  first.unmount(); permissions.clear(); fetch.mockClear(); show()
  expect(screen.queryByRole('button', { name: 'Kelola delegasi pemeriksa' })).toBeNull(); expect(fetch).not.toHaveBeenCalled()
})
it('creates a role-bound independent named delegation after review and preserves it through uncertain retry', async () => {
  let writes = 0
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { writes++; if (writes === 1) throw new TypeError('response lost'); return response({ ...delegationFixture(1), sourceRoleId: id.target }) }
    if (path.includes('/delegation-candidates?')) return page(path.includes('kind=DELEGATE') ? [{ id: id.other, name: 'Pemeriksa pengganti' }] : [{ id: id.checker, name: 'Pemeriksa gudang' }])
    if (path.includes('/workbench/delegations?')) return page([])
    return response(delegationPolicyFixture())
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Kelola delegasi pemeriksa' }))
  fireEvent.click(screen.getByRole('button', { name: 'Tambah delegasi' }))
  fireEvent.change(await screen.findByRole('combobox', { name: 'Lokasi delegasi' }), { target: { value: id.source } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Sumber kewenangan delegasi' }), { target: { value: id.target } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Pemeriksa asal' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Pemeriksa asal' }), { target: { value: id.checker } })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Penerima delegasi' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Penerima delegasi' }), { target: { value: id.other } })
  const until = new Date(Date.now() + 86400000), local = new Date(until.getTime() - until.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
  fireEvent.change(screen.getByLabelText(/Delegasi berlaku sampai/), { target: { value: local } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau delegasi' }))
  const review = await screen.findByRole('dialog', { name: 'Konfirmasi delegasi pemeriksa' })
  expect(review.textContent).toContain('Pemeriksa gudang → Pemeriksa pengganti'); expect(review.textContent).toContain('Role Supervisor gudang'); expect(writes).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan delegasi' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const mutations = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(mutations).toHaveLength(2); expect(mutations[0][1]?.body).toBe(mutations[1][1]?.body)
  expect(JSON.parse(String(mutations[0][1]?.body))).toEqual({ expectedRevision: 0, approverId: id.checker, delegateId: id.other, sourceRoleId: id.target, locationId: id.source, operation: 'ADJUSTMENT', validUntil: new Date(local).toISOString() })
  expect((mutations[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((mutations[1][1]!.headers as Headers).get('Idempotency-Key'))
  expect(fetch.mock.calls.some(([path]) => path.includes(`kind=DELEGATE&approverId=${id.checker}&sourceRoleId=${id.target}`))).toBe(true)
})
it('revokes the displayed revision, then reloads a conflict without inventing another command', async () => {
  let revision = 17
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { revision = 18; return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) }
    if (path.includes('/workbench/delegations?')) return page([delegationViewFixture(revision)])
    return response(delegationPolicyFixture())
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Kelola delegasi pemeriksa' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Cabut delegasi' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Revisi 17')
  fireEvent.click(screen.getByRole('button', { name: 'Konfirmasi cabut' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await waitFor(() => expect(screen.getByRole('region', { name: 'Delegasi pemeriksa' }).textContent).toContain('Revisi 18'))
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 17 })
})
