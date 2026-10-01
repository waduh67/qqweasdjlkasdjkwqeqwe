import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { myResidual } from '@/test/myMaterialsFixture'
import { WarehousePendingMaterialReturns } from './WarehousePendingMaterialReturns'

vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ readOnly: false }) }))
beforeEach(() => {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
})
afterEach(() => { vi.unstubAllGlobals(); tokenStore.clear() })
it('loads the scoped inbox and acknowledges its actual revision before inspection intake', async () => {
  let acknowledged = false
  const fetch = vi.fn(async (_path: string, init?: RequestInit) => {
    if (init?.method === 'POST') { acknowledged = true; return new Response(JSON.stringify({ documentId: myResidual().id, state: 'RECEIVED_IN_INSPECTION' })) }
    return new Response(JSON.stringify({ items: acknowledged ? [] : [myResidual()], page: 0, size: 25, totalElements: acknowledged ? 0 : 1 }))
  }); vi.stubGlobal('fetch', fetch); render(<WarehousePendingMaterialReturns />)
  fireEvent.click(await screen.findByRole('button', { name: myResidual().code }))
  fireEvent.click(await screen.findByRole('button', { name: 'Terima material' }))
  fireEvent.change(screen.getByRole('textbox', { name: /Bukti penerimaan/ }), { target: { value: 'Surat petugas gudang' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' }))
  expect((await screen.findByRole('dialog')).textContent).toContain('Lanjutkan pencatatan retur')
  fireEvent.click(screen.getByRole('button', { name: 'Terima material' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  const writes = fetch.mock.calls.filter(([, init]) => init?.method === 'POST'); expect(writes).toHaveLength(1)
  expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ documentId: myResidual().id, expectedRevision: 1, evidenceReference: 'Surat petugas gudang' })
})

it('reloads a stale reception without reporting success or leaving the old document open', async () => {
  const received = vi.fn()
  let reads = 0
  const fetch = vi.fn(async (_path: string, init?: RequestInit) => {
    if (init?.method === 'POST') return new Response(JSON.stringify({ code: 'STALE_REVISION', message: 'STALE_REVISION' }), { status: 409 })
    reads++
    return new Response(JSON.stringify({ items: [myResidual()], page: 0, size: 10, totalElements: 1 }))
  })
  vi.stubGlobal('fetch', fetch)
  render(<WarehousePendingMaterialReturns onReceived={received} />)
  fireEvent.click(await screen.findByRole('button', { name: myResidual().code }))
  fireEvent.click(await screen.findByRole('button', { name: 'Terima material' }))
  fireEvent.change(screen.getByRole('textbox', { name: 'Bukti penerimaan' }), { target: { value: 'BA-STALE' } })
  fireEvent.click(screen.getByRole('button', { name: 'Tinjau penerimaan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Terima material' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Muat ulang dokumen' }))
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  await waitFor(() => expect(reads).toBe(2))
  expect(received).not.toHaveBeenCalled()
  expect(fetch.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
})
