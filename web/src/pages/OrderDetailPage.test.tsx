import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderStatus, OrderTimelineEntryView, OrderView } from '@/api/order'

const { can, getOrder, getOrderTimeline, transitionOrder, flagOrder, markOrderUnreachable } = vi.hoisted(() => ({
  can: vi.fn((_permission: string) => true),
  getOrder: vi.fn(),
  getOrderTimeline: vi.fn(),
  transitionOrder: vi.fn(),
  flagOrder: vi.fn(),
  markOrderUnreachable: vi.fn(),
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('@/system', () => ({ useToast: () => toast }))
// Hanya fungsi jaringannya yang dipalsukan; [canTransition] dan [portalFlagSetAt] tetap asli
// supaya uji ini menguji tabel transisi yang sebenarnya, bukan tiruannya.
vi.mock('@/api/order', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/order')>()),
  getOrder,
  getOrderTimeline,
  transitionOrder,
  flagOrder,
  markOrderUnreachable,
}))

import { OrderDetailPage } from './OrderDetailPage'

const view = (overrides: Partial<OrderView> = {}): OrderView => ({
  id: 'order-1',
  tenantId: 'tenant-1',
  customerId: null,
  status: 'ACCEPTED',
  lines: [{ catalogItemId: 'plan-1', description: 'Paket 30 Mbps', quantity: 1 }],
  serviceAddress: { address: 'Jl. Anggrek No. 12', city: 'Bekasi', postalCode: '17111', latitude: null, longitude: null },
  appointment: null,
  cancellationReason: null,
  rejectionReason: null,
  revision: 4,
  lastActorId: null,
  leadId: 'lead-1',
  orderNumber: 'ORD-2609-0001',
  portalFlag: null,
  portalFlagReason: null,
  portalFlagSource: null,
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

function mount(order: OrderView = view(), timeline: OrderTimelineEntryView[] = [entry()]) {
  getOrder.mockResolvedValue(order)
  getOrderTimeline.mockResolvedValue(timeline)
  return render(
    <MemoryRouter initialEntries={[`/orders/${order.id}`]}>
      <Routes>
        <Route path="/orders/:id" element={<OrderDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const button = (name: string) => screen.getByRole('button', { name }) as HTMLButtonElement

// [Modal] membungkus `<div role="dialog">` di dalam `<dialog>` asli (yang perannya juga dialog),
// jadi selalu ada DUA simpul berperan dialog untuk satu modal. Isinya sama; ambil yang terluar.
const dialog = () => screen.getAllByRole('dialog')[0]

describe('detail pesanan operator', () => {
  // jsdom tak mengimplementasikan `<dialog>.showModal`, sedangkan [Modal] memanggilnya supaya
  // dialognya naik ke top layer browser. Tanpa tiruan ini SETIAP uji yang membuka dialog mati
  // dengan TypeError sebelum assertion pertama. Pola ini sama dengan `NetworkProvisioningPage.test.tsx`.
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', '') }
    HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open') }
  })

  it('mematikan transisi yang tidak sah untuk status sekarang', async () => {
    mount(view({ status: 'ACCEPTED' }))
    await screen.findByText('Ubah status')

    // ACCEPTED hanya boleh dijadwalkan, diselesaikan, atau dibatalkan.
    expect(button('Jadwalkan').disabled).toBe(false)
    expect(button('Tandai selesai').disabled).toBe(false)
    expect(button('Batalkan').disabled).toBe(false)
    expect(button('Ajukan').disabled).toBe(true)
    expect(button('Terima').disabled).toBe(true)
    expect(button('Mulai kerjakan').disabled).toBe(true)
    expect(button('Tolak').disabled).toBe(true)
  })

  it('mematikan semua transisi pada status akhir', async () => {
    mount(view({ status: 'FULFILLED' as OrderStatus }))
    await screen.findByText('Ubah status')

    for (const label of ['Ajukan', 'Terima', 'Jadwalkan', 'Mulai kerjakan', 'Tandai selesai', 'Batalkan', 'Tolak']) {
      expect(button(label).disabled).toBe(true)
    }
  })

  it('mematikan seluruh aksi tulis untuk pengguna yang hanya boleh membaca', async () => {
    can.mockImplementation((permission: string) => permission === 'order.order.view')
    mount(view({ status: 'SUBMITTED' }))
    await screen.findByText('Ubah status')

    // 'Terima' sah untuk SUBMITTED, jadi kalau tombolnya hidup di sini penyebabnya PASTI izin.
    expect(button('Terima').disabled).toBe(true)
    expect(button('Tandai menunggu pelanggan').disabled).toBe(true)
    expect(button('Tandai perlu perhatian').disabled).toBe(true)
  })

  it('menolak pembatalan tanpa alasan dan tidak mengirim apa pun ke server', async () => {
    const user = userEvent.setup()
    mount()
    await screen.findByText('Ubah status')

    await user.click(button('Batalkan'))
    await screen.findAllByRole('dialog')
    await user.click(within(dialog()).getByRole('button', { name: 'Batalkan' }))

    expect(transitionOrder).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('Alasan wajib diisi')
    // Dialognya tetap terbuka supaya operator bisa mengisi alasannya, bukan mengulang dari awal.
    expect(screen.queryAllByRole('dialog').length).toBeGreaterThan(0)
  })

  it('memakai ULANG kunci operasi saat percobaan kedua, supaya tak lahir work order PSB kedua', async () => {
    const user = userEvent.setup()
    transitionOrder.mockRejectedValueOnce(new Error('jaringan putus')).mockResolvedValueOnce(undefined)
    mount(view({ status: 'SUBMITTED' }))
    await screen.findByText('Ubah status')

    const confirm = () => within(dialog()).getByRole('button', { name: 'Terima' })

    await user.click(button('Terima'))
    await screen.findAllByRole('dialog')
    await user.click(confirm())
    await waitFor(() => expect(transitionOrder).toHaveBeenCalledTimes(1))

    // Percobaan pertama gagal, dialognya tetap terbuka; operator menekan tombol yang sama lagi.
    await user.click(confirm())
    await waitFor(() => expect(transitionOrder).toHaveBeenCalledTimes(2))

    const [first, second] = transitionOrder.mock.calls
    expect(first[2].operation.key).toBe(second[2].operation.key)
    expect(first[2].expectedRevision).toBe(4)
  })

  it('menampilkan penanda portal dari pesanannya beserta kalimat dan asal-usulnya', async () => {
    mount(
      view({ portalFlag: 'WAITING_CUSTOMER', portalFlagReason: 'Mohon hubungi kami', portalFlagSource: 'SYSTEM' }),
      [entry(), entry({ revision: 5, eventType: 'ORDER_FLAGGED', toStatus: 'WAITING_CUSTOMER', reason: 'Mohon hubungi kami' })],
    )

    // Penandanya tampil dua kali dan itu memang disengaja: lencana di kepala kartu (terlihat
    // sekilas) dan kartu penanda (dengan kalimat serta waktunya).
    await screen.findByText(/Terpasang/)
    expect(screen.getAllByText('Menunggu pelanggan')).toHaveLength(2)
    expect(screen.getAllByText(/Mohon hubungi kami/).length).toBeGreaterThan(0)
    // Asal penandanya HARUS terbaca: penanda sistem dipasang ulang sendiri setelah dilepas.
    // getAllByText: kalimatnya dipecah jadi beberapa simpul teks, jadi induknya ikut cocok.
    expect(screen.getAllByText(/oleh sistem/).length).toBeGreaterThan(0)
    expect(button('Lepas penanda').disabled).toBe(false)
  })

  /**
   * Riwayat yang MASIH memuat `ORDER_FLAGGED` lama tidak boleh membuat pesanan tampak bertanda.
   *
   * Dulu penandanya disimpulkan dari riwayat, jadi kasus ini menguji aturan rekonstruksi. Sekarang
   * pesanannya sendiri yang menjawab, dan riwayat lama justru jadi jebakan terbaik untuk
   * membuktikan tak ada sisa rekonstruksi yang tertinggal.
   */
  it('mematikan "lepas penanda" ketika pesanan memang tidak bertanda, walau riwayatnya pernah bertanda', async () => {
    mount(view(), [entry(), entry({ revision: 5, eventType: 'ORDER_FLAGGED', toStatus: 'WAITING_CUSTOMER' })])
    await screen.findByText('Pesanan ini tidak sedang bertanda.')

    expect(button('Lepas penanda').disabled).toBe(true)
  })

  it('mematikan "tidak bisa dihubungi" ketika janji temu sudah ada, karena server menolaknya', async () => {
    mount(view({ appointment: { startsAt: '2026-09-10T01:00:00Z', endsAt: '2026-09-10T03:00:00Z' } }))
    await screen.findByText('Penanda perhatian')

    expect(button('Pelanggan tidak bisa dihubungi').disabled).toBe(true)
  })

  it('mengirim kalimat penanda untuk pelanggan lewat endpoint attention', async () => {
    const user = userEvent.setup()
    flagOrder.mockResolvedValue(undefined)
    mount()
    await screen.findByText('Penanda perhatian')

    await user.click(button('Tandai perlu perhatian'))
    await user.type(await screen.findByLabelText(/Kalimat untuk pelanggan/), 'Tolong siapkan KTP')
    await user.click(screen.getByRole('button', { name: 'Pasang penanda' }))

    await waitFor(() => expect(flagOrder).toHaveBeenCalled())
    expect(flagOrder.mock.calls[0][1]).toMatchObject({ flag: 'REQUIRES_ATTENTION', reason: 'Tolong siapkan KTP' })
  })
})

afterEach(() => {
  can.mockReset()
  can.mockReturnValue(true)
  getOrder.mockReset()
  getOrderTimeline.mockReset()
  transitionOrder.mockReset()
  flagOrder.mockReset()
  markOrderUnreachable.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
})
