import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { OrderLeadView } from '@/api/order'

const { can, listOrderLeads, createOrderLead, changeOrderLeadStatus, promoteOrderLead } = vi.hoisted(() => ({
  can: vi.fn((_permission: string) => true),
  listOrderLeads: vi.fn(),
  createOrderLead: vi.fn(),
  changeOrderLeadStatus: vi.fn(),
  promoteOrderLead: vi.fn(),
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can }) }))
vi.mock('@/system', () => ({ useToast: () => toast }))
// [LEAD_STATUS_TARGETS] sengaja TIDAK dipalsukan: uji ini ikut menjaga agar daftar tujuan
// status di layar tetap sama dengan yang diterima server.
vi.mock('@/api/order', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/order')>()),
  listOrderLeads,
  createOrderLead,
  changeOrderLeadStatus,
  promoteOrderLead,
}))

import { OrderLeadsPage } from './OrderLeadsPage'

const lead = (overrides: Partial<OrderLeadView> = {}): OrderLeadView => ({
  id: 'lead-1',
  name: 'Budi Santoso',
  phone: '081234567890',
  email: null,
  address: 'Jl. Anggrek No. 12',
  latitude: null,
  longitude: null,
  interestedPlanId: 'plan-1',
  source: 'PUBLIC_WEB',
  status: 'NEW',
  convertedCustomerId: null,
  notes: null,
  createdAt: '2026-09-01T02:00:00Z',
  updatedAt: '2026-09-01T02:00:00Z',
  ...overrides,
})

const page = (content: OrderLeadView[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 })

function mount() {
  return render(<MemoryRouter><OrderLeadsPage /></MemoryRouter>)
}

const openRowMenu = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(screen.getByRole('button', { name: 'Aksi baris' }))
  return screen.getByRole('menu')
}

describe('meja calon pelanggan', () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', '') }
    HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open') }
  })

  it('menolak layar untuk pengguna tanpa izin baca prospek', () => {
    can.mockReturnValue(false)
    mount()

    expect(screen.getByText('Tak berizin')).toBeDefined()
    expect(listOrderLeads).not.toHaveBeenCalled()
  })

  it('menampilkan prospek beserta sumber dan statusnya', async () => {
    listOrderLeads.mockResolvedValue(page([lead()]))
    mount()

    await screen.findByText('Budi Santoso')
    const grid = screen.getByRole('grid')
    expect(within(grid).getByText('081234567890')).toBeDefined()
    expect(within(grid).getByText('Web publik')).toBeDefined()
    expect(within(grid).getByText('Baru')).toBeDefined()
  })

  it('menyembunyikan tombol tambah dari pengguna yang hanya boleh membaca', async () => {
    can.mockImplementation((permission: string) => permission === 'order.lead.view')
    listOrderLeads.mockResolvedValue(page([lead()]))
    mount()

    await screen.findByText('Budi Santoso')
    expect(screen.queryByRole('button', { name: /Tambah/ })).toBeNull()
  })

  it('mematikan aksi tulis di menu baris untuk pengguna yang hanya boleh membaca', async () => {
    const user = userEvent.setup()
    can.mockImplementation((permission: string) => permission === 'order.lead.view')
    listOrderLeads.mockResolvedValue(page([lead()]))
    mount()

    await screen.findByText('Budi Santoso')
    const menu = await openRowMenu(user)

    for (const item of within(menu).getAllByRole('menuitem')) {
      expect(item.getAttribute('aria-disabled')).toBe('true')
    }
    expect(promoteOrderLead).not.toHaveBeenCalled()
  })

  it('mempromosikan prospek hanya setelah konfirmasi', async () => {
    const user = userEvent.setup()
    listOrderLeads.mockResolvedValue(page([lead()]))
    promoteOrderLead.mockResolvedValue({ leadId: 'lead-1', customerId: 'cust-1', subscriptionId: 'sub-1', alreadyConverted: false })
    mount()

    await screen.findByText('Budi Santoso')
    const menu = await openRowMenu(user)
    await user.click(within(menu).getByRole('menuitem', { name: 'Promosikan jadi pelanggan' }))

    expect(promoteOrderLead).not.toHaveBeenCalled()
    await user.click(within(screen.getAllByRole('dialog')[0]).getByRole('button', { name: 'Promosikan' }))

    await waitFor(() => expect(promoteOrderLead).toHaveBeenCalledWith('lead-1'))
  })

  it('tidak menawarkan tujuan status yang tidak sah dan mematikan promosi prospek yang sudah dikonversi', async () => {
    const user = userEvent.setup()
    listOrderLeads.mockResolvedValue(page([lead({ status: 'CONVERTED', convertedCustomerId: 'cust-1' })]))
    mount()

    await screen.findByText('Budi Santoso')
    const menu = await openRowMenu(user)

    // CONVERTED adalah status akhir: server tak menerima satu pun perubahan status darinya.
    expect(within(menu).queryByRole('menuitem', { name: /Ubah status/ })).toBeNull()
    expect(within(menu).getByRole('menuitem', { name: 'Promosikan jadi pelanggan' }).getAttribute('aria-disabled')).toBe('true')
  })

  it('menolak prospek tanpa nomor HP, karena nomor itu satu-satunya jalan menelepon balik', async () => {
    const user = userEvent.setup()
    listOrderLeads.mockResolvedValue(page([]))
    mount()

    await waitFor(() => expect(listOrderLeads).toHaveBeenCalled())
    await user.click(screen.getByRole('button', { name: /Tambah/ }))
    await user.type(await screen.findByLabelText(/^Nama/), 'Siti Aminah')
    await user.click(within(screen.getAllByRole('dialog')[0]).getByRole('button', { name: 'Simpan' }))

    expect(createOrderLead).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('Nama dan nomor HP wajib diisi')
  })

  it('mengirim prospek buatan operator dengan sumber OPERATOR', async () => {
    const user = userEvent.setup()
    listOrderLeads.mockResolvedValue(page([]))
    createOrderLead.mockResolvedValue(lead({ source: 'OPERATOR' }))
    mount()

    await waitFor(() => expect(listOrderLeads).toHaveBeenCalled())
    await user.click(screen.getByRole('button', { name: /Tambah/ }))
    await user.type(await screen.findByLabelText(/^Nama/), 'Siti Aminah')
    await user.type(screen.getByLabelText(/^Nomor HP/), '0899999999')
    await user.click(within(screen.getAllByRole('dialog')[0]).getByRole('button', { name: 'Simpan' }))

    await waitFor(() => expect(createOrderLead).toHaveBeenCalled())
    expect(createOrderLead.mock.calls[0][0]).toMatchObject({ name: 'Siti Aminah', phone: '0899999999', source: 'OPERATOR' })
  })
})

afterEach(() => {
  can.mockReset()
  can.mockReturnValue(true)
  listOrderLeads.mockReset()
  createOrderLead.mockReset()
  changeOrderLeadStatus.mockReset()
  promoteOrderLead.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
})
