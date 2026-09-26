import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { tokenStore } from '@/api/client'
import { WarehouseDataError } from '@/api/warehouse/codec'
import { getWorkOrderSignatureFile, workOrderSignature, type WorkOrderSignature } from '@/api/warehouse/evidence'
import { reacquisitionEntry, type ReacquisitionEntry } from '@/api/warehouse/returns'
import { returnDetailsFixture, returnFixture, returnIds as id } from '@/test/warehouseReturnFixture'
import { WarehouseReturnsPage } from './WarehouseReturnsPage'
import { buildReacquisition } from './reacquisitionDraft'

const mocks = vi.hoisted(() => { const permissions = new Set<string>(); return { permissions, can: (value: string) => permissions.has(value) } })
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: mocks.can }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const page = (items: unknown[]) => ({ items, page: 0, size: 25, totalElements: items.length })
const signature: WorkOrderSignature = { revisionId: id.evidence, workOrderId: id.source, signerName: 'Pelanggan asal', contentType: 'image/png', sizeBytes: 100,
  signedBy: id.plan, signedByName: 'Teknisi pemasang', signedAt: '2026-09-22T12:00:00Z', createdAt: '2026-09-22T12:00:01Z' }
const entry: ReacquisitionEntry = { documentId: id.rma, returnId: id.returnCase, revision: 0, code: 'RET-TITLE-001', sourceReturnRevision: 1,
  reason: 'Perangkat diserahkan pelanggan', titleTransferReference: 'BA-ALIH-001', evidenceId: id.evidence, recordedAt: '2026-09-25T00:00:00Z', appliedReturnRevision: null }
function server({ conflict = false, missingSignature = false } = {}) {
  const details = returnDetailsFixture(returnFixture(true)); details.references.workOrderId = id.issue
  const entries: ReacquisitionEntry[] = []
  let activeSignature: WorkOrderSignature | null = missingSignature ? null : signature
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') {
      entries.push(entry)
      return conflict ? response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409) : response({ documentId: entry.documentId, returnId: entry.returnId, revision: 0 }, 201)
    }
    if (path.endsWith('/signature')) return activeSignature ? response(activeSignature) : new Response(null, { status: 204 })
    if (path.includes('/reacquisition-requests?')) return response(page(entries))
    if (path.endsWith('/details')) return response(details)
    if (path.includes('/history/page?')) return response(page([details.returnCase]))
    throw new Error(`Unexpected read ${path}`)
  })
  vi.stubGlobal('fetch', fetch)
  return { fetch, entries, sign: () => { activeSignature = signature }, apply: () => {
    details.returnCase = { ...details.returnCase, revision: 2, legalOwner: 'ISP' }
    entries[0] = { ...entry, appliedReturnRevision: 2 }
  } }
}
function show() { return render(<MemoryRouter initialEntries={[`/warehouse/returns?returnId=${id.returnCase}`]}><WarehouseReturnsPage /></MemoryRouter>) }

it('retains an expired ownership proposal without presenting it as an applied transfer', async () => {
  const state = server()
  state.entries.push({ ...entry, state: 'EXPIRED', draftExpiry: { deadline: '2026-09-26T12:00:00Z', recordedAt: null, reason: 'IDLE_DEADLINE' } })
  show(); await screen.findByText(/Draf kedaluwarsa sejak/)
  expect(screen.getByRole('link', { name: 'Buka persetujuan RET-TITLE-001' })).toBeTruthy()
  expect(screen.queryByText(/Alih kepemilikan sudah dicatat pada retur revisi/)).toBeNull()
  expect(screen.getByRole('button', { name: 'Siapkan alih kepemilikan' })).toBeTruthy()
})
async function prepare() {
  fireEvent.click(await screen.findByRole('button', { name: 'Siapkan alih kepemilikan' }))
  const form = await screen.findByRole('form', { name: 'Permintaan alih kepemilikan' })
  expect(form.textContent).toContain('Pelanggan asal'); expect(form.textContent).toContain('Teknisi pemasang')
  fireEvent.change(screen.getByRole('textbox', { name: 'Alasan alih kepemilikan' }), { target: { value: entry.reason } })
  fireEvent.change(screen.getByRole('textbox', { name: 'Referensi persetujuan alih kepemilikan' }), { target: { value: entry.titleTransferReference } })
  expect(screen.getByRole('button', { name: 'Tinjau alih kepemilikan' })).toHaveProperty('disabled', true)
  fireEvent.click(screen.getByRole('checkbox', { name: /Saya telah memeriksa bukti/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau alih kepemilikan' }))
}
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
  mocks.permissions.clear()
  for (const value of ['inventory.return.view', 'inventory.return.manage', 'inventory.approval.view', 'inventory.approval.request', 'workorder.evidence.view']) mocks.permissions.add(value)
  tokenStore.clear()
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })

it('binds the persisted signature of the original WO and separates applied title revision from the request', () => {
  const details = returnDetailsFixture(returnFixture(true)); details.references.workOrderId = id.issue
  expect(workOrderSignature(signature)).toEqual(signature)
  expect(reacquisitionEntry(entry)).toEqual(entry)
  expect(() => reacquisitionEntry({ ...entry, appliedReturnRevision: 5 })).toThrow(WarehouseDataError)
  expect(buildReacquisition(details, signature, entry.reason, entry.titleTransferReference, true)).toEqual({ expectedRevision: 1, reason: entry.reason, titleTransferReference: entry.titleTransferReference, evidenceId: signature.revisionId })
  expect(() => buildReacquisition(details, { ...signature, workOrderId: id.issue }, 'reason', 'ref', true)).toThrow('WO pemasangan asal')
  expect(() => buildReacquisition(details, signature, 'reason', 'ref', false)).toThrow('persetujuan alih kepemilikan')
  expect(() => buildReacquisition({ ...details, references: { ...details.references, rmaHandoverId: id.rma } }, signature, 'reason', 'ref', true)).toThrow('karantina')
})
it('stores a reviewed request and resumes its persisted approval link without changing customer title', async () => {
  const state = server(); show(); await prepare()
  const dialog = await screen.findByRole('dialog', { name: 'Konfirmasi permintaan alih kepemilikan' })
  expect(dialog.textContent).toContain('Kepemilikan dan stok tersedia belum berubah')
  expect(state.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
  fireEvent.click(screen.getByRole('button', { name: 'Simpan permintaan alih kepemilikan' }))
  const link = await screen.findByRole('link', { name: 'Buka persetujuan RET-TITLE-001' })
  expect(link.getAttribute('href')).toBe(`/warehouse/approvals?sourceDocumentId=${id.rma}`)
  expect(screen.getByText(/Perangkat tetap milik pelanggan/)).toBeTruthy()
  const writes = state.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1)
  expect(writes[0][0]).toBe(`/api/v1/warehouse/returns/${id.returnCase}/reacquisition`)
  expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ expectedRevision: 1, reason: entry.reason, titleTransferReference: entry.titleTransferReference, evidenceId: signature.revisionId })
  expect(state.fetch.mock.calls.filter(([path]) => path.endsWith('/signature')).map(([path]) => path)).toEqual([`/api/work-orders/${id.source}/signature`])
  state.apply(); fireEvent.click(screen.getByRole('button', { name: 'Muat ulang retur' }))
  await screen.findByText(/Alih kepemilikan sudah dicatat pada retur revisi 2/)
  expect(screen.queryByRole('button', { name: 'Siapkan alih kepemilikan' })).toBeNull()
  expect(screen.getByRole('button', { name: 'Periksa retur' })).toBeTruthy()
  expect(state.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('reloads the saved request after conflict without blind resubmission', async () => {
  const state = server({ conflict: true }); show(); await prepare()
  fireEvent.click(screen.getByRole('button', { name: 'Simpan permintaan alih kepemilikan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await screen.findByRole('link', { name: 'Buka persetujuan RET-TITLE-001' })
  expect(screen.queryByRole('form', { name: 'Permintaan alih kepemilikan' })).toBeNull()
  expect(state.fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
it('handles absent committed signature and missing permission without accepting a manual evidence ID', async () => {
  const state = server({ missingSignature: true }); const mounted = show()
  fireEvent.click(await screen.findByRole('button', { name: 'Siapkan alih kepemilikan' }))
  await screen.findByText(/belum memiliki tanda tangan aktif/)
  expect(screen.queryByRole('form', { name: 'Permintaan alih kepemilikan' })).toBeNull()
  state.sign(); fireEvent.click(screen.getByRole('button', { name: 'Muat ulang bukti' }))
  const form = await screen.findByRole('form', { name: 'Permintaan alih kepemilikan' })
  expect(within(form).getAllByRole('textbox')).toHaveLength(2)
  mounted.unmount(); mocks.permissions.delete('workorder.evidence.view')
  state.fetch.mockClear(); show()
  expect(await screen.findByRole('button', { name: 'Siapkan alih kepemilikan' })).toHaveProperty('disabled', true)
  expect(state.fetch.mock.calls.some(([path]) => path.endsWith('/signature'))).toBe(false)
})
it('refuses to download a different current signature under the old displayed signer', async () => {
  const fetch = vi.fn(async () => response({ ...signature, revisionId: id.rma }))
  vi.stubGlobal('fetch', fetch)
  await expect(getWorkOrderSignatureFile(signature)).rejects.toThrow('Tanda tangan telah berubah')
  expect(fetch).toHaveBeenCalledTimes(1)
})
