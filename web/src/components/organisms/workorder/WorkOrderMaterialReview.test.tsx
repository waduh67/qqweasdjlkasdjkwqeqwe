import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { materialIds as id, materialPlanFixture, materialSku } from '@/test/warehouseMaterialFixture'
import { fieldContextFixture, settlementFixture, usageViewFixture } from '@/test/warehouseExecutionFixture'
import { WorkOrderMaterialObligations, WorkOrderMaterialReview } from './WorkOrderMaterialReview'
import { WorkOrderMaterialRework } from './WorkOrderMaterialRework'

const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
const page = (items: unknown[], index = 0, total = items.length) => response({ items, page: index, size: 10, totalElements: total })
const basis = { expectedRevision: 1, workOrderRevision: 8, previousPlanId: id.plan, previousUsageId: id.evidence, expectedUsageRevision: 1, previousEvidenceRevision: 'old-proof', evidenceRevision: 'new-proof' }
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('loads the real frozen QA usage only on demand, then discards it when authority changes', async () => {
  let denied = false
  const fetch = vi.fn(async () => denied ? response({ code: 'NOT_FOUND', message: 'NOT_FOUND' }, 404) : response({ review: { id: id.document, workOrderRevision: 11, usage: usageViewFixture() } }))
  vi.stubGlobal('fetch', fetch); render(<WorkOrderMaterialReview id={id.source} />)
  expect(fetch).not.toHaveBeenCalled(); fireEvent.click(screen.getByRole('button', { name: 'Lihat material persetujuan QA' }))
  await screen.findByText('WO revisi 11 · Rencana 1 · Pemakaian 1'); expect(screen.getByText('82,500 m')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Tutup material persetujuan QA' })); denied = true
  fireEvent.click(screen.getByRole('button', { name: 'Lihat material persetujuan QA' })); await screen.findByText('Data tidak ditemukan dalam cakupan gudang Anda.')
  expect(screen.queryByText('82,500 m')).toBeNull()
})
it('pages named obligations without aggregating unlike units', async () => {
  const row = { id: id.line, issueCode: 'ISS-MATERIAL', sku: materialSku, serial: null, lotCode: 'REEL-1', obligation: settlementFixture('17500').obligations.lines[0] }
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path.includes('page=1') ? page([], 1, 11) : page([row], 0, 11)))
  render(<WorkOrderMaterialObligations id={id.source} />)
  await screen.findByText('Kabel drop · REEL-1'); expect(screen.getByText('17,500 m')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' })); await screen.findByText('Tidak ada baris kewajiban dalam cakupan lokasi Anda.')
})
it('reviews a positive rework delta using actual prior evidence and rejects stale review with reload', async () => {
  const onDone = vi.fn()
  const sku = { ...materialSku, state: 'ACTIVE', category: null, model: null, inspectionRequired: false, allowedOwnershipModes: ['LOAN'], minimumQuantityBase: '0' }
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response({ code: 'STALE_REVISION', message: 'STALE_REVISION' }, 409)
    if (path.includes('/skus?')) return page([sku])
    return response(basis)
  }); vi.stubGlobal('fetch', fetch)
  render(<WorkOrderMaterialRework context={{ ...fieldContextFixture(), useRevision: 1, latestUsageId: id.evidence }} onDone={onDone} onClose={vi.fn()} />)
  await screen.findByRole('combobox', { name: 'Barang tambahan 1' })
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang tambahan 1' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang tambahan 1' }), { target: { value: id.sku } })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah tambahan 1/ }), { target: { value: '10,001' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan kebutuhan tambahan/ }), { target: { value: 'Tambahan setelah inspeksi' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau tambahan rencana' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('10,001 m')
  fireEvent.click(screen.getByRole('button', { name: 'Simpan kebutuhan tambahan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' })); expect(onDone).toHaveBeenCalledTimes(1)
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST')
  expect(writes).toHaveLength(1); expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ ...basis, reason: 'Tambahan setelah inspeksi', deltas: [{ skuId: id.sku, quantityBase: '10001', baseUnit: 'MM', continuousCut: false }] })
})
it('requires a fresh basis when another plan was saved before opening rework', async () => {
  const fetch = vi.fn(async () => response({ ...basis, previousPlanId: id.supplier, expectedRevision: 2 })); vi.stubGlobal('fetch', fetch)
  render(<WorkOrderMaterialRework context={{ ...fieldContextFixture(), plan: materialPlanFixture, useRevision: 1 }} onDone={vi.fn()} onClose={vi.fn()} />)
  await screen.findByText('Rencana atau pemakaian sudah berubah. Muat ulang sebelum menyusun tambahan.')
  expect(screen.queryByRole('button', { name: 'Tinjau tambahan rencana' })).toBeNull(); expect(fetch.mock.calls).toHaveLength(1)
})
