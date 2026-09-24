import { afterEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { WarehouseApprovalEvidence } from './WarehouseApprovalEvidence'

const file = { id: id.decision, kind: 'SIGNATURE', recordedAt: '2026-09-25T01:00:00Z', signerLabel: 'Pelanggan' }
const page = (items: unknown[], page = 0) => new Response(JSON.stringify({ items, page, size: 1, totalElements: 2 }), { headers: { 'Content-Type': 'application/json' } })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); tokenStore.clear() })
it('reads sealed approval evidence on demand with authenticated preview and server paging', async () => {
  const create = vi.fn(() => 'blob:approval-evidence'), revoke = vi.fn()
  vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = revoke })
  const fetch = vi.fn(async (path: string) => path.endsWith(`/attachments/${id.decision}`) ? new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } }) : page([file], path.includes('page=1') ? 1 : 0))
  vi.stubGlobal('fetch', fetch)
  render(<WarehouseApprovalEvidence id={id.request} />)
  expect(fetch).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Tampilkan bukti pengajuan' }))
  await screen.findByText('Tanda tangan · Pelanggan', { exact: false })
  fireEvent.click(screen.getByRole('button', { name: 'Lihat gambar bukti' }))
  expect(await screen.findByRole('img', { name: 'Tanda tangan Pelanggan' })).toHaveProperty('src', 'blob:approval-evidence')
  expect(fetch.mock.calls[1][0]).toBe(`/api/v1/warehouse/approvals/${id.request}/attachments/${id.decision}`)
  fireEvent.click(screen.getByRole('button', { name: 'Tutup gambar bukti' }))
  await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:approval-evidence'))
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await waitFor(() => expect(fetch.mock.calls.at(-1)![0]).toContain('page=1&size=25'))
  expect(fetch.mock.calls.every(([path]) => path.startsWith(`/api/v1/warehouse/approvals/${id.request}/`))).toBe(true)
})
it('does not display corrupt or no longer authorized evidence as a successful preview', async () => {
  const create = vi.fn()
  vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = vi.fn() })
  let denied = false
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path.endsWith(`/attachments/${id.decision}`)
    ? denied ? new Response(JSON.stringify({ code: 'NOT_FOUND', message: 'Bukti tidak tersedia' }), { status: 404, headers: { 'Content-Type': 'application/json' } })
      : new Response('<svg></svg>', { headers: { 'Content-Type': 'image/svg+xml' } }) : page([file])))
  render(<WarehouseApprovalEvidence id={id.request} />)
  fireEvent.click(screen.getByRole('button', { name: 'Tampilkan bukti pengajuan' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Lihat gambar bukti' }))
  await screen.findByRole('alert'); expect(create).not.toHaveBeenCalled(); expect(screen.queryByRole('img')).toBeNull()
  denied = true; fireEvent.click(screen.getByRole('button', { name: 'Lihat gambar bukti' }))
  await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Bukti tidak tersedia'))
  expect(create).not.toHaveBeenCalled()
})
