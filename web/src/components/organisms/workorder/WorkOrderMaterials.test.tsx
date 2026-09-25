import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { custodyFixture, executionWorkOrder, fieldContextFixture, settlementFixture, usageViewFixture } from '@/test/warehouseExecutionFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { WorkOrderMaterials } from './WorkOrderMaterials'

const mocks = vi.hoisted(() => ({ permissions: new Set<string>(), userId: '' }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: (permission: string) => mocks.permissions.has(permission) }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { id: mocks.userId } }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], total = items.length, index = 0) => response({ items, page: index, size: 10, totalElements: total })
function show(workOrder = executionWorkOrder) { return render(<MemoryRouter><WorkOrderMaterials workOrder={workOrder} /></MemoryRouter>) }
beforeEach(() => {
  mocks.permissions.clear(); mocks.permissions.add('workorder.order.field'); mocks.userId = id.inspection
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
async function fillUse(amount: string) {
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang diterima 1' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang diterima 1' }), { target: { value: id.piece } })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah dipakai 1/ }), { target: { value: amount } })
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti pemakaian/ }), { target: { value: 'Foto pengukuran lapangan' } })
}
it('keeps technical completion, QA, provisioning and residual closure distinct for a read-only reviewer', async () => {
  mocks.permissions.clear(); mocks.permissions.add('workorder.order.view')
  const fetch = vi.fn(async (path: string) => path.includes('/obligations?') ? page([]) : path.endsWith('/settlement') ? response({ ...settlementFixture('17500'), technicalState: 'DONE', qaState: 'APPROVED', provisioningState: 'PENDING' }) : response({ ...fieldContextFixture(), useRevision: 1, latestUsageId: id.evidence }))
  vi.stubGlobal('fetch', fetch); show({ ...executionWorkOrder, status: 'DONE', approvalStatus: 'APPROVED' })
  await screen.findByText('Teknis selesai'); expect(screen.getByText('Disetujui QA')).toBeTruthy(); expect(screen.getByText('Menunggu')).toBeTruthy(); expect(screen.getByText('Belum ditutup')).toBeTruthy()
  expect(screen.queryByRole('button', { name: /Catat.*pemakaian/ })).toBeNull(); expect(screen.queryByRole('button', { name: 'Tutup kewajiban material' })).toBeNull()
  expect(screen.queryByRole('link', { name: 'Lihat biaya material WO' })).toBeNull()
  await waitFor(() => expect(fetch.mock.calls).toHaveLength(3))
})
it.each([0, 1])('reports first measured use at physical revision%s and replays response loss unchanged', async (physicalRevision) => {
  let writes = 0
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/obligations?')) return page([])
    if (init?.method === 'POST') { writes++; if (writes === 1) throw new TypeError('response lost'); return response({ usageId: id.evidence, workOrderId: id.source, useRevision: physicalRevision + 1 }) }
    if (path.endsWith('/settlement')) return response(settlementFixture(writes === 2 ? '17500' : '100000'))
    if (path.includes('/custody?')) return page([custodyFixture()])
    return response(writes === 2 ? { ...fieldContextFixture(), useRevision: physicalRevision + 1, latestUsageId: id.evidence } : { ...fieldContextFixture(), useRevision: physicalRevision })
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Catat pemakaian material' })); await fillUse('82,500')
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemakaian' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Kabel drop'); expect(writes).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Catat pemakaian' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Coba transaksi yang sama' }))
  await screen.findByRole('button', { name: 'Catat tambahan pemakaian' })
  const mutations = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(mutations).toHaveLength(2); expect(mutations[0][0]).toBe(`/api/work-orders/${id.source}/materials/report-use`)
  expect(mutations[0][1]?.body).toBe(mutations[1][1]?.body)
  expect(JSON.parse(String(mutations[0][1]?.body))).toMatchObject({ expectedRevision: physicalRevision, workOrderRevision: 5, planRevision: 1, lines: [{ receiptId: id.document, issueLineId: id.line, stockIdentityId: id.piece, quantityBase: '82500' }] })
  expect((mutations[0][1]!.headers as Headers).get('Idempotency-Key')).toBe((mutations[1][1]!.headers as Headers).get('Idempotency-Key'))
})
it('sends a positive correction with the real previous usage then reloads stale409 without another write', async () => {
  let stale = false
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/obligations?')) return page([])
    if (init?.method === 'POST') { stale = true; return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) }
    if (path.endsWith('/settlement')) return response(settlementFixture('17500'))
    if (path.includes('/custody?')) return page([{ ...custodyFixture(), quantityBase: '17500', sourceUsageId: id.evidence, initialUseSource: false }])
    return response({ ...fieldContextFixture(), useRevision: stale ? 2 : 1, latestUsageId: id.evidence, workOrderRevision: stale ? 6 : 5 })
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Catat tambahan pemakaian' })); await fillUse('7,500')
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan tambahan pemakaian/ }), { target: { value: 'Tambahan penarikan' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemakaian' })); fireEvent.click(await screen.findByRole('button', { name: 'Catat pemakaian' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await screen.findByText('Rencana 1 · Pemakaian revisi 2')
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(writes[0][0]).toBe(`/api/work-orders/${id.source}/materials/correct-use`)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ expectedRevision: 1, workOrderRevision: 5, previousUsageId: id.evidence, quantityBase: '7500', stockIdentityId: id.piece })
})
it('requires an explicit no-material declaration and does not load custody or submit invented allocations', async () => {
  const context = fieldContextFixture(); context.plan = { ...context.plan!, materialMode: 'NONE', reason: 'Pemeriksaan saja', lines: [] }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/obligations?')) return page([])
    if (init?.method === 'POST') return response({ usageId: id.evidence, workOrderId: id.source, useRevision: 1 })
    return path.endsWith('/settlement') ? response(settlementFixture('0')) : response(context)
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Nyatakan tanpa pemakaian material' }))
  fireEvent.change(screen.getByRole('textbox', { name: /Referensi bukti pemakaian/ }), { target: { value: 'Foto inspeksi' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan tidak memakai material/ }), { target: { value: 'Tidak mengganti barang' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau pemakaian' })); fireEvent.click(await screen.findByRole('button', { name: 'Catat pemakaian' }))
  await waitFor(() => expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(true))
  const write = fetch.mock.calls.find(([, init]) => init?.method === 'POST')!
  expect(JSON.parse(String(write[1]?.body))).toMatchObject({ expectedRevision: 0, materialMode: 'NONE', lines: [], reason: 'Tidak mengganti barang' })
  expect(fetch.mock.calls.some(([path]) => path.includes('/custody'))).toBe(false)
})
it('hides use for a reassigned or cancelled job while retaining residual guidance and real paged history', async () => {
  mocks.userId = id.supplier; mocks.permissions.add('inventory.return.view')
  const fetch = vi.fn(async (path: string) => path.includes('/obligations?') ? page([]) : path.includes('/usage?') ? page(path.includes('page=1') ? [] : [usageViewFixture()], 11, path.includes('page=1') ? 1 : 0) : path.endsWith('/settlement') ? response({ ...settlementFixture('17500'), technicalState: 'CANCELLED' }) : response({ ...fieldContextFixture(), useRevision: 1, latestUsageId: id.evidence }))
  vi.stubGlobal('fetch', fetch); show({ ...executionWorkOrder, status: 'CANCELLED' })
  await screen.findByText('Dibatalkan'); expect(screen.queryByRole('button', { name: /Catat.*pemakaian/ })).toBeNull()
  expect(screen.getByRole('link', { name: 'Lihat retur dan pemeriksaan gudang' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Lihat riwayat pemakaian' })); await screen.findByText('Pemakaian 1 · Rencana 1')
  expect(screen.getByText(/Budi Teknisi/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' })); await screen.findByText('Belum ada catatan pemakaian dalam cakupan Anda.')
  expect(fetch.mock.calls.some(([path]) => path.includes('page=1&size=10'))).toBe(true)
})
it('closes only clear material obligations using actual settlement and WO revisions', async () => {
  mocks.permissions.add('workorder.order.close')
  let closed = false
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (path.includes('/obligations?')) return page([])
    if (init?.method === 'POST') { closed = true; return response({ ...settlementFixture('0').obligations, revision: 8, materialState: 'CLOSED' }) }
    return path.endsWith('/settlement') ? response({ ...settlementFixture('0'), materialState: closed ? 'CLOSED' : 'OPEN' }) : response(fieldContextFixture())
  }); vi.stubGlobal('fetch', fetch); show()
  fireEvent.click(await screen.findByRole('button', { name: 'Tutup kewajiban material' }))
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan penutupan material/ }), { target: { value: 'Seluruh barang selesai' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penutupan material' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Penyelesaian revisi 7')
  fireEvent.click(screen.getByRole('button', { name: 'Tutup material' })); await screen.findByText('Material ditutup')
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 7, workOrderRevision: 5, reason: 'Seluruh barang selesai' })
})
