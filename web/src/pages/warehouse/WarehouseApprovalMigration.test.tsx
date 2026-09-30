import { afterEach, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { approvalDocument } from '@/api/warehouse/approvalReads'
import { tokenStore } from '@/api/client'
import { approvalIds as id } from '@/test/warehouseApprovalFixture'
import { migrationCaseFixture, migrationDocumentFixture } from '@/test/warehouseMigrationFixture'
import { WarehouseApprovalDocument } from './WarehouseApprovalDocument'
import { WarehouseApprovalMigration } from './WarehouseApprovalMigration'

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => false }) }))
const response = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); tokenStore.clear() })
it('explains unknown valuation and explicit empty stock without an empty ordinary line table', () => {
  render(<MemoryRouter><WarehouseApprovalDocument document={approvalDocument(migrationDocumentFixture(true))} /></MemoryRouter>)
  expect(screen.getByText(/Harga historis belum diketahui/)).toBeTruthy()
  expect(screen.getByText(/Usulan ini menyatakan tidak ada stok fisik/)).toBeTruthy()
  expect(screen.queryByRole('table')).toBeNull()
  expect(screen.queryByText(/0 IDR/)).toBeNull()
})
it('pages frozen cases through the approval API and leaves unresolved installed history outside the baseline', async () => {
  const first = migrationCaseFixture(), second = { ...first, caseId: id.other, sourceId: id.policy, sourceTable: 'onu',
    sourceSnapshot: { id: id.policy, serialNumber: '', model: 'ONT historis' }, resolution: null, resolutionRequired: false }
  const fetch = vi.fn(async (path: string) => response({ items: [path.includes('page=1') ? second : first], page: path.includes('page=1') ? 1 : 0, size: 1, totalElements: 2 }))
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseApprovalMigration id={id.request} document={approvalDocument(migrationDocumentFixture())} /></MemoryRouter>)
  expect(fetch).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Tampilkan kasus migrasi' }))
  await screen.findByText('Kabel diperiksa dalam milimeter')
  expect(screen.getByText(/82,5/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Berikutnya' }))
  await screen.findByRole('heading', { name: 'ONT historis' })
  expect(screen.getByText(/Riwayat belum diselesaikan, di luar saldo awal/)).toBeTruthy()
  expect(screen.getByText('(kosong)')).toBeTruthy()
  expect(screen.queryByRole('button', { name: /Unduh bukti kasus/ })).toBeNull()
  expect(fetch.mock.calls.every(([path]) => path.startsWith('/api/v1/warehouse/approvals/' + id.request + '/migration-cases?'))).toBe(true)
})
it('downloads the original case proof and exposes a current-scope denial when reloading the review', async () => {
  const { webcrypto } = await vi.importActual<{ webcrypto: Crypto }>('node:crypto')
  vi.stubGlobal('crypto', webcrypto)
  const bytes = new TextEncoder().encode('%PDF-original-case-proof'), row = migrationCaseFixture()
  row.resolution.evidence[0].sha256 = Array.from(new Uint8Array(await webcrypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('')
  const create = vi.fn((_blob: Blob) => 'blob:migration-proof'), click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  vi.stubGlobal('URL', class extends URL { static createObjectURL = create; static revokeObjectURL = vi.fn() })
  let denied = false
  const fetch = vi.fn(async (path: string) => denied ? response({ code: 'FORBIDDEN', message: 'Cakupan migrasi berubah' }, 403)
    : path.endsWith('/attachments/' + id.decision) ? new Response(bytes, { headers: { 'Content-Type': 'application/pdf' } })
      : response({ items: [row, { ...row, caseId: id.other, resolution: null, resolutionRequired: false }], page: 0, size: 25, totalElements: 2 }))
  vi.stubGlobal('fetch', fetch)
  render(<MemoryRouter><WarehouseApprovalMigration id={id.request} document={approvalDocument(migrationDocumentFixture())} /></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Tampilkan kasus migrasi' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Unduh bukti kasus 1' }))
  await waitFor(() => expect(click).toHaveBeenCalledTimes(1))
  expect(create.mock.calls[0][0]).toMatchObject({ type: 'application/pdf', size: bytes.length })
  denied = true; fireEvent.click(screen.getByRole('button', { name: 'Muat ulang kasus migrasi' }))
  await screen.findByRole('alert')
  expect(screen.queryByRole('button', { name: 'Unduh bukti kasus 1' })).toBeNull()
})
