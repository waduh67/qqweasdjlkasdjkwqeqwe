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
    localStorage.setItem('hotspot-authorized.v2', JSON.stringify(['Layanan Pelanggan']))

    render(
      <MemoryRouter>
        <SidebarNav
          groups={groups}
          can={(permission) => permission === 'hotspot.voucher.view'}
          storageKey="hotspot-authorized"
        />
      </MemoryRouter>,
    )

    screen.getByRole('button', { name: 'Layanan Pelanggan' }).click()

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
