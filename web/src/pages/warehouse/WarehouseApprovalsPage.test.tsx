import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { approvalDetailsFixture, approvalDocumentFixture, approvalIds as id, approvalPostedFixture } from '@/test/warehouseApprovalFixture'
import type { ApprovalDetails } from '@/api/warehouse/approvalReads'
import { WarehouseApprovalsPage } from './WarehouseApprovalsPage'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value), user: { id: '' } } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: mocks.user }) }))
const root = '/api/v1/warehouse/approvals'
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], number = 0, size = 25, totalElements = items.length) => ({ items, page: number, size, totalElements })
function show(path = `/warehouse/approvals?approvalId=${id.request}`) { return render(<MemoryRouter initialEntries={[path]}><WarehouseApprovalsPage /></MemoryRouter>) }
function read(path: string, details = approvalDetailsFixture()) {
  if (path.endsWith('/details')) return response(details)
  if (path.includes('/history/page?')) return response(page([]))
  throw new Error(`Unexpected read ${path}`)
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear(); for (const permission of ['inventory.approval.view', 'inventory.approval.decide']) mocks.permissions.add(permission)
  mocks.user.id = id.checker; tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('rejects denied or mixed source and approval addresses before any read', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); mocks.permissions.clear()
  const first = show(); expect(screen.getByRole('alert')).toBeTruthy(); first.unmount()
  mocks.permissions.add('inventory.approval.view'); show(`/warehouse/approvals?approvalId=${id.request}&sourceDocumentId=${id.document}`)
  expect(screen.getByText('Alamat persetujuan tidak dikenal.')).toBeTruthy(); expect(fetch).not.toHaveBeenCalled()
})
it('pages actual document codes and applies approval filters from page zero without stock directory permissions', async () => {
  const fetch = vi.fn(async (path: string) => {
    const second = path.includes('page=1'), details = approvalDetailsFixture()
    return response(page([{ approval: details.approval, documentCode: second ? 'RCPT-OLD' : 'RCPT-NEW', operation: 'RECEIPT', requester: details.document.requester, requestedAt: details.requestedAt }], second ? 1 : 0, 1, 2))
  }); vi.stubGlobal('fetch', fetch); show('/warehouse/approvals')
  await screen.findByRole('link', { name: 'RCPT-NEW · Revisi 5' }); fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await screen.findByRole('link', { name: 'RCPT-OLD · Revisi 5' }); fireEvent.click(screen.getByText('Filter persetujuan'))
  fireEvent.change(screen.getByRole('textbox', { name: 'Cari kode dokumen persetujuan' }), { target: { value: 'RCPT' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Status persetujuan' }), { target: { value: 'PENDING' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Jenis persetujuan' }), { target: { value: 'RECEIPT' } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Serial lengkap persetujuan' }), { target: { value: 'ONU-001' } })
  fireEvent.change(screen.getByLabelText('Persetujuan diajukan mulai tanggal'), { target: { value: '2026-09-01' } })
  fireEvent.change(screen.getByLabelText('Persetujuan sampai tanggal'), { target: { value: '2026-09-25' } })
  fireEvent.click(screen.getByRole('button', { name: 'Terapkan filter persetujuan' }))
  await screen.findByRole('link', { name: 'RCPT-NEW · Revisi 5' })
  expect(Object.fromEntries(new URLSearchParams(fetch.mock.calls.at(-1)![0].split('?')[1]))).toMatchObject({ page: '0', status: 'PENDING', operation: 'RECEIPT', query: 'RCPT', serial: 'ONU-001' })
  expect(fetch.mock.calls.every(([path]) => path.startsWith(root))).toBe(true)
})
it('requests current source revision only after evaluation and confirmation, then opens persisted approval', async () => {
  mocks.user.id = id.requester; mocks.permissions.add('inventory.approval.request')
  const document = approvalDocumentFixture()
  const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
    if (path.endsWith('/evaluate')) return response({ sourceDocumentId: id.document, sourceRevision: 5, code: 'APPROVAL_REQUIRED', requiredAction: 'REQUEST_APPROVAL' })
    if (path.endsWith('/request')) return response(approvalDetailsFixture().approval, 201)
    if (path.includes('/sources/')) return response({ document, canRequest: true, requestBlock: null })
    if (path.includes('/workbench?')) return response(page([]))
    return read(path, { ...approvalDetailsFixture(), actions: { ...approvalDetailsFixture().actions, canDecide: false, decisionBlock: 'INDEPENDENT_APPROVER_REQUIRED' } })
  }); vi.stubGlobal('fetch', fetch); show(`/warehouse/approvals?sourceDocumentId=${id.document}`)
  fireEvent.click(await screen.findByRole('button', { name: 'Periksa persyaratan persetujuan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Ajukan persetujuan' }))
  expect((await screen.findByRole('dialog', { name: 'Ajukan dokumen untuk persetujuan' })).textContent).toContain('Revisi sumber 5')
  expect(fetch.mock.calls.filter(([path]) => path.endsWith('/request'))).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Kirim permintaan persetujuan' }))
  await screen.findByRole('region', { name: 'Status persetujuan' })
  const writes = fetch.mock.calls.filter(([path]) => path.endsWith('/request'))
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ sourceDocumentId: id.document, sourceRevision: 5 })
  expect(screen.queryByRole('button', { name: 'Setujui permintaan' })).toBeNull()
})
it('lets an approver without receipt or stock permission decide and displays only reloaded actual effects', async () => {
  let current = approvalDetailsFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { current = approvalPostedFixture(); return response(current.approval) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Setujui permintaan' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan keputusan / referensi pemeriksaan' }), { target: { value: 'Berita acara diperiksa' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau keputusan' }))
  expect((await screen.findByRole('dialog', { name: 'Konfirmasi keputusan persetujuan' })).textContent).toContain('Revisi permintaan 2')
  expect(screen.queryByRole('region', { name: 'Hasil persetujuan dibukukan' })).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Simpan keputusan' }))
  const effect = await screen.findByRole('region', { name: 'Hasil persetujuan dibukukan' })
  expect(effect.textContent).toContain(id.effect); expect(effect.textContent).toContain('1 catatan pergerakan tersimpan')
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ requestId: id.request, expectedRevision: 2, decision: 'APPROVE', reason: 'Berita acara diperiksa' })
  expect(fetch.mock.calls.every(([path]) => path.startsWith(root))).toBe(true)
  expect(screen.queryByRole('link', { name: 'Buka penerimaan sumber' })).toBeNull()
  expect(screen.queryByText(/Nilai persetujuan:/)).toBeNull()
})
it('keeps an ambiguous decision captured and reloads a stale response without choosing another tier or revision', async () => {
  let calls = 0, current = approvalDetailsFixture()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      calls++
      if (calls === 1) throw new TypeError('Lost connection')
      current = { ...current, approval: { ...current.approval, status: 'STALE', code: 'STALE_REVISION', revision: 3 }, actions: { ...current.actions, canDecide: false, decisionBlock: 'REQUEST_TERMINAL' } }
      return response(current.approval, 409)
    }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Setujui permintaan' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan keputusan / referensi pemeriksaan' }), { target: { value: 'Bukti diverifikasi' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau keputusan' })); fireEvent.click(await screen.findByRole('button', { name: 'Simpan keputusan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await waitFor(() => expect(screen.getByRole('region', { name: 'Status persetujuan' }).textContent).toContain('Sumber berubah'))
  expect(screen.queryByRole('textbox', { name: 'Alasan keputusan / referensi pemeriksaan' })).toBeNull()
  expect(screen.queryByRole('button', { name: 'Setujui permintaan' })).toBeNull()
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(2); expect(writes[0][1]?.body).toBe(writes[1][1]?.body)
  expect((writes[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((writes[1][1]!.headers as Headers).get('Idempotency-Key'))
})
it('does not offer self or currently ineligible decisions and requires a reason for rejection', async () => {
  mocks.user.id = id.requester
  let current = approvalDetailsFixture()
  vi.stubGlobal('fetch', vi.fn(async (path: string) => read(path, current)))
  const first = show(); await screen.findByRole('heading', { name: 'RCPT-001' })
  expect(screen.queryByRole('button', { name: 'Setujui permintaan' })).toBeNull()
  expect(screen.getByText(/Dibutuhkan pemeriksa independen/)).toBeTruthy(); first.unmount()
  mocks.user.id = id.checker; current = { ...current, actions: { ...current.actions, canDecide: false, decisionBlock: 'NOT_CURRENT_APPROVER' } }
  const second = show(); await screen.findByRole('heading', { name: 'RCPT-001' })
  expect(screen.queryByRole('button', { name: 'Setujui permintaan' })).toBeNull(); second.unmount()
  current = approvalDetailsFixture(); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Kembalikan untuk perbaikan' }))
  fireEvent.submit(screen.getByRole('form', { name: 'Keputusan persetujuan' }))
  expect(screen.getByRole('alert').textContent).toContain('Isi alasan keputusan')
})
it('uses actual source revision for rework and retains historical decision names on another page', async () => {
  mocks.user.id = id.requester; mocks.permissions.add('inventory.approval.request')
  const previous = approvalDetailsFixture()
  const current: ApprovalDetails = { ...previous, approval: { ...previous.approval, status: 'REWORK_REQUIRED', revision: 3, code: 'REWORK_REQUIRED' }, currentSourceRevision: 6,
    actions: { canDecide: false, decisionBlock: 'REQUEST_TERMINAL', canRework: true, reworkSourceRevision: 6, currentTier: null } }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response({ requestId: id.request, sourceDocumentId: id.document, sourceRevision: 7, status: 'DRAFT' })
    if (path.includes('/sources/')) return response({ document: approvalDocumentFixture(7), canRequest: true, requestBlock: null })
    if (path.includes('/workbench?')) return response(page([]))
    if (path.includes('/history/page?')) { const second = path.includes('page=1'); return response(page([{ id: id.decision, tier: second ? 1 : 2, approver: { id: id.checker, name: 'Pemeriksa independen' }, decision: second ? 'APPROVE' : 'REJECT', reason: second ? 'Tahap awal lolos' : 'Perbaiki bukti', decidedAt: '2026-09-25T01:02:00Z', revision: second ? 1 : 2, delegatedFrom: null, evidenceReference: null }], second ? 1 : 0, 1, 2)) }
    return read(path, current)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByText('Riwayat keputusan'))
  const history = screen.getByText('Riwayat keputusan').closest('details')!
  await within(history).findByText('Perbaiki bukti'); fireEvent.click(within(history).getByRole('button', { name: 'Berikutnya' }))
  await within(history).findByText('Tahap awal lolos')
  fireEvent.click(screen.getByRole('button', { name: 'Buka perbaikan dokumen' }))
  expect((await screen.findByRole('dialog', { name: 'Buka perbaikan dokumen sumber' })).textContent).toContain('Revisi sumber saat ini 6')
  fireEvent.click(screen.getByRole('button', { name: 'Buka perbaikan' }))
  await screen.findByRole('button', { name: 'Periksa persyaratan persetujuan' })
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ requestId: id.request, expectedRevision: 6 })
})
