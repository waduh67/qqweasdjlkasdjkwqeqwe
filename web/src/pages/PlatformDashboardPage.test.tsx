import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { PlatformDashboardPage } from './PlatformDashboardPage'

const fixtures = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('../api/platform', () => ({ listTenants: fixtures.list }))
vi.mock('../auth/useCan', () => ({ useCan: () => ({ can: () => true }) }))

function show() { return render(<MemoryRouter><PlatformDashboardPage /></MemoryRouter>) }

describe('platform dashboard data states', () => {
  it('does not present a failed request as an empty tenant portfolio', async () => {
    fixtures.list.mockRejectedValueOnce(new Error('unavailable'))
    show()
    expect((await screen.findByRole('alert')).textContent).toContain('Data tenant gagal dimuat')
    expect(screen.queryByText('Belum ada tenant pelanggan')).toBeNull()
    expect(screen.queryByLabelText('Ringkasan tenant')).toBeNull()
  })
  it('explains an empty portfolio when the only entry is the system tenant', async () => {
    fixtures.list.mockResolvedValueOnce({ content: [{ id: 'system', slug: 'platform', name: 'Platform', status: 'ACTIVE' }], totalElements: 1 })
    show()
    expect(await screen.findByText('Belum ada tenant pelanggan')).toBeTruthy()
    expect(screen.queryByLabelText('Ringkasan tenant')).toBeNull()
  })
  it('labels counts as page-scoped when the API has additional pages', async () => {
    fixtures.list.mockResolvedValueOnce({ content: [{ id: 'tenant', slug: 'isp', name: 'ISP', status: 'ACTIVE' }], totalElements: 201 })
    show()
    expect(await screen.findByText('Tenant dalam halaman ini')).toBeTruthy()
    expect(screen.queryByText('Tenant pelanggan')).toBeTruthy()
    expect(screen.getByText('201 entri tersedia di daftar platform')).toBeTruthy()
  })
})
