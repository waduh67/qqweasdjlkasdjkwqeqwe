import { render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { getPortalOrders } = vi.hoisted(() => ({ getPortalOrders: vi.fn() }))

vi.mock('./portalApi', () => ({ getPortalOrders }))

import { PortalOrdersPage } from './PortalOrdersPage'

describe('PortalOrdersPage', () => {
  it('shows only customer-safe order facts when the projection contains operational fields', async () => {
    getPortalOrders.mockResolvedValueOnce([{
      id: 'order-1',
      status: 'SCHEDULED',
      lines: [{ catalogItemId: 'plan-1', description: 'Pasang internet rumah', quantity: 1 }],
      serviceAddress: { address: 'Jl. Melati 1', city: 'Bekasi', postalCode: '17121' },
      appointment: { startsAt: '2026-09-04T08:00:00Z', endsAt: '2026-09-04T10:00:00Z' },
      revision: 3,
      technicianName: 'Teknisi internal',
      latitude: -6.2,
      longitude: 106.8,
      approvalNote: 'Catatan internal',
      warehouseBin: 'BIN-A1',
    }])

    render(<PortalOrdersPage />)

    await screen.findByText('Terjadwal')

    expect(screen.queryByText('Teknisi internal')).toBeNull()
    expect(screen.queryByText('Catatan internal')).toBeNull()
    expect(screen.queryByText('BIN-A1')).toBeNull()
    expect(screen.queryByText('-6.2')).toBeNull()
  })

  it('provides a retry after the customer projection fails to load', async () => {
    getPortalOrders.mockRejectedValueOnce(new Error('Jaringan putus')).mockResolvedValueOnce([])

    render(<PortalOrdersPage />)

    await screen.findByRole('alert')
    screen.getByRole('button', { name: 'Coba lagi' }).click()

    await waitFor(() => expect(getPortalOrders).toHaveBeenCalledTimes(2))
    await screen.findByText('Belum ada pesanan')
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  getPortalOrders.mockReset()
})
