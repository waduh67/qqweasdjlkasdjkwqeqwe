import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OrderSummaryView } from '@/api/order'

const { can, listOrders, getOrderTimeline } = vi.hoisted(() => ({
  can: vi.fn(() => true),
  listOrders: vi.fn(),
  getOrderTimeline: vi.fn(),
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('@/system', () => ({ useToast: () => toast }))
// Hanya fungsi jaringannya yang dipalsukan; labelnya tetap yang asli supaya uji ini ikut menjaga
// bahwa teks yang dibaca operator memang berasal dari peta label, bukan tiruannya.
//
// [getOrderTimeline] tetap dipalsukan MESKI layar ini tak lagi memanggilnya — justru supaya
// panggilan yang kembali diam-diam langsung terlihat sebagai tiruan yang terpanggil.
vi.mock('@/api/order', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/order')>()),
  listOrders,
  getOrderTimeline,
}))

import { OrdersPage } from './OrdersPage'

const order = (overrides: Partial<OrderSummaryView> = {}): OrderSummaryView => ({
  id: 'order-1',
  orderNumber: 'ORD-2609-0001',
  status: 'ACCEPTED',
  customerId: null,
  leadId: 'lead-1',
  requesterName: 'Budi Santoso',
  requesterPhone: '081234567890',
  address: 'Jl. Anggrek No. 12',
  city: 'Bekasi',
  appointmentStartsAt: null,
  portalFlag: null,
  portalFlagReason: null,
  portalFlagSource: null,
  revision: 2,
  createdAt: '2026-09-01T02:00:00Z',
  updatedAt: '2026-09-01T02:00:00Z',
  ...overrides,
})

function page(content: OrderSummaryView[]) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: 1 }
}

function renderPage() {
  return render(<MemoryRouter><OrdersPage /></MemoryRouter>)
}

describe('antrean pesanan operator', () => {
  it('menolak layar untuk pengguna tanpa izin baca pesanan', () => {
    can.mockReturnValue(false)
    renderPage()

    expect(screen.getByText('Tak berizin')).toBeDefined()
    expect(listOrders).not.toHaveBeenCalled()
  })

  it('menampilkan pemesan, alamat, dan status dari antrean server', async () => {
    listOrders.mockResolvedValueOnce(page([order()]))
    renderPage()

    await screen.findByText('ORD-2609-0001')
    expect(screen.getByText('Budi Santoso')).toBeDefined()
    expect(screen.getByText('Jl. Anggrek No. 12, Bekasi')).toBeDefined()
    // Dicari di dalam grid saja: "Diterima" juga muncul sebagai pilihan di penyaring status,
    // dan pencarian global akan cocok dengan penyaringnya walau barisnya tidak pernah dirender.
    expect(within(screen.getByRole('grid')).getByText('Diterima')).toBeDefined()
  })

  it('tetap menampilkan pesanan yang pemesannya tak bisa diresolusi', async () => {
    listOrders.mockResolvedValueOnce(page([order({ requesterName: null, requesterPhone: null })]))
    renderPage()

    await screen.findByText('Pemesan tak dikenal')
  })

  it('menampilkan penanda langsung dari baris antrean, tanpa memuat riwayat sama sekali', async () => {
    listOrders.mockResolvedValueOnce(
      page([
        order({ portalFlag: 'WAITING_CUSTOMER', portalFlagReason: 'Mohon hubungi kami', portalFlagSource: 'OPERATOR' }),
        order({ id: 'order-2', orderNumber: 'ORD-2609-0002' }),
      ]),
    )
    renderPage()

    await screen.findByText('ORD-2609-0001')
    expect(within(screen.getByRole('grid')).getByText('Menunggu pelanggan')).toBeDefined()
    // Riwayat TIDAK boleh disentuh lagi. Inilah inti perubahannya: satu halaman dulu berarti 20
    // permintaan riwayat, dan angka nol di sini yang menjaga tambalan itu tidak diam-diam kembali.
    expect(getOrderTimeline).not.toHaveBeenCalled()
  })

  it('menyerahkan penyaringan penanda ke server, bukan menyaring halaman yang sedang tampil', async () => {
    const user = userEvent.setup()
    listOrders.mockResolvedValue(page([order({ portalFlag: 'WAITING_CUSTOMER' })]))
    renderPage()

    await screen.findByText('ORD-2609-0001')
    await user.selectOptions(screen.getByLabelText('Saring penanda'), 'ANY')

    await waitFor(() =>
      expect(listOrders).toHaveBeenLastCalledWith(expect.objectContaining({ flagged: true, portalFlag: '', page: 0 })),
    )

    // Satu tanda tertentu dikirim sebagai `portalFlag`, dan `flagged` HARUS ikut lepas — kalau
    // keduanya terkirim bersamaan, penyaringnya menyempit dua kali dan hasilnya membingungkan.
    await user.selectOptions(screen.getByLabelText('Saring penanda'), 'REQUIRES_ATTENTION')
    await waitFor(() =>
      expect(listOrders).toHaveBeenLastCalledWith(
        expect.objectContaining({ flagged: undefined, portalFlag: 'REQUIRES_ATTENTION', page: 0 }),
      ),
    )
  })

  it('menyaring status lewat server dan mengembalikan pembaca ke halaman pertama', async () => {
    const user = userEvent.setup()
    listOrders.mockResolvedValue(page([order()]))
    renderPage()

    await screen.findByText('ORD-2609-0001')
    await user.selectOptions(screen.getByLabelText('Saring status'), 'SUBMITTED')

    await waitFor(() => expect(listOrders).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'SUBMITTED', page: 0 })))
  })
})

afterEach(() => {
  can.mockReset()
  can.mockReturnValue(true)
  listOrders.mockReset()
  getOrderTimeline.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
})
