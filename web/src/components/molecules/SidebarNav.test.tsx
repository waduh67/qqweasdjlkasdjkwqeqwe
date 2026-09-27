import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { IconWifi } from '@/components/atoms/icons'
import { HOTSPOT_VIEW_PERMISSIONS } from '@/api/hotspot'
import { SidebarNav } from './SidebarNav'

const groups = [
  {
    label: 'Layanan Pelanggan',
    items: [
      {
        to: '/hotspot',
        label: 'Hotspot & Voucher',
        permission: HOTSPOT_VIEW_PERMISSIONS,
        icon: IconWifi,
      },
    ],
  },
]

describe('SidebarNav hotspot', () => {
  it('menampilkan menu untuk pengguna yang punya kebijakan view hotspot', () => {
    localStorage.removeItem('hotspot-authorized.v3.closed')

    render(
      <MemoryRouter>
        <SidebarNav
          groups={groups}
          can={(permission) => permission === 'hotspot.voucher.view'}
          storageKey="hotspot-authorized"
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: 'Hotspot & Voucher' }).getAttribute('href')).toBe('/hotspot')
  })

  it('menyembunyikan menu tanpa kebijakan view hotspot', () => {
    render(
      <MemoryRouter>
        <SidebarNav groups={groups} can={() => false} storageKey="hotspot-denied" />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('link', { name: 'Hotspot & Voucher' })).toBeNull()
  })
})

 describe('SidebarNav discovery', () => {
  it('reveals the active section even when previously collapsed', () => {
    localStorage.setItem('nav-active.v3.closed', JSON.stringify(['Layanan Pelanggan']))
    render(<MemoryRouter initialEntries={['/hotspot/vouchers']}><SidebarNav groups={groups} can={() => true} storageKey="nav-active" /></MemoryRouter>)
    expect(screen.getByRole('button', { name: 'Layanan Pelanggan' }).getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('link', { name: 'Hotspot & Voucher' }).getAttribute('aria-current')).toBe('page')
  })
})
