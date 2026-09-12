import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderImportBatchDetailView, OrderImportBatchSummaryView, OrderImportRowView } from '@/api/order'

const { can, previewOrderImport, commitOrderImport, listOrderImportHistory, downloadOrderImportTemplate, downloadBlob } =
  vi.hoisted(() => ({
    can: vi.fn(() => true),
    previewOrderImport: vi.fn(),
    commitOrderImport: vi.fn(),
    listOrderImportHistory: vi.fn(),
    downloadOrderImportTemplate: vi.fn(),
    downloadBlob: vi.fn(),
  }))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('@/system', () => ({ useToast: () => toast }))
vi.mock('@/utils/download', () => ({ downloadBlob }))
vi.mock('@/api/order', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/order')>()),
  previewOrderImport,
  commitOrderImport,
  listOrderImportHistory,
  downloadOrderImportTemplate,
}))

import { ImportOrdersPage } from './ImportOrdersPage'

const summary = (overrides: Partial<OrderImportBatchSummaryView> = {}): OrderImportBatchSummaryView => ({
  id: 'batch-1',
  fileName: 'pesanan-september.csv',
  status: 'PREVIEWED',
  delimiter: ',',
  byteSize: 4096,
  totalRows: 2,
  acceptedRows: 1,
  rejectedRows: 1,
  createdRows: 0,
  failedRows: 0,
  importedBy: 'user-1',
  createdAt: '2026-09-01T02:00:00Z',
  committedAt: null,
  ...overrides,
})

const row = (overrides: Partial<OrderImportRowView> = {}): OrderImportRowView => ({
  id: 'row-1',
  lineNumber: 2,
  status: 'ACCEPTED',
  message: null,
  name: 'Budi Santoso',
  phone: '081234567890',
  email: null,
  planId: 'plan-1',
  address: 'Jl. Anggrek No. 12',
  city: 'Bekasi',
  postalCode: '17111',
  notes: null,
  orderId: null,
  orderNumber: null,
  ...overrides,
})

const detail = (
  batch: OrderImportBatchSummaryView = summary(),
  rows: OrderImportRowView[] = [
    row(),
    row({ id: 'row-2', lineNumber: 3, status: 'REJECTED', message: 'Kolom paket tidak dikenali: Super Ngebut', name: 'Siti', planId: null }),
  ],
): OrderImportBatchDetailView => ({ batch, rows })

function mount() {
  return render(<MemoryRouter><ImportOrdersPage /></MemoryRouter>)
}

/** Berkas CSV palsu dengan ukuran yang bisa ditentukan — dipakai untuk menguji batas 2 MiB. */
const csv = (name: string, bytes: number) => new File([new Uint8Array(bytes)], name, { type: 'text/csv' })

const fileInput = (container: HTMLElement) => container.querySelector('#order-csv-upload') as HTMLInputElement

describe('impor pesanan dua langkah', () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', '') }
    HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open') }
    listOrderImportHistory.mockResolvedValue([])
  })

  it('menolak layar untuk pengguna tanpa izin impor', () => {
    can.mockReturnValue(false)
    mount()

    expect(screen.getByText('Tak berizin')).toBeDefined()
    expect(listOrderImportHistory).not.toHaveBeenCalled()
  })

  it('menyebut batas ukuran dan jumlah baris SEBELUM berkas diunggah', () => {
    mount()

    // 2 MiB dan 1.000 baris ditulis di layar, bukan hanya diketahui server: operator yang
    // menyiapkan berkas 5.000 baris harus tahu sebelum menunggu unggahan yang pasti ditolak.
    expect(screen.getByText(/2\.048 KiB per berkas dan 1\.000 baris data/)).toBeDefined()
  })

  it('tidak menyediakan satu pun tombol jalankan sebelum ada pratinjau', () => {
    mount()

    expect(screen.queryByRole('button', { name: /Jalankan/ })).toBeNull()
    expect(screen.queryByText(/2\. Pratinjau/)).toBeNull()
  })

  it('menolak berkas melebihi 2 MiB tanpa mengirimnya ke server', async () => {
    const user = userEvent.setup()
    const { container } = mount()

    await user.upload(fileInput(container), csv('besar.csv', 3 * 1024 * 1024))

    expect(previewOrderImport).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('melebihi batas'))
  })

  it('menampilkan alasan penolakan tiap baris pada pratinjau', async () => {
    const user = userEvent.setup()
    previewOrderImport.mockResolvedValue(detail())
    const { container } = mount()

    await user.upload(fileInput(container), csv('pesanan-september.csv', 1024))

    const rows = await screen.findByRole('table', { name: 'Pratinjau baris impor' })
    expect(within(rows).getByText('Kolom paket tidak dikenali: Super Ngebut')).toBeDefined()
    expect(within(rows).getByText('Ditolak')).toBeDefined()
    expect(commitOrderImport).not.toHaveBeenCalled()
  })

  it('menjalankan impor hanya setelah pratinjau dan konfirmasi', async () => {
    const user = userEvent.setup()
    previewOrderImport.mockResolvedValue(detail())
    commitOrderImport.mockResolvedValue(
      detail(summary({ status: 'COMMITTED', acceptedRows: 1, createdRows: 1, committedAt: '2026-09-01T03:00:00Z' }), [
        row({ status: 'CREATED', orderNumber: 'ORD-2609-0007' }),
      ]),
    )
    const { container } = mount()

    await user.upload(fileInput(container), csv('pesanan-september.csv', 1024))
    await user.click(await screen.findByRole('button', { name: 'Jalankan 1 baris' }))
    // Konfirmasi dulu: 1 klik tak boleh cukup untuk melahirkan pesanan dan calon pelanggan.
    await user.click(within(screen.getAllByRole('dialog')[0]).getByRole('button', { name: 'Jalankan' }))

    await waitFor(() => expect(commitOrderImport).toHaveBeenCalledWith('batch-1'))
    await screen.findByText('ORD-2609-0007')
  })

  it('mematikan tombol jalankan untuk batch yang sudah dijalankan, dan menyebut alasannya', async () => {
    const user = userEvent.setup()
    previewOrderImport.mockResolvedValue(
      detail(summary({ status: 'COMMITTED', createdRows: 1, committedAt: '2026-09-01T03:00:00Z' })),
    )
    const { container } = mount()

    await user.upload(fileInput(container), csv('pesanan-september.csv', 1024))

    const run = (await screen.findByRole('button', { name: /Jalankan/ })) as HTMLButtonElement
    expect(run.disabled).toBe(true)
    expect(screen.getByText(/menjalankannya lagi tidak membuat pesanan kedua/)).toBeDefined()
  })

  it('mematikan tombol jalankan ketika tak ada satu pun baris yang lolos', async () => {
    const user = userEvent.setup()
    previewOrderImport.mockResolvedValue(
      detail(summary({ acceptedRows: 0, rejectedRows: 2 }), [
        row({ status: 'REJECTED', message: 'Nomor HP kosong' }),
        row({ id: 'row-2', lineNumber: 3, status: 'REJECTED', message: 'Kota kosong' }),
      ]),
    )
    const { container } = mount()

    await user.upload(fileInput(container), csv('pesanan-september.csv', 1024))

    const run = (await screen.findByRole('button', { name: /Jalankan/ })) as HTMLButtonElement
    expect(run.disabled).toBe(true)
    expect(screen.getByText(/Tak ada baris yang lolos/)).toBeDefined()
  })

  it('mengunduh berkas contoh lewat unduhan berotentikasi, bukan tautan polos', async () => {
    const user = userEvent.setup()
    downloadOrderImportTemplate.mockResolvedValue(new Blob(['nama,hp\n']))
    mount()

    await user.click(screen.getByRole('button', { name: /Unduh berkas contoh/ }))

    await waitFor(() => expect(downloadBlob).toHaveBeenCalled())
    expect(downloadBlob.mock.calls[0][1]).toBe('contoh-impor-pesanan.csv')
  })
})

afterEach(() => {
  can.mockReset()
  can.mockReturnValue(true)
  previewOrderImport.mockReset()
  commitOrderImport.mockReset()
  listOrderImportHistory.mockReset()
  downloadOrderImportTemplate.mockReset()
  downloadBlob.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
})
