import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const { can, stageCustomerImport, commitCustomerImport, cancelCustomerImport, retryCustomerImport, customerImportStatus, downloadCustomerImportReport } = vi.hoisted(() => ({
  can: vi.fn(() => true),
  stageCustomerImport: vi.fn(),
  commitCustomerImport: vi.fn(),
  cancelCustomerImport: vi.fn(),
  retryCustomerImport: vi.fn(),
  customerImportStatus: vi.fn(),
  downloadCustomerImportReport: vi.fn(),
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('../auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('../api/onboarding', () => ({
  CUSTOMER_CSV_COLUMNS: ['name', 'mikrotik_username', 'mikrotik_password', 'id_card_number'],
  stageCustomerImport,
  commitCustomerImport,
  cancelCustomerImport,
  retryCustomerImport,
  customerImportStatus,
  downloadCustomerImportReport,
}))
vi.mock('@/system', () => ({ useToast: () => toast }))

import { ImportCustomersPage } from './ImportCustomersPage'

const staged = {
  id: 'batch-1', sha256: 'a'.repeat(64), mode: 'ALREADY_INSTALLED' as const, state: 'STAGED' as const,
  errors: [], result: null, createdAt: '2026-09-04T00:00:00Z',
}

function upload(): void {
  const input = document.querySelector<HTMLInputElement>('#customer-csv-upload')
  if (!input) throw new Error('input CSV tidak ditemukan')
  fireEvent.change(input, { target: { files: [new File(['name\nmira'], 'pelanggan.csv', { type: 'text/csv' })] } })
}

describe('alur presentasi impor pelanggan bertahap', () => {
  it('menolak layar mutasi ketika izin wajib tidak lengkap', () => {
    can.mockReturnValue(false)
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)
    expect(screen.getByText('Tak berizin')).toBeDefined()
  })

  it('menahan commit saat validasi server gagal dan tidak merender pesan sensitif', async () => {
    stageCustomerImport.mockResolvedValueOnce({ ...staged, errors: [{ row: 2, column: 'name', code: 'INVALID_DATE', message: 'rahasia tidak boleh muncul' }] })
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)

    upload()

    await screen.findByText('INVALID_DATE')
    expect(screen.getByRole('button', { name: 'Jalankan impor' }).hasAttribute('disabled')).toBe(true)
    expect(screen.queryByText('rahasia tidak boleh muncul')).toBeNull()
  })

  it('mempertahankan identitas commit untuk replay setelah respons commit hilang', async () => {
    const user = userEvent.setup()
    stageCustomerImport.mockResolvedValueOnce(staged)
    commitCustomerImport.mockRejectedValueOnce(new Error('respons putus')).mockResolvedValueOnce({ ...staged, state: 'PROCESSING' as const })
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)

    upload()
    await screen.findByRole('button', { name: 'Jalankan impor' })
    await user.click(screen.getByRole('button', { name: 'Jalankan impor' }))
    await waitFor(() => expect(commitCustomerImport).toHaveBeenCalledTimes(1))
    await user.click(screen.getByRole('button', { name: 'Jalankan impor' }))
    await screen.findByText('Sedang diproses')

    expect(commitCustomerImport).toHaveBeenCalledTimes(2)
    const first = commitCustomerImport.mock.calls[0]?.[1]
    const second = commitCustomerImport.mock.calls[1]?.[1]
    expect(first).toEqual({ commitOperationKey: expect.any(String), commitHash: staged.sha256 })
    expect(second).toEqual(first)
  })

  it('mengizinkan retry hanya untuk batch retryable dan memakai identitas commit yang sama', async () => {
    const user = userEvent.setup()
    const retryable = { ...staged, state: 'RETRYABLE_FAILED' as const }
    stageCustomerImport.mockResolvedValueOnce(retryable)
    retryCustomerImport.mockResolvedValueOnce({ ...retryable, state: 'PROCESSING' as const })
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)

    upload()
    await screen.findByRole('button', { name: 'Ulangi batch' })
    await user.click(screen.getByRole('button', { name: 'Ulangi batch' }))
    await screen.findByText('Sedang diproses')

    expect(retryCustomerImport).toHaveBeenCalledWith('batch-1', { commitOperationKey: expect.any(String), commitHash: staged.sha256 })
  })

  it('menjelaskan batch permanen atau terhapus tanpa menawarkan retry', async () => {
    stageCustomerImport.mockResolvedValueOnce({ ...staged, state: 'PERMANENT_FAILED' as const })
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)

    upload()
    await screen.findByText('Perlu perbaikan manual')
    expect(screen.getByRole('button', { name: 'Ulangi batch' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByText('Batch tidak dapat diulangi. Perbaiki sumber data lalu unggah berkas baru.')).toBeDefined()
  })

  it('menandai batch yang dipurge sebagai tidak lagi tersedia', async () => {
    stageCustomerImport.mockResolvedValueOnce({ ...staged, state: 'PURGED' as const })
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)

    upload()

    await screen.findByText('Sudah dihapus')
    expect(screen.getByRole('button', { name: 'Unduh rekap aman' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByText('Data batch telah melewati retensi dan tidak lagi tersedia.')).toBeDefined()
  })

  it('mengunduh rekap aman dari server, bukan metadata batch di browser', async () => {
    const user = userEvent.setup()
    stageCustomerImport.mockResolvedValueOnce({ ...staged, state: 'COMMITTED' as const })
    downloadCustomerImportReport.mockResolvedValueOnce(new Blob(['status,COMMITTED'], { type: 'text/csv' }))
    render(<MemoryRouter><ImportCustomersPage /></MemoryRouter>)

    upload()
    await screen.findByText('Selesai')
    await user.click(screen.getByRole('button', { name: 'Unduh rekap aman' }))

    await waitFor(() => expect(downloadCustomerImportReport).toHaveBeenCalledWith('batch-1'))
  })
})

beforeEach(() => {
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:report'), revokeObjectURL: vi.fn() })
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  can.mockReset()
  can.mockReturnValue(true)
  stageCustomerImport.mockReset()
  commitCustomerImport.mockReset()
  cancelCustomerImport.mockReset()
  retryCustomerImport.mockReset()
  customerImportStatus.mockReset()
  downloadCustomerImportReport.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
  toast.info.mockReset()
})
