import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { fieldContextFixture } from '@/test/warehouseExecutionFixture'
import { handoverSourceFixture, handoverTargetFixture } from '@/test/materialHandoverFixture'
import { materialIds as id } from '@/test/warehouseMaterialFixture'
import { WorkOrderMaterialHandover } from './WorkOrderMaterialHandover'

const identity = vi.hoisted(() => ({ id: '' }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: identity, readOnly: false }) }))
const response = (value: unknown) => new Response(JSON.stringify(value))
const page = (items: unknown[]) => response({ items, page: 0, size: 25, totalElements: items.length })
beforeEach(() => { identity.id = id.supplier; HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }; HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') } })
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
async function fill() {
  await waitFor(() => expect(screen.getByRole('combobox', { name: 'Barang dan pemegang saat ini' })).not.toHaveProperty('disabled', true))
  fireEvent.change(screen.getByRole('combobox', { name: 'Barang dan pemegang saat ini' }), { target: { value: id.piece } })
  fireEvent.change(screen.getByRole('textbox', { name: /Jumlah diserahterimakan/ }), { target: { value: '7,500' } })
  fireEvent.change(screen.getByRole('combobox', { name: 'Teknisi dan lokasi penerima' }), { target: { value: id.allocation } })
  fireEvent.change(screen.getByRole('textbox', { name: /Alasan serah-terima/ }), { target: { value: 'Pergantian teknisi' } })
  fireEvent.change(screen.getByRole('textbox', { name: /Bukti persetujuan serah-terima/ }), { target: { value: 'Instruksi 12' } })
}
it('authorizes a measured transfer between named distinct people using the real source and work order revision', async () => {
  const context = { ...fieldContextFixture(), workOrderRevision: 9 }, done = vi.fn()
  const fetch = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return response({ authorizationId: id.plan, workOrderId: id.source })
    return path.includes('/sources?') ? page([handoverSourceFixture()]) : path.includes('/targets?') ? page([handoverTargetFixture()]) : response(context)
  }); vi.stubGlobal('fetch', fetch); render(<WorkOrderMaterialHandover context={context} onDone={done} onClose={vi.fn()} />); await fill()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau persetujuan' }))
  const dialog = await screen.findByRole('dialog'); expect(dialog.textContent).toContain('Budi pengirim → Ani penerima'); expect(dialog.textContent).toContain('Stok belum berpindah')
  fireEvent.click(screen.getByRole('button', { name: 'Setujui serah-terima' })); await waitFor(() => expect(done).toHaveBeenCalledTimes(1))
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ workOrderRevision: 9, stockIdentityId: id.piece, receiptId: id.document, usageId: id.evidence, quantityBase: '7500', targetLocationId: id.allocation, expectedSenderId: id.inspection, expectedReceiverId: id.demandLine })
})
it('does not let a source holder act as the independent dispatcher even with manipulated choices', async () => {
  identity.id = id.inspection
  const fetch = vi.fn(async (path: string) => path.includes('/sources?') ? page([handoverSourceFixture()]) : page([handoverTargetFixture()]))
  vi.stubGlobal('fetch', fetch); render(<WorkOrderMaterialHandover context={fieldContextFixture()} onDone={vi.fn()} onClose={vi.fn()} />); await fill()
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau persetujuan' }))
  await screen.findByText('Pilih pemegang dan penerima berbeda. Dispatcher harus independen dari keduanya.')
  expect(screen.queryByRole('dialog')).toBeNull(); expect(fetch.mock.calls.every(([path]) => !path.endsWith('/authorize'))).toBe(true)
})
