import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { OrderSummaryView, OrderTimelineEntryView } from '@/api/order'

const { can, listOrders, getOrderTimeline } = vi.hoisted(() => ({
  can: vi.fn(() => true),
  listOrders: vi.fn(),
  getOrderTimeline: vi.fn(),
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('@/system', () => ({ useToast: () => toast }))
// Hanya fungsi jaringannya yang dipalsukan; label dan [derivePortalFlag] tetap yang asli supaya
// uji ini ikut menguji aturan rekonstruksi penanda, bukan tiruan aturannya.
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
  revision: 2,
  createdAt: '2026-09-01T02:00:00Z',
  updatedAt: '2026-09-01T02:00:00Z',
  ...overrides,
})

const entry = (overrides: Partial<OrderTimelineEntryView> = {}): OrderTimelineEntryView => ({
  revision: 1,
  eventType: 'ORDER_CREATED',
  fromStatus: null,
  toStatus: 'DRAFT',
  reason: null,
  actorId: null,
  occurredAt: '2026-09-01T02:00:00Z',
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

  it('menandai pesanan bertanda perhatian lewat pemindaian riwayat, karena antrean server tak memuatnya', async () => {
    const user = userEvent.setup()
    listOrders.mockResolvedValueOnce(page([order(), order({ id: 'order-2', orderNumber: 'ORD-2609-0002' })]))
    getOrderTimeline.mockImplementation((id: string) =>
      Promise.resolve(
        id === 'order-1'
          ? [
              entry(),
              entry({ revision: 2, eventType: 'ORDER_FLAGGED', toStatus: 'WAITING_CUSTOMER', reason: 'Mohon hubungi kami' }),
            ]
          : [entry()],
      ),
    )
    renderPage()

    await screen.findByText('ORD-2609-0001')
    await user.click(screen.getByRole('button', { name: /Periksa penanda perhatian/ }))

    await screen.findByText('Menunggu pelanggan')
    expect(getOrderTimeline).toHaveBeenCalledTimes(2)

    await user.click(screen.getByRole('button', { name: 'Hanya yang bertanda' }))
    expect(screen.queryByText('ORD-2609-0002')).toBeNull()
    expect(screen.getByText('ORD-2609-0001')).toBeDefined()
  })

  it('tidak menganggap pesanan yang penandanya sudah dilepas sebagai bertanda', async () => {
    const user = userEvent.setup()
    listOrders.mockResolvedValueOnce(page([order()]))
    getOrderTimeline.mockResolvedValueOnce([
      entry({ revision: 2, eventType: 'ORDER_FLAGGED', toStatus: 'WAITING_CUSTOMER' }),
      entry({ revision: 3, eventType: 'ORDER_UNFLAGGED', toStatus: 'ACCEPTED' }),
    ])
    renderPage()

    await screen.findByText('ORD-2609-0001')
    await user.click(screen.getByRole('button', { name: /Periksa penanda perhatian/ }))

    await waitFor(() => expect(getOrderTimeline).toHaveBeenCalled())
    expect(screen.queryByText('Menunggu pelanggan')).toBeNull()
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
